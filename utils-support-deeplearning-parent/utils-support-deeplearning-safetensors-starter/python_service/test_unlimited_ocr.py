import subprocess
import sys
import time
import requests
import base64
import io
from PIL import Image

print("=== Starting service ===")
p = subprocess.Popen(
    [sys.executable, "safetensor_service.py"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1,
    encoding='utf-8'
)

for _ in range(30):
    try:
        r = requests.get("http://127.0.0.1:8765/health", timeout=2)
        if r.status_code == 200:
            break
    except:
        pass
    time.sleep(1)
else:
    print("Service not ready")
    sys.exit(1)

print("Service ready")

b = io.BytesIO()
Image.new('RGB', (400, 60), 'white').save(b, 'PNG')
img = base64.b64encode(b.getvalue()).decode()

payload = {
    "model_name": "unlimited_ocr",
    "model_type": "document_ocr",
    "input": {"image": img},
    "params": {"max_new_tokens": 128, "use_gpu": False}
}

print("=== Sending inference request ===")
try:
    r = requests.post("http://127.0.0.1:8765/infer", json=payload, timeout=600)
    print(f"HTTP {r.status_code}")
    print(r.text[:2000])
except Exception as e:
    print(f"Request error: {e}")

print("=== Service logs (first 30 lines) ===")
count = 0
for line in p.stdout:
    print(line.rstrip())
    count += 1
    if count >= 30:
        break

p.terminate()
