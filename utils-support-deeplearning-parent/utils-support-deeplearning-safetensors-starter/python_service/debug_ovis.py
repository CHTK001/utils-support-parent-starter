import base64, io
from PIL import Image
import torch
from transformers import AutoProcessor, AutoModelForImageTextToText

model_dir = r"D:\safetensor_models\models\ATH-MaaS--OvisOCR2\snapshots\master"
print("loading processor")
proc = AutoProcessor.from_pretrained(model_dir, trust_remote_code=True)
print("loading model (AutoModelForImageTextToText)")
model = AutoModelForImageTextToText.from_pretrained(model_dir, trust_remote_code=True, dtype=torch.float32)
model.eval()
print("model type:", type(model).__name__)

b = io.BytesIO()
Image.new("RGB", (400, 60), "white").save(b, "PNG")
image = Image.open(io.BytesIO(b.getvalue())).convert("RGB")

query = "请将这张文档图片转换为 Markdown 格式。"
print("processor call ...")
try:
    inputs = proc(text=query, images=image, return_tensors="pt")
    for k, v in inputs.items():
        print("  ", k, tuple(v.shape) if hasattr(v, "shape") else type(v))
except Exception as e:
    import traceback
    traceback.print_exc()
    sys.exit(1)

print("generate ...")
try:
    out = model.generate(**inputs, max_new_tokens=128)
    print("OK ->", proc.decode(out[0], skip_special_tokens=True)[:300])
except Exception as e:
    import traceback
    traceback.print_exc()