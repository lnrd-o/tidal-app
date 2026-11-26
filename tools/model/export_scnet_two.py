"""
Exports SCNet in two parts, for a phone that runs the first on the CPU and the second on the NPU:

    scnet_front.onnx    the encoder and the six dual-path layers (all twelve LSTMs), up to the output of the last LSTM   (CPU)
    scnet_decoder.onnx  the end of the last dual-path layer, its feature conversion and the decoder                    (NPU)

The front gives the decoder the last LSTM's output, the residual of the last layer and the encoder's three skips. The chain run with ONNX Runtime on the CPU
must give what the whole network gives in PyTorch, and a manifest.json says what each part takes and gives.

    python export_scnet_two.py OUT_DIR --scnet-checkpoint PATH   (SCNET_DIR names the clone of the SCNet code)
"""
import argparse
import json
import os
import sys

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_stems import ScnetCore  # noqa: E402
from export_scnet_parts import FRAMES, AfterFreq, AfterTime, Lstm, Start, export, freq_out, time_in  # noqa: E402,F401


class Front(nn.Module):
    """From the spectrogram to the output of the last LSTM, with what the decoder needs besides."""

    def __init__(self, core, start):
        super().__init__(); self.core = core; self.start = start
        sep = core.m.separation_net
        self.after_freq = nn.ModuleList([AfterFreq(core, i) for i in range(sep.num_layers)])
        self.after_time = nn.ModuleList([AfterTime(core, i, start) for i in range(sep.num_layers - 1)])
        self.freq_lstm = nn.ModuleList([Lstm(sep.dp_modules[i].lstm_layers[0]) for i in range(sep.num_layers)])
        self.time_lstm = nn.ModuleList([Lstm(sep.dp_modules[i].lstm_layers[1]) for i in range(sep.num_layers)])

    def forward(self, spec):
        seq, x, *skips = self.start(spec)
        layers = self.core.m.separation_net.num_layers
        for i in range(layers):
            seq, x = self.after_freq[i](self.freq_lstm[i](seq), x)
            seq = self.time_lstm[i](seq)
            if i + 1 < layers:
                seq, x = self.after_time[i](seq, x)
        return (seq, x, *skips)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out_dir")
    parser.add_argument("--scnet-checkpoint", required=True)
    args = parser.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, os.environ.get("SCNET_DIR", os.path.join(here, "scnet")))
    from scnet.SCNet import SCNet
    model = SCNet()
    state = torch.load(args.scnet_checkpoint, map_location="cpu")
    model.load_state_dict(state.get("best_state", state.get("state", state)))
    model.eval()
    core = ScnetCore(model, FRAMES).eval()
    torch.manual_seed(3)
    spec = torch.randn(1, 4, 2049, FRAMES)
    with torch.no_grad():
        whole = core(spec).numpy()

    start = Start(core)
    front = Front(core, start).eval()
    last = core.m.separation_net.num_layers - 1
    decoder = AfterTime(core, last, start).eval()
    skips = [f"skip{k}" for k in range(len(core.m.encoder))]
    front_out = ["lstm_last", "x_last"] + skips
    with torch.no_grad():
        values = dict(zip(front_out, front(spec)))
    export(front, [spec], ["spec"], front_out, os.path.join(args.out_dir, "scnet_front.onnx"))
    # The decoder is traced after the front ran, so that the lengths that the encoder kept are those of this chunk.
    export(decoder, [values[n] for n in front_out], front_out, ["out"], os.path.join(args.out_dir, "scnet_decoder.onnx"))
    for name in ("scnet_front.onnx", "scnet_decoder.onnx"):
        print(f"  {name}: {os.path.getsize(os.path.join(args.out_dir, name)) / 1e6:.1f} MB")
    print(f"  front -> decoder: {[(n, tuple(values[n].shape)) for n in front_out]}")

    feed = {"spec": spec.numpy()}
    a = ort.InferenceSession(os.path.join(args.out_dir, "scnet_front.onnx"), providers=["CPUExecutionProvider"])
    feed.update(dict(zip(front_out, a.run(front_out, feed))))
    b = ort.InferenceSession(os.path.join(args.out_dir, "scnet_decoder.onnx"), providers=["CPUExecutionProvider"])
    out = b.run(["out"], {n: feed[n] for n in front_out})[0]
    diff = out - whole
    snr = 10 * np.log10(np.sum(whole.astype(np.float64) ** 2) / max(np.sum(diff.astype(np.float64) ** 2), 1e-30))
    print(f"chain vs whole network (PyTorch FP32): max diff {float(np.max(np.abs(diff))):.2e} (scale {float(np.max(np.abs(whole))):.2e}), SNR {snr:.1f} dB")
    with open(os.path.join(args.out_dir, "manifest.json"), "w") as f:
        json.dump({"frames": FRAMES, "bins": 2049, "input": "spec", "output": "out", "parts": [
            {"file": "scnet_front.onnx", "kind": "front", "inputs": ["spec"], "outputs": front_out},
            {"file": "scnet_decoder.onnx", "kind": "decoder", "inputs": front_out, "outputs": ["out"]},
        ]}, f, indent=1)


if __name__ == "__main__":
    main()
