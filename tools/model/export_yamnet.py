"""Convert the convolutional part of YAMNet (log-mel patches in, 521 scores and a 1024 embedding out) to ONNX, with reference data for the Kotlin side."""
import os, sys, csv, json
sys.path.insert(0, os.path.expanduser("~/.btml/yamnet"))
import numpy as np
import tensorflow as tf
import tf_keras
import params as yp
import yamnet as ym
import features as ft
import tf2onnx

home = os.path.expanduser("~/.btml/yamnet")
out_dir = sys.argv[1]; ref_dir = sys.argv[2]
os.makedirs(out_dir, exist_ok=True); os.makedirs(ref_dir, exist_ok=True)
p = yp.Params()

# The network on log-mel patches: 96 frames of 64 bands.
patches = tf_keras.layers.Input(batch_shape=(1, p.patch_frames, p.patch_bands))
predictions, embeddings = ym.yamnet(patches, p)
model = tf_keras.Model(patches, [predictions, embeddings])
model.load_weights(os.path.join(home, "yamnet.h5"), by_name=True)

spec = (tf.TensorSpec((1, p.patch_frames, p.patch_bands), tf.float32, name="patch"),)
onnx_path = os.path.join(out_dir, "yamnet.onnx")
tf2onnx.convert.from_keras(model, input_signature=spec, opset=17, output_path=onnx_path)
print("onnx size:", os.path.getsize(onnx_path))

# The mel filterbank as TensorFlow builds it (257 FFT bins by 64 bands), for the Kotlin features.
mel = tf.signal.linear_to_mel_weight_matrix(num_mel_bins=p.mel_bands, num_spectrogram_bins=257, sample_rate=p.sample_rate, lower_edge_hertz=p.mel_min_hz, upper_edge_hertz=p.mel_max_hz)
mel.numpy().astype("<f4").tofile(os.path.join(out_dir, "yamnet_mel_257x64.bin"))
# The class map for the Kotlin side.
names = [row[2] for row in csv.reader(open(os.path.join(home, "yamnet_class_map.csv"), encoding="utf-8"))][1:]
json.dump(names, open(os.path.join(out_dir, "yamnet_classes.json"), "w"))

# A reference: two seconds of a synthetic tone sweep through the YAMNet feature code and the network.
rate = 16000
t = np.arange(rate * 4) / rate
wave = (0.3 * np.sin(2 * np.pi * (200 + 400 * t) * t) + 0.05 * np.random.default_rng(3).standard_normal(len(t))).astype(np.float32)
wave.astype("<f4").tofile(os.path.join(ref_dir, "yamnet_wave16k.f32"))
log_mel, patch_stack = ft.waveform_to_log_mel_spectrogram_patches(tf.constant(wave), p)
log_mel.numpy().astype("<f4").tofile(os.path.join(ref_dir, "yamnet_logmel.f32"))
patch_stack.numpy().astype("<f4").tofile(os.path.join(ref_dir, "yamnet_patches.f32"))
scores, emb = model(patch_stack[:1])
scores.numpy().astype("<f4").tofile(os.path.join(ref_dir, "yamnet_scores0.f32"))
print("log-mel frames:", log_mel.shape, "patches:", patch_stack.shape, "top class:", names[int(np.argmax(scores.numpy()))])

import onnxruntime as ort
sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
r = sess.run(None, {"patch": patch_stack[:1].numpy()})
print("onnx vs keras max diff:", float(np.abs(r[0] - scores.numpy()).max()))
