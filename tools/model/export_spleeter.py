"""
Exports the published Spleeter 4-stem network (Deezer, v1.4.0 checkpoint) to ONNX for the stems feature.

    python export_spleeter.py CHECKPOINT_DIR OUT.onnx [--check]

The checkpoint is the unpacked 4stems.tar.gz of https://github.com/deezer/spleeter/releases/tag/v1.4.0 (model.index, model.data-*, model.meta). The ONNX file
is the network of the four instruments on one magnitude spectrogram: input "mag" [1, 2, 512, 1024] (channels, 512 frames, 1024 bins of the 4096-point
STFT at a hop of 1024), output "mask" [4, 1, 2, 512, 1024] with the sigmoid masks of vocals, drums, bass and other. The app does the STFT, makes the ratio
masks (mask² over the sum of the squares, the "separation exponent" of 2 of the model) and the inverse STFT.

With --check, the PyTorch network is compared with the original TensorFlow graph on a test signal before the file is written.
"""
import argparse
import os
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

INSTRUMENTS = ["vocals", "drums", "bass", "other"]


class SameConv(nn.Module):
    """A 5×5 stride-2 convolution with TensorFlow's "same" padding: 1 before and 2 after on each axis, which differs from PyTorch's symmetric 2 and 2."""

    def __init__(self, cin, cout):
        super().__init__()
        self.conv = nn.Conv2d(cin, cout, 5, 2, 0)

    def forward(self, x):
        return self.conv(F.pad(x, (1, 2, 1, 2)))


class SameDeconv(nn.Module):
    """A 5×5 stride-2 transposed convolution with TensorFlow's "same" cropping: 1 from the start and 2 from the end of each axis."""

    def __init__(self, cin, cout):
        super().__init__()
        self.conv = nn.ConvTranspose2d(cin, cout, 5, 2, 0)

    def forward(self, x):
        h, w = x.shape[2] * 2, x.shape[3] * 2
        return self.conv(x)[:, :, 1:1 + h, 1:1 + w]


class StemNet(nn.Module):
    """One instrument's U-Net, as unet.py of Spleeter builds it with ELU activations: six down convolutions (batch norm and ELU after each), six up
    transposed convolutions (ELU, batch norm, no dropout at inference) joined to the raw outputs of the down convolutions, and a 4×4 dilated sigmoid mask."""

    def __init__(self):
        super().__init__()
        f = [16, 32, 64, 128, 256, 512]
        self.down = nn.ModuleList([SameConv(c, o) for c, o in zip([2] + f[:-1], f)])
        self.down_bn = nn.ModuleList([nn.BatchNorm2d(o, eps=1e-3) for o in f])
        ups = [256, 128, 64, 32, 16, 1]
        cins = [512, 512, 256, 128, 64, 32]
        self.up = nn.ModuleList([SameDeconv(c, o) for c, o in zip(cins, ups)])
        self.up_bn = nn.ModuleList([nn.BatchNorm2d(o, eps=1e-3) for o in ups])
        self.mask = nn.Conv2d(1, 2, 4, 1, 3, dilation=2)

    def forward(self, x):
        convs = []
        h = x
        for conv, bn in zip(self.down, self.down_bn):
            raw = conv(h)
            convs.append(raw)
            h = F.elu(bn(raw))
        # The first up-convolution reads the raw output of the last down convolution; the others read the join with the matching down output.
        up = self.up_bn[0](F.elu(self.up[0](convs[5])))
        for i in range(1, 6):
            joined = torch.cat([convs[5 - i], up], dim=1)
            up = self.up_bn[i](F.elu(self.up[i](joined)))
        return torch.sigmoid(self.mask(up))


class SpleeterCore(nn.Module):
    def __init__(self):
        super().__init__()
        self.stems = nn.ModuleList([StemNet() for _ in INSTRUMENTS])

    def forward(self, mag):
        return torch.stack([s(mag) for s in self.stems], dim=0)


def load_weights(core, reader, order):
    """Copies the checkpoint into the network. [order] gives, for each of the four instruments, the index of its U-Net in the checkpoint (they are numbered
    in the order that the graph made them, which is not always the order of the list)."""
    def conv(name):
        w = reader.get_tensor(f"{name}/kernel")  # [kh, kw, cin, cout]
        b = reader.get_tensor(f"{name}/bias")
        return torch.from_numpy(w.transpose(3, 2, 0, 1).copy()), torch.from_numpy(b.copy())

    def deconv(name):
        w = reader.get_tensor(f"{name}/kernel")  # [kh, kw, cout, cin]
        b = reader.get_tensor(f"{name}/bias")
        return torch.from_numpy(w.transpose(3, 2, 0, 1).copy()), torch.from_numpy(b.copy())

    def bn(module, name):
        module.weight.data = torch.from_numpy(reader.get_tensor(f"{name}/gamma").copy())
        module.bias.data = torch.from_numpy(reader.get_tensor(f"{name}/beta").copy())
        module.running_mean = torch.from_numpy(reader.get_tensor(f"{name}/moving_mean").copy())
        module.running_var = torch.from_numpy(reader.get_tensor(f"{name}/moving_variance").copy())

    def suffix(prefix, n):
        return prefix if n == 0 else f"{prefix}_{n}"

    for stem, k in zip(core.stems, order):
        for i in range(6):
            w, b = conv(suffix("conv2d", 7 * k + i))
            stem.down[i].conv.weight.data, stem.down[i].conv.bias.data = w, b
            bn(stem.down_bn[i], suffix("batch_normalization", 12 * k + i))
        for i in range(6):
            w, b = deconv(suffix("conv2d_transpose", 6 * k + i))
            stem.up[i].conv.weight.data, stem.up[i].conv.bias.data = w, b
            bn(stem.up_bn[i], suffix("batch_normalization", 12 * k + 6 + i))
        w, b = conv(suffix("conv2d", 7 * k + 6))
        stem.mask.weight.data, stem.mask.bias.data = w, b


def tensorflow_reference(checkpoint, seconds=12.0):
    """Runs the original graph on a test signal: returns the spectrogram that it feeds to its networks, the four masks, and the order of the networks."""
    import tensorflow as tf
    tf.compat.v1.disable_eager_execution()
    g = tf.Graph()
    with g.as_default():
        saver = tf.compat.v1.train.import_meta_graph(os.path.join(checkpoint, "model.meta"))
        mask_ops, order = [], []
        for name in INSTRUMENTS:
            mul = g.get_operation_by_name(f"{name}_spectrogram/mul")
            mask = mul.inputs[0]
            mask_ops.append(mask)
            order.append(int(mask.op.inputs[0].op.name.split("conv2d")[1].lstrip("_").split("/")[0] or 0) // 7)
        spec = g.get_operation_by_name("strided_slice_3").outputs[0]
        rng = np.random.RandomState(0)
        n = int(44100 * seconds)
        t = np.arange(n) / 44100.0
        wave = np.stack([.3 * np.sin(2 * np.pi * 220 * t) + .2 * rng.randn(n) * (np.sin(2 * np.pi * 2 * t) > 0) + .25 * np.sin(2 * np.pi * 55 * t),
                         .3 * np.sin(2 * np.pi * 330 * t) + .2 * rng.randn(n) * (np.sin(2 * np.pi * 3 * t) > 0) + .25 * np.sin(2 * np.pi * 55 * t)], axis=1).astype(np.float32)
        with tf.compat.v1.Session(graph=g) as sess:
            saver.restore(sess, os.path.join(checkpoint, "model"))
            values = sess.run([spec] + mask_ops, {"waveform:0": wave})
    return values[0], values[1:], order


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("checkpoint")
    parser.add_argument("out")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    import tensorflow as tf
    reader = tf.train.load_checkpoint(os.path.join(args.checkpoint, "model"))
    order = list(range(4))
    reference = None
    if args.check:
        spec, masks, order = tensorflow_reference(args.checkpoint)
        reference = (spec, masks)
        print("order of the networks in the checkpoint:", order)
    core = SpleeterCore()
    load_weights(core, reader, order)
    core.eval()
    if reference:
        spec, masks = reference
        x = torch.from_numpy(spec[:1].transpose(0, 3, 1, 2).copy())  # [1, 2, 512, 1024]
        with torch.no_grad():
            out = core(x).numpy()
        for i, name in enumerate(INSTRUMENTS):
            tf_mask = masks[i][0].transpose(2, 0, 1)
            diff = float(np.abs(out[i, 0] - tf_mask).max())
            print(f"  {name}: max |torch - tensorflow| of the mask = {diff:.2e}")
    x = torch.rand(1, 2, 512, 1024)
    torch.onnx.export(core, (x,), args.out, input_names=["mag"], output_names=["mask"], opset_version=17, dynamo=False, do_constant_folding=True)
    try:
        import onnx
        from onnxsim import simplify
        simplified, ok = simplify(onnx.load(args.out))
        if ok:
            onnx.save(simplified, args.out)
    except Exception as e:
        print("simplifier skipped:", e)
    import onnxruntime as ort
    session = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    got = session.run(None, {"mag": x.numpy()})[0]
    with torch.no_grad():
        want = core(x).numpy()
    print(f"onnx vs torch: {float(np.abs(got - want).max()):.2e}; {os.path.getsize(args.out) / 1e6:.1f} MB")
