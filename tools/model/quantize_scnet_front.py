"""
Makes an 8-bit copy of SCNet's front (scnet_front.onnx, from export_scnet_two.py) for the phone's CPU: the weights of its LSTMs become 8-bit integers,
quantised again for each input (dynamic quantisation). The matrix products stay as they are: they include the Fourier matrices, and 8-bit breaks them. Then it checks the copy on a real song: the
decoder's output with the 8-bit front against the output with the full one, as an SNR, and the time of each front on this computer's CPU.

    python quantize_scnet_front.py PARTS_DIR [MUSIC.pcm]

MUSIC.pcm is 16-bit mono at 44.1 kHz (a stem file from the phone, or a mix); without it, noise is used. The copy is written next to the front as
scnet_front_int8.onnx, which the app uses when "Faster SCNet" is on (files/stems/scnet_two/). On the Galaxy S24 Ultra it takes 1.97 s a chunk instead of
3.15 s, 23 dB from the full network on music.
"""
import os
import sys
import time

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic

FRAMES = 474
SIZE = 4096
HOP = 1024


def spectrogram(signal):
    """The normalised input of SCNet for a stereo chunk whose two channels are [signal], as the app makes it: rectangular windows, "ortho" scale."""
    pad = SIZE // 2
    span = (FRAMES - 1) * HOP + SIZE
    x = np.zeros(span, dtype=np.float64)
    take = min(len(signal), span - pad)
    x[pad:pad + take] = signal[:take]
    frames = np.stack([x[f * HOP:f * HOP + SIZE] for f in range(FRAMES)])
    spec = np.fft.rfft(frames, axis=1) / np.sqrt(SIZE)          # [frames, bins]
    channel = np.stack([spec.real.T, spec.imag.T])                # [2, bins, frames]
    stereo = np.concatenate([channel, channel])[None]             # [1, 4, bins, frames]
    stereo = (stereo - stereo.mean()) / (1e-5 + stereo.std(ddof=1))
    return stereo.astype(np.float32)


def run(front, decoder, spec):
    a = front.run(None, {"spec": spec})
    feed = {i.name: v for i, v in zip(decoder.get_inputs(), a)}
    return decoder.run(None, feed)[0]


def main():
    parts = sys.argv[1]
    src = os.path.join(parts, "scnet_front.onnx")
    dst = os.path.join(parts, "scnet_front_int8.onnx")
    t0 = time.time()
    quantize_dynamic(src, dst, weight_type=QuantType.QInt8, op_types_to_quantize=["LSTM"], per_channel=False)
    print(f"quantised in {time.time() - t0:.0f} s: {os.path.getsize(src) / 1e6:.1f} MB -> {os.path.getsize(dst) / 1e6:.1f} MB")

    if len(sys.argv) > 2:
        music = np.fromfile(sys.argv[2], dtype="<i2").astype(np.float64) / 32768.0
        offset = 44_100 * 60
        signal = music[offset:offset + (FRAMES - 1) * HOP]
    else:
        signal = np.random.default_rng(1).normal(0, .1, (FRAMES - 1) * HOP)
    spec = spectrogram(signal)

    options = ort.SessionOptions(); options.intra_op_num_threads = 4
    decoder = ort.InferenceSession(os.path.join(parts, "scnet_decoder.onnx"), options, providers=["CPUExecutionProvider"])
    full = ort.InferenceSession(src, options, providers=["CPUExecutionProvider"])
    small = ort.InferenceSession(dst, options, providers=["CPUExecutionProvider"])
    reference = run(full, decoder, spec)
    out = run(small, decoder, spec)
    for name, session in (("full", full), ("8-bit", small)):
        t = time.time()
        for _ in range(2):
            session.run(None, {"spec": spec})
        print(f"  front {name}: {(time.time() - t) / 2 * 1000:.0f} ms per chunk on this CPU")
    diff = out.astype(np.float64) - reference
    snr = 10 * np.log10(np.sum(reference.astype(np.float64) ** 2) / np.sum(diff ** 2))
    # Per stem, in the network's order (drums, bass, other, vocals): four channels each (left and right, real and imaginary).
    stems = ["drums", "bass", "other", "vocals"]
    per = []
    for s in range(4):
        r = reference[0, s * 4:(s + 1) * 4].astype(np.float64); d = diff[0, s * 4:(s + 1) * 4]
        per.append(f"{stems[s]} {10 * np.log10(np.sum(r ** 2) / np.sum(d ** 2)):.1f} dB")
    print(f"8-bit front vs full front, decoder output: SNR {snr:.1f} dB ({', '.join(per)})")


if __name__ == "__main__":
    main()
