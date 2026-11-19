# Models

The neural models of D'JAI are exported on a PC and shipped as ONNX files in `app/src/main/assets`. These scripts make them again.

| Script | Makes |
| --- | --- |
| `export_beat_this.py` | the first ONNX of Beat This! (small0, MIT licence) and the reference vectors that the tests compare with |
| `export_beat_this_window.py` | the shipped model: a fixed window of 500 frames, shapes folded, empty and identity slices removed (the NPU needs fixed shapes) |
| `export_beat_this_gpu_friendly.py` | the same network written with tensors of at most four dimensions (rotary embedding as a matrix product, normalisation without ReduceL2). It matches the original to 2e-6. The Qualcomm GPU back-end builds it only in windows of about 125 frames, too short for good beats, so the app does not ship it |
| `export_yamnet.py` | the convolutional part of YAMNet (Apache 2.0) with its mel filterbank, class list and reference vectors |

They need Python 3.12 with `torch`, `beat-this`, `onnx`, `onnxruntime`, `onnxscript` (Beat This!), and `tensorflow`, `tf-keras`, `tf2onnx` (YAMNet). Keep the environment in a short path on Windows. Run the scripts with `-I`.
