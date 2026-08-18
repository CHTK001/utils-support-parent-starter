import base64, io
from PIL import Image
import torch
from transformers import AutoProcessor, AutoModelForImageTextToText

model_dir = r"D:\safetensor_models\models\ATH-MaaS--OvisOCR2\snapshots\master"
proc = AutoProcessor.from_pretrained(model_dir, trust_remote_code=True)
model = AutoModelForImageTextToText.from_pretrained(model_dir, trust_remote_code=True, dtype=torch.float32)
model.eval()

b = io.BytesIO()
Image.new("RGB", (400, 60), "white").save(b, "PNG")
image = Image.open(io.BytesIO(b.getvalue())).convert("RGB")

query = "请将这张文档图片转换为 Markdown 格式。"
messages = [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": query}]}]

print("== proc(messages, images) ==")
try:
    inputs34 = proc(messages, images=[image], return_tensors="pt")
    for k, v in inputs34.items():
        print("  ", k, tuple(v.shape) if hasattr(v, "shape") else type(v))
    out = model.generate(**inputs34, max_new_tokens=128)
    print("OK ->", proc.decode(out[0], skip_special_tokens=True)[:300])
except Exception as e:
    import traceback
    traceback.print_exc()