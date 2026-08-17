import base64, io, requests, json, time
from PIL import Image

b = io.BytesIO()
Image.new('RGB', (400, 60), 'white').save(b, 'PNG')
img = base64.b64encode(b.getvalue()).decode()

payload = {
    "model_name": "ovisocr2",
    "model_type": "document_ocr",
    "input": {"image": img},
    "params": {"max_new_tokens": 128},
}

t0 = time.time()
print("POST /infer start ...")
r = requests.post('http://127.0.0.1:8765/infer', json=payload, timeout=1800)
print("elapsed=%.1fs http=%s" % (time.time() - t0, r.status_code))
print(r.text[:3000])
