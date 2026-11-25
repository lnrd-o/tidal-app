"""
Exports four music source separation networks to ONNX for the phone benchmark of the stems feature: HT Demucs, SCNet, Open-Unmix (umxhq) and a
Spleeter-shaped U-Net. Each is exported as its "core": the network between the spectrogram and the separated spectrograms, with fixed shapes, because
the STFT and its inverse cannot be exported (or run on an NPU) and are done by the app, as for the rhythm model.

    python export_stems.py OUT_DIR [--scnet-checkpoint PATH] [--only NAME]

Weights: HT Demucs and Open-Unmix load their published weights. SCNet loads its checkpoint when one is given, else random weights. The Spleeter-shaped
U-Net has random weights: it is there to measure what a network of that shape costs, not to separate. Speed does not depend on the weights.
Every export is checked against PyTorch with ONNX Runtime on the CPU before it is written.
"""
import argparse
import math
import os
import sys
import time

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F

SR = 44100


def check_and_save(name, module, inputs, input_names, output_names, out_dir):
    module.eval()
    with torch.no_grad():
        t0 = time.time()
        reference = module(*inputs)
        torch_ms = (time.time() - t0) * 1000
    if not isinstance(reference, (tuple, list)):
        reference = (reference,)
    path = os.path.join(out_dir, f"stems_{name}.onnx")
    torch.onnx.export(module, tuple(inputs), path, input_names=input_names, output_names=output_names, opset_version=17, dynamo=False, do_constant_folding=True)
    try:
        import onnx
        from onnxsim import simplify
        simplified, ok = simplify(onnx.load(path))
        if ok:
            onnx.save(simplified, path)
    except Exception as e:  # the simplifier is a nicety
        print(f"  {name}: simplifier skipped ({e})")
    session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    feed = {n: t.numpy() for n, t in zip(input_names, inputs)}
    t0 = time.time()
    outputs = session.run(None, feed)
    ort_ms = (time.time() - t0) * 1000
    worst = max(float(np.max(np.abs(o - r.numpy()))) for o, r in zip(outputs, reference))
    scale = max(float(np.max(np.abs(r.numpy()))) for r in reference)
    size = os.path.getsize(path) / 1e6
    ops = sorted({n.op_type for n in __import__("onnx").load(path).graph.node})
    print(f"  {name}: {size:.1f} MB, max |onnx - torch| = {worst:.2e} (scale {scale:.2e}); torch {torch_ms:.0f} ms, onnx cpu {ort_ms:.0f} ms")
    print(f"  {name}: inputs {[tuple(t.shape) for t in inputs]}; ops {ops}")
    return path


# ---------------------------------------------------------------- HT Demucs
class DemucsCore(nn.Module):
    """HT Demucs between the spectrogram and the outputs: takes the waveform (time branch) and the complex spectrogram as channels (cac), returns the
    separated spectrograms (as channels) and the time-branch waveforms. The app does the STFT before and the inverse STFT and the sum after."""

    def __init__(self, model):
        super().__init__()
        self.m = model

    def forward(self, mix, mag):
        m = self.m
        x = mag
        B, C, Fq, T = x.shape
        mean = x.mean(dim=(1, 2, 3), keepdim=True)
        std = x.std(dim=(1, 2, 3), keepdim=True)
        x = (x - mean) / (1e-5 + std)
        xt = mix
        meant = xt.mean(dim=(1, 2), keepdim=True)
        stdt = xt.std(dim=(1, 2), keepdim=True)
        xt = (xt - meant) / (1e-5 + stdt)
        saved, saved_t, lengths, lengths_t = [], [], [], []
        for idx, encode in enumerate(m.encoder):
            lengths.append(x.shape[-1])
            inject = None
            if idx < len(m.tencoder):
                lengths_t.append(xt.shape[-1])
                tenc = m.tencoder[idx]
                xt = tenc(xt)
                if not tenc.empty:
                    saved_t.append(xt)
                else:
                    inject = xt
            x = encode(x, inject)
            if idx == 0 and m.freq_emb is not None:
                frs = torch.arange(x.shape[-2], device=x.device)
                emb = m.freq_emb(frs).t()[None, :, :, None].expand_as(x)
                x = x + m.freq_emb_scale * emb
            saved.append(x)
        if m.crosstransformer:
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_upsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_upsampler_t(xt)
            x, xt = m.crosstransformer(x, xt)
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = x.reshape(b, c, f * t)
                x = m.channel_downsampler(x)
                x = x.reshape(b, -1, f, t)
                xt = m.channel_downsampler_t(xt)
        for idx, decode in enumerate(m.decoder):
            skip = saved.pop(-1)
            x, pre = decode(x, skip, lengths.pop(-1))
            offset = m.depth - len(m.tdecoder)
            if idx >= offset:
                tdec = m.tdecoder[idx - offset]
                length_t = lengths_t.pop(-1)
                if tdec.empty:
                    pre = pre[:, :, 0]
                    xt, _ = tdec(pre, None, length_t)
                else:
                    skip = saved_t.pop(-1)
                    xt, _ = tdec(xt, skip, length_t)
        S = len(m.sources)
        x = x.view(B, S, -1, Fq, T)
        x = x * std[:, None] + mean[:, None]
        xt = xt.view(B, S, -1, mix.shape[-1])
        xt = xt * stdt[:, None] + meant[:, None]
        return x, xt


def export_demucs(out_dir):
    from demucs.pretrained import get_model
    bag = get_model("htdemucs")
    model = bag.models[0] if hasattr(bag, "models") else bag
    model.eval()
    length = int(model.segment * SR)  # 7.8 s, the longest that the transformer takes
    mix = torch.randn(1, 2, length) * .1
    z = model._spec(mix)
    mag = model._magnitude(z)
    core = DemucsCore(model)
    # The core must give what the whole model gives once the app adds the inverse STFT.
    with torch.no_grad():
        x, xt = core(mix, mag)
        zout = model._mask(z, x)
        whole = model._ispec(zout, length) + xt
        full = model(mix)
    print(f"  htdemucs: core + inverse STFT vs the whole model: max diff {float((whole - full).abs().max()):.2e}")
    return check_and_save("htdemucs", core, [mix, mag], ["mix", "spec"], ["spec_out", "wave_out"], out_dir)


# ---------------------------------------------------------------- SCNet
class RfftMatmul(nn.Module):
    """torch.fft.rfft/irfft along the frames (norm "ortho") as products with fixed matrices: an NPU has no FFT, and the number of frames is fixed."""

    def __init__(self, channels, inverse, frames):
        super().__init__()
        self.inverse = inverse
        self.channels = channels
        k = frames // 2 + 1
        n = torch.arange(frames, dtype=torch.float64)
        f = torch.arange(k, dtype=torch.float64)
        angle = 2 * math.pi * f[:, None] * n[None, :] / frames  # [k, frames]
        scale = 1 / math.sqrt(frames)
        if inverse:
            w = torch.full((k, 1), 2.0, dtype=torch.float64)
            w[0] = 1.0
            if frames % 2 == 0:
                w[-1] = 1.0
            self.register_buffer("c", (w * torch.cos(angle) * scale).float())   # [k, frames]
            self.register_buffer("s", (w * torch.sin(angle) * scale).float())
        else:
            self.register_buffer("c", (torch.cos(angle).t() * scale).float())    # [frames, k]
            self.register_buffer("s", (-torch.sin(angle).t() * scale).float())

    def forward(self, x):
        if self.inverse:
            xr = x[:, : self.channels // 2]
            xi = x[:, self.channels // 2:]
            return xr @ self.c - xi @ self.s
        return torch.cat([x @ self.c, x @ self.s], dim=1)


class ScnetCore(nn.Module):
    """SCNet between the normalised spectrogram and the separated spectrograms (complex as channels)."""

    def __init__(self, model, frames):
        super().__init__()
        self.m = model
        sep = model.separation_net
        for i, conv in enumerate(sep.feature_conversion):
            sep.feature_conversion[i] = RfftMatmul(conv.channels, conv.inverse, frames if not conv.inverse else frames)

    def forward(self, x):
        m = self.m
        from collections import deque
        save_skip, save_lengths, save_original = deque(), deque(), deque()
        for sd in m.encoder:
            x, skip, lengths, original = sd(x)
            save_skip.append(skip)
            save_lengths.append(lengths)
            save_original.append(original)
        x = m.separation_net(x)
        for fusion, su in m.decoder:
            x = fusion(x, save_skip.pop())
            x = su(x, save_lengths.pop(), save_original.pop())
        return x


def export_scnet(out_dir, checkpoint):
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, os.environ.get("SCNET_DIR", os.path.join(here, "scnet")))
    from scnet.SCNet import SCNet
    model = SCNet()
    if checkpoint:
        state = torch.load(checkpoint, map_location="cpu")
        state = state.get("best_state", state.get("state", state))
        model.load_state_dict(state)
        print("  scnet: published weights loaded")
    else:
        print("  scnet: random weights (speed only)")
    model.eval()
    # About 11 s, the training segment; the frames must be even for the rfft between the dual-path layers, and the down-sampling by 4 and 16 of the
    # frequency bands works on 2049 bins.
    frames = 474
    x = torch.randn(1, 4, 2049, frames)
    # The matrix rfft must match torch's before it replaces it.
    test = torch.randn(1, 8, 5, frames)
    a = torch.fft.rfft(test, dim=3, norm="ortho")
    b = RfftMatmul(16, False, frames)(test)
    print(f"  scnet: rfft by matrices vs torch: {float((torch.cat([a.real, a.imag], 1) - b).abs().max()):.2e}")
    c = torch.fft.irfft(a, dim=3, norm="ortho")
    d = RfftMatmul(16, True, frames)(torch.cat([a.real, a.imag], 1))
    print(f"  scnet: irfft by matrices vs torch: {float((c - d).abs().max()):.2e}")
    reference_model = SCNet()
    reference_model.load_state_dict(model.state_dict())
    core = ScnetCore(model, frames)
    return check_and_save("scnet", core, [x], ["spec"], ["spec_out"], out_dir)


# ---------------------------------------------------------------- Open-Unmix
class UmxCore(nn.Module):
    """The four Open-Unmix target networks of umxhq on one magnitude spectrogram: returns the four magnitude estimates."""

    def __init__(self, targets):
        super().__init__()
        self.targets = nn.ModuleList(targets)

    def forward(self, mag):
        return torch.stack([t(mag) for t in self.targets], dim=0)


def export_umx(out_dir):
    import openunmix
    separator = openunmix.umxhq(device="cpu")
    targets = [separator.target_models[name] for name in ["vocals", "drums", "bass", "other"]]
    frames = 258  # about 6 s at a hop of 1024
    mag = torch.rand(1, 2, 2049, frames)
    return check_and_save("umxhq", UmxCore(targets), [mag], ["mag"], ["mag_out"], out_dir)


# ---------------------------------------------------------------- Spleeter-shaped U-Net
class SpleeterUNet(nn.Module):
    """The U-Net of Spleeter for one stem: six 5×5 stride-2 convolutions (16 … 512 filters) with batch norm and leaky ReLU, six transposed ones back with
    skips, and a sigmoid mask. Random weights: this measures the cost of the shape."""

    def __init__(self, channels=2):
        super().__init__()
        filters = [16, 32, 64, 128, 256, 512]
        self.down = nn.ModuleList()
        c = channels
        for f in filters:
            self.down.append(nn.Sequential(nn.Conv2d(c, f, 5, 2, 2), nn.BatchNorm2d(f), nn.LeakyReLU(.2)))
            c = f
        self.up = nn.ModuleList()
        ups = [256, 128, 64, 32, 16, 1]
        for i, f in enumerate(ups):
            cin = c if i == 0 else c * 2
            self.up.append(nn.Sequential(nn.ConvTranspose2d(cin, f, 5, 2, 2, output_padding=1), nn.ReLU(), nn.BatchNorm2d(f)))
            c = f
        self.mask = nn.Conv2d(1, channels, 4, padding=3, dilation=2)

    def forward(self, x):
        skips = []
        for d in self.down:
            x = d(x)
            skips.append(x)
        skips.pop()
        for i, u in enumerate(self.up):
            if i > 0:
                x = torch.cat([x, skips.pop()], dim=1)
            x = u(x)
        return torch.sigmoid(self.mask(x))


class SpleeterCore(nn.Module):
    def __init__(self):
        super().__init__()
        self.stems = nn.ModuleList([SpleeterUNet() for _ in range(4)])

    def forward(self, mag):
        return torch.stack([s(mag) for s in self.stems], dim=0)


def export_spleeter(out_dir):
    mag = torch.rand(1, 2, 512, 1024)  # 512 frames of 1024 bins, about 11.9 s at a hop of 1024
    return check_and_save("spleeter", SpleeterCore(), [mag], ["mag"], ["mask"], out_dir)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("out_dir")
    parser.add_argument("--scnet-checkpoint")
    parser.add_argument("--only")
    args = parser.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    torch.manual_seed(0)
    jobs = {"htdemucs": lambda: export_demucs(args.out_dir), "scnet": lambda: export_scnet(args.out_dir, args.scnet_checkpoint),
            "umxhq": lambda: export_umx(args.out_dir), "spleeter": lambda: export_spleeter(args.out_dir)}
    for name, job in jobs.items():
        if args.only and name != args.only:
            continue
        print(f"{name}:")
        try:
            job()
        except Exception as e:
            print(f"  {name} FAILED: {type(e).__name__}: {e}")
