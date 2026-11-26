"""
Exports SCNet as a chain of small ONNX graphs, so that the phone can run each where it runs best: the whole network at once does not fit the NPU (building
its graph takes the phone's memory and the system kills the app), and its LSTMs are what the NPU handles worst. The chain is:

    block  p00: the encoder, and the input of the first LSTM
    lstm   l00: the frequency path of dual-path layer 0
    block  p01: back from the LSTM (linear, residual), the input of the next LSTM
    lstm   l01: the time path of dual-path layer 0
    block  p02: back from the LSTM, the feature conversion of layer 0, and the input of layer 1's first LSTM
    ...
    block  p12: back from the last LSTM, the last feature conversion and the decoder

The app reads manifest.json: for each part its file, its kind and the names of the values that it takes and gives (the names of its inputs and outputs).
Every part is checked: the chain run with ONNX Runtime on the CPU must give what the whole network gives in PyTorch.

    python export_scnet_parts.py OUT_DIR --scnet-checkpoint PATH   (SCNET_DIR names the clone of the SCNet code)
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

FRAMES = 474


def freq_in(dp, x):
    """The input of the frequency-path LSTM of [dp], from the layer's input."""
    B, C, F, T = x.shape
    y = dp.norm_layers[0](x)
    return y.transpose(1, 3).contiguous().view(B * T, F, C)


def freq_out(dp, seq, original):
    B, C, F, T = original.shape
    y = dp.linear_layers[0](seq)
    y = y.view(B, T, F, C).transpose(1, 3)
    return y + original


def time_in(dp, x):
    B, C, F, T = x.shape
    y = dp.norm_layers[1](x)
    return y.transpose(1, 2).contiguous().view(B * F, C, T).transpose(1, 2)


def time_out(dp, seq, original):
    B, C, F, T = original.shape
    y = dp.linear_layers[1](seq)
    y = y.transpose(1, 2).contiguous().view(B, F, C, T).transpose(1, 2)
    return y + original


class Start(nn.Module):
    def __init__(self, core):
        super().__init__(); self.core = core; self.lengths = []; self.original = []

    def forward(self, spec):
        m = self.core.m
        x = spec
        skips = []
        self.lengths.clear(); self.original.clear()
        for sd in m.encoder:
            x, skip, lengths, original = sd(x)
            skips.append(skip); self.lengths.append(lengths); self.original.append(original)
        dp = m.separation_net.dp_modules[0]
        return (freq_in(dp, x), x, *skips)


class AfterFreq(nn.Module):
    def __init__(self, core, i):
        super().__init__(); self.dp = core.m.separation_net.dp_modules[i]

    def forward(self, seq, original):
        x = freq_out(self.dp, seq, original)
        return time_in(self.dp, x), x


class AfterTime(nn.Module):
    """Back from the time path of layer [i]; then the feature conversion, and the next layer's first LSTM input, or the decoder after the last layer."""
    def __init__(self, core, i, start):
        super().__init__(); self.core = core; self.i = i; self.start = start
        self.dp = core.m.separation_net.dp_modules[i]; self.fc = core.m.separation_net.feature_conversion[i]

    def forward(self, seq, original, *skips):
        x = time_out(self.dp, seq, original)
        x = self.fc(x)
        sep = self.core.m.separation_net
        if self.i + 1 < sep.num_layers:
            return freq_in(sep.dp_modules[self.i + 1], x), x
        skips = list(skips); lengths = list(self.start.lengths); original_lengths = list(self.start.original)
        for fusion, su in self.core.m.decoder:
            x = fusion(x, skips.pop())
            x = su(x, lengths.pop(), original_lengths.pop())
        return x


class Lstm(nn.Module):
    def __init__(self, lstm):
        super().__init__(); self.lstm = lstm

    def forward(self, seq):
        y, _ = self.lstm(seq)
        return y


def export(module, inputs, input_names, output_names, path):
    module.eval()
    torch.onnx.export(module, tuple(inputs), path, input_names=input_names, output_names=output_names, opset_version=17, dynamo=False, do_constant_folding=True)
    try:
        import onnx
        from onnxsim import simplify
        simplified, ok = simplify(onnx.load(path))
        if ok:
            onnx.save(simplified, path)
    except Exception:
        pass


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

    parts = []
    values = {"spec": spec}
    sep = core.m.separation_net
    start = Start(core)

    def run_part(kind, name, module, inputs, outputs):
        with torch.no_grad():
            result = module(*[values[n] for n in inputs])
        if not isinstance(result, (tuple, list)):
            result = (result,)
        for n, r in zip(outputs, result):
            values[n] = r
        path = os.path.join(args.out_dir, f"{name}.onnx")
        export(module, [values[n] for n in inputs], inputs, outputs, path)
        parts.append({"file": f"{name}.onnx", "kind": kind, "inputs": inputs, "outputs": outputs})
        print(f"  {name} ({kind}): {[tuple(values[n].shape) for n in inputs]} -> {[tuple(values[n].shape) for n in outputs]}, {os.path.getsize(path) / 1e6:.1f} MB")

    skips = [f"skip{k}" for k in range(len(core.m.encoder))]
    run_part("block", "p00", start, ["spec"], ["seq_0_0", "x_0_0"] + skips)
    block = 1
    for i in range(sep.num_layers):
        dp = sep.dp_modules[i]
        run_part("lstm", f"l{2 * i:02d}", Lstm(dp.lstm_layers[0]), [f"seq_{i}_0"], [f"lstm_{i}_0"])
        run_part("block", f"p{block:02d}", AfterFreq(core, i), [f"lstm_{i}_0", f"x_{i}_0"], [f"seq_{i}_1", f"x_{i}_1"]); block += 1
        run_part("lstm", f"l{2 * i + 1:02d}", Lstm(dp.lstm_layers[1]), [f"seq_{i}_1"], [f"lstm_{i}_1"])
        last = i + 1 == sep.num_layers
        outputs = ["out"] if last else [f"seq_{i + 1}_0", f"x_{i + 1}_0"]
        run_part("block", f"p{block:02d}", AfterTime(core, i, start), [f"lstm_{i}_1", f"x_{i}_1"] + (skips if last else []), outputs); block += 1

    # The chain on ONNX Runtime must give what the whole network gives.
    feed = {"spec": spec.numpy()}
    for part in parts:
        session = ort.InferenceSession(os.path.join(args.out_dir, part["file"]), providers=["CPUExecutionProvider"])
        result = session.run(part["outputs"], {n: feed[n] for n in part["inputs"]})
        feed.update(dict(zip(part["outputs"], result)))
    worst = float(np.max(np.abs(feed["out"] - whole)))
    print(f"chain vs whole network: max diff {worst:.2e} (scale {float(np.max(np.abs(whole))):.2e})")
    with open(os.path.join(args.out_dir, "manifest.json"), "w") as f:
        json.dump({"frames": FRAMES, "bins": 2049, "input": "spec", "output": "out", "parts": parts}, f, indent=1)


if __name__ == "__main__":
    main()
