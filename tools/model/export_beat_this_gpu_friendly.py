"""Beat This! small0 re-written without tensors of more than four dimensions and without ReduceL2, for GPU back-ends: same weights, same outputs."""
import copy, os, sys
import numpy as np, onnx, onnxruntime as ort, torch
import torch.nn.functional as F
from onnx import helper, shape_inference
from beat_this.inference import load_model
from beat_this.model import roformer as rf

frames = int(sys.argv[1]); out = sys.argv[2]
original = load_model("small0", "cpu").eval()
model = copy.deepcopy(original).eval()


def rms_norm(self, x):
    # F.normalize(x, dim=-1) * scale * gamma, as sum of squares and a reciprocal square root: no ReduceL2, no Clip.
    inv = (x.pow(2).sum(dim=self.dim, keepdim=True) + 1e-12).rsqrt()
    return x * inv * self.scale * self.gamma


def rotate_half_matrix(d, dtype):
    r = torch.zeros(d, d, dtype=dtype)
    for i in range(d // 2):
        r[2 * i + 1, 2 * i] = -1.0
        r[2 * i, 2 * i + 1] = 1.0
    return r


def attention(self, x):
    x = self.norm(x)
    b, n, _ = x.shape
    h = self.heads
    qkv = self.to_qkv(x)
    hd = qkv.shape[-1] // 3
    d = hd // h

    def heads(t):  # [b, n, h*d] -> [b, h, n, d]: four dimensions at most
        return t.reshape(b, n, h, d).transpose(1, 2)

    q = heads(qkv[..., :hd]); k = heads(qkv[..., hd:2 * hd]); v = heads(qkv[..., 2 * hd:])
    if self.rotary_embed is not None:
        freqs = self.rotary_embed.forward(torch.arange(n))  # [n, rot_dim], the angle of each pair repeated twice
        cos, sin = freqs.cos(), freqs.sin()
        rot = freqs.shape[-1]
        r = rotate_half_matrix(rot, q.dtype)

        def rope(t):
            tr, tp = t[..., :rot], t[..., rot:]
            tr = tr * cos + (tr @ r) * sin
            return tr if tp.shape[-1] == 0 else torch.cat([tr, tp], dim=-1)

        q = rope(q); k = rope(k)
    scores = (q * (d ** -0.5)) @ k.transpose(-1, -2)
    out = scores.softmax(dim=-1) @ v
    if self.to_gates is not None:
        gates = self.to_gates(x)  # [b, n, h]
        out = out * gates.transpose(1, 2).unsqueeze(-1).sigmoid()
    out = out.transpose(1, 2).reshape(b, n, h * d)
    return self.to_out(out)


# The reference outputs come from the original code, before the classes are changed.
x = torch.randn(1, frames, 128)
with torch.inference_mode():
    a = original(x)
rf.RMSNorm.forward = rms_norm
rf.Attention.forward = attention

# The two models agree.
with torch.inference_mode():
    b = model(x)
print("max difference of the rewritten model:", float((a["beat"] - b["beat"]).abs().max()), float((a["downbeat"] - b["downbeat"]).abs().max()))


class Wrapper(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m

    def forward(self, spect):
        o = self.m(spect)
        return o["beat"], o["downbeat"]


home = os.path.expanduser("~/.btml")
raw = os.path.join(home, f"gpu_raw{frames}.onnx")
torch.onnx.export(Wrapper(model).eval(), (torch.randn(1, frames, 128),), raw, input_names=["spect"], output_names=["beat", "downbeat"], opset_version=17, dynamo=False)
m = onnx.load(raw)
for dd, v in zip(m.graph.input[0].type.tensor_type.shape.dim, [1, frames, 128]):
    dd.ClearField("dim_param"); dd.dim_value = v
fixed = os.path.join(home, f"gpu_fixed{frames}.onnx"); onnx.save(m, fixed)
so = ort.SessionOptions(); so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
folded = os.path.join(home, f"gpu_folded{frames}.onnx"); so.optimized_model_filepath = folded
ort.InferenceSession(fixed, so, providers=["CPUExecutionProvider"])
m = shape_inference.infer_shapes(onnx.load(folded)); g = m.graph
shapes = {v.name: [dd.dim_value for dd in v.type.tensor_type.shape.dim] for v in list(g.value_info) + list(g.input) + list(g.output)}
alias = {}; empty = set()
for n in g.node:
    if n.op_type == "Slice":
        o = shapes.get(n.output[0]); i = shapes.get(n.input[0])
        if o and 0 in o: empty.add(n.output[0])
        elif o and i and o == i: alias[n.output[0]] = n.input[0]
def real(t):
    while t in alias: t = alias[t]
    return t
keep = []
for n in g.node:
    if n.op_type == "Slice" and (n.output[0] in empty or n.output[0] in alias): continue
    for k, t in enumerate(n.input): n.input[k] = real(t)
    keep.append(n)
del g.node[:]; g.node.extend(keep)
del g.value_info[:]
onnx.checker.check_model(m); onnx.save(m, out)
ranks = {}
sm = shape_inference.infer_shapes(m)
for v in list(sm.graph.value_info):
    r = len(v.type.tensor_type.shape.dim); ranks[r] = ranks.get(r, 0) + 1
import collections
print("tensor ranks:", ranks, "ops:", dict(collections.Counter(n.op_type for n in sm.graph.node).most_common(14)))
sess = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
r = sess.run(None, {"spect": x.numpy()})
print("onnx vs original torch:", float(np.abs(r[0] - a["beat"].numpy()).max()), "size", os.path.getsize(out))
