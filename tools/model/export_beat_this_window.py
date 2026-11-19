"""Export Beat This! small0 for a fixed window length, fold its shapes, remove empty and identity slices, and compare it with the 1500-frame model."""
import collections, os, sys
import numpy as np, onnx, onnxruntime as ort, torch
from onnx import helper, shape_inference
from beat_this.inference import load_model, split_predict_aggregate
from beat_this.model.postprocessor import Postprocessor

frames = int(sys.argv[1]); out = sys.argv[2]; ref_spect = sys.argv[3]
model = load_model("small0", "cpu").eval()


class Wrapper(torch.nn.Module):
    def __init__(self, m):
        super().__init__(); self.m = m

    def forward(self, spect):
        o = self.m(spect)
        return o["beat"], o["downbeat"]


home = os.path.expanduser("~/.btml")
raw = os.path.join(home, f"raw{frames}.onnx")
torch.onnx.export(Wrapper(model).eval(), (torch.randn(1, frames, 128),), raw, input_names=["spect"], output_names=["beat", "downbeat"], opset_version=17, dynamo=False)
m = onnx.load(raw)
for d, v in zip(m.graph.input[0].type.tensor_type.shape.dim, [1, frames, 128]):
    d.ClearField("dim_param"); d.dim_value = v
fixed = os.path.join(home, f"fixed{frames}.onnx"); onnx.save(m, fixed)
so = ort.SessionOptions(); so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
folded = os.path.join(home, f"folded{frames}.onnx"); so.optimized_model_filepath = folded
ort.InferenceSession(fixed, so, providers=["CPUExecutionProvider"])

m = shape_inference.infer_shapes(onnx.load(folded)); g = m.graph
shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim] for v in list(g.value_info) + list(g.input) + list(g.output)}
empty = set(); alias = {}
for n in g.node:
    if n.op_type == "Slice":
        o = shapes.get(n.output[0]); i = shapes.get(n.input[0])
        if o and 0 in o: empty.add(n.output[0])
        elif o and i and o == i: alias[n.output[0]] = n.input[0]
def real(x):
    while x in alias: x = alias[x]
    return x
keep = []
for n in g.node:
    if n.op_type == "Slice" and (n.output[0] in empty or n.output[0] in alias): continue
    for k, x in enumerate(n.input): n.input[k] = real(x)
    if n.op_type == "Concat":
        ins = [x for x in n.input if x not in empty]
        if len(ins) == 1: keep.append(helper.make_node("Identity", ins, list(n.output), name=n.name + "_identity")); continue
        del n.input[:]; n.input.extend(ins)
    keep.append(n)
del g.node[:]; g.node.extend(keep)
outs = {o.name for o in g.output}
while True:
    used = {x for n in g.node for x in n.input} | outs
    alive = [n for n in g.node if any(o in used for o in n.output)]
    if len(alive) == len(g.node): break
    del g.node[:]; g.node.extend(alive)
used = {x for n in g.node for x in n.input}
inits = [i for i in g.initializer if i.name in used]; del g.initializer[:]; g.initializer.extend(inits); del g.value_info[:]
onnx.checker.check_model(m); onnx.save(m, out)
print("ops:", sum(1 for _ in g.node), "slices:", sum(n.op_type == "Slice" for n in g.node), "size:", os.path.getsize(out))

# The beats of the reference song with this window, against the 1500-frame windows of Beat This! itself.
spect = torch.tensor(np.fromfile(ref_spect, dtype="<f4").reshape(-1, 128))
with torch.inference_mode():
    big = split_predict_aggregate(spect, 1500, 6, "keep_first", model)
    small = split_predict_aggregate(spect, frames, 6, "keep_first", model)
post = Postprocessor("minimal")
mask = torch.ones(spect.shape[0], dtype=torch.bool)
b1, _ = post(big["beat"], big["downbeat"], mask); b2, _ = post(small["beat"], small["downbeat"], mask)
same = sum(1 for b in b2 if np.min(np.abs(np.array(b1) - b)) <= 0.021)
print(f"beats with {frames}-frame windows: {len(b2)}, matching the 1500-frame ones: {same} of {len(b1)}")
sess = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
x = spect[:frames].unsqueeze(0).numpy()
with torch.inference_mode():
    t = Wrapper(model)(torch.tensor(x))
r = sess.run(None, {"spect": x})
print("onnx vs torch:", float(np.abs(r[0] - t[0].numpy()).max()))
