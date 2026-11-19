"""Export Beat This! small0 to ONNX for the app, with the mel filterbank and reference vectors to check the Android implementation against."""
import json, os, sys, time
import numpy as np
import torch, torchaudio
import onnxruntime as ort
from beat_this.inference import load_model, split_predict_aggregate
from beat_this.preprocessing import LogMelSpect
from beat_this.model.postprocessor import Postprocessor

out_dir = sys.argv[1]
ref_dir = sys.argv[2]
os.makedirs(out_dir, exist_ok=True); os.makedirs(ref_dir, exist_ok=True)

model = load_model("small0", "cpu").eval()
print("parameters:", sum(p.numel() for p in model.parameters()))


class Wrapper(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m

    def forward(self, spect):
        o = self.m(spect)
        return o["beat"], o["downbeat"]


wrapper = Wrapper(model).eval()
dummy = torch.randn(1, 1500, 128)
onnx_path = os.path.join(out_dir, "beat_this_small0.onnx")
torch.onnx.export(wrapper, (dummy,), onnx_path, input_names=["spect"], output_names=["beat", "downbeat"], opset_version=17, dynamo=False)
print("onnx size:", os.path.getsize(onnx_path))

# The mel filterbank, as torchaudio builds it for LogMelSpect: 513 bins by 128 bands, row-major little-endian float32.
fb = torchaudio.functional.melscale_fbanks(513, 30.0, 11000.0, 128, 22050, norm=None, mel_scale="slaney")
fb.numpy().astype("<f4").tofile(os.path.join(out_dir, "mel_fb_513x128.bin"))

# A reference song: 40 s at 22050 Hz, 124 BPM, kick on every beat, a hat between, a chord. Its spectrogram, the model's output and the beats.
sr = 22050; seconds = 40; bpm = 124.0
n = sr * seconds
t = np.arange(n) / sr
y = np.zeros(n)
period = 60.0 / bpm
rng = np.random.default_rng(1)
beat = 0.25
k = 0
while beat < seconds:
    s = int(beat * sr)
    m = np.arange(min(int(0.12 * sr), n - s))
    amp = 0.7 if k % 4 == 0 else 0.5
    y[s:s + len(m)] += amp * np.exp(-m / (0.04 * sr)) * np.sin(2 * np.pi * 55 * m / sr)
    h = int((beat + period / 2) * sr)
    if h < n:
        mm = np.arange(min(500, n - h))
        y[h:h + len(mm)] += 0.15 * rng.standard_normal(len(mm)) * np.exp(-mm / 100)
    beat += period; k += 1
y += 0.05 * (np.sin(2 * np.pi * 220 * t) + np.sin(2 * np.pi * 277.2 * t) + np.sin(2 * np.pi * 329.6 * t))
y = y.astype(np.float32)
y.astype("<f4").tofile(os.path.join(ref_dir, "song_22050.f32"))

spect = LogMelSpect()(torch.tensor(y))
print("spect shape:", tuple(spect.shape))
spect.numpy().astype("<f4").tofile(os.path.join(ref_dir, "spect.f32"))
with torch.inference_mode():
    pred = split_predict_aggregate(spect, 1500, 6, "keep_first", model)
beats, downbeats = Postprocessor("minimal")(pred["beat"], pred["downbeat"], torch.ones_like(pred["beat"], dtype=torch.bool))
pred["beat"].numpy().astype("<f4").tofile(os.path.join(ref_dir, "beat_logits.f32"))
pred["downbeat"].numpy().astype("<f4").tofile(os.path.join(ref_dir, "downbeat_logits.f32"))
ibi = np.diff(beats)
ref = {"frames": int(spect.shape[0]), "beats": [float(b) for b in beats], "downbeats": [float(d) for d in downbeats], "bpm": float(60.0 / np.median(ibi)) if len(ibi) else None, "truth_bpm": bpm}
json.dump(ref, open(os.path.join(ref_dir, "reference.json"), "w"), indent=1)
print("reference bpm:", ref["bpm"], "beats:", len(beats), "downbeats:", len(downbeats))

# ONNX Runtime agrees with PyTorch on the first window.
sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
window = spect[:1500].unsqueeze(0).numpy()
t0 = time.time(); b, d = sess.run(None, {"spect": window}); dt = time.time() - t0
with torch.inference_mode():
    tb, td = wrapper(torch.tensor(window))
print("onnx vs torch max diff: beat %.2e downbeat %.2e, one window on this CPU %.0f ms" % (np.abs(b - tb.numpy()).max(), np.abs(d - td.numpy()).max(), dt * 1000))
