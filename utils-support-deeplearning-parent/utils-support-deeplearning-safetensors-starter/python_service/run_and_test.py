import subprocess, sys, time, requests, base64, io, os
from PIL import Image

workdir = os.path.dirname(os.path.abspath(__file__))
print("=== Starting service ===")
p = subprocess.Popen(
    [sys.executable, "safetensor_service.py"],
    cwd=workdir,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1,
    encoding="utf-8",
    errors="replace",
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
Image.new("RGB", (400, 60), "white").save(b, "PNG")
img = base64.b64encode(b.getvalue()).decode()

payload = {
    "model_name": "ovisocr2",
    "model_type": "document_ocr",
    "input": {"image": img},
    "params": {"max_new_tokens": 128, "use_gpu": False},
}

print("=== Sending inference ===")
try:
    r = requests.post("http://127.0.0.1:8765/infer", json=payload, timeout=600)
    print(f"HTTP {r.status_code}")
    print(r.text[:1500])
except Exception as e:
    print(f"Request error: {e}")

print("=== Service logs (first 40 lines) ===")
count = 0
for line in p.stdout:
    print(line.rstrip())
    count += 1
    if count >= 40:
        break

p.terminate()
