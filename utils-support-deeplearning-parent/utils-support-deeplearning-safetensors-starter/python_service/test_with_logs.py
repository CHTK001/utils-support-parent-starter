import subprocess, sys, time, requests, base64, io, json
from PIL import Image

print('=== Starting service ===')
p = subprocess.Popen(
    [sys.executable, 'safetensor_service.py'],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1
)

for _ in range(30):
    try:
        requests.get('http://127.0.0.1:8765/health', timeout=2)
        break
    except:
        time.sleep(1)
else:
    print('Service not ready')
    sys.exit(1)

print('Service ready')

b = io.BytesIO()
Image.new('RGB', (400,60), 'white').save(b, 'PNG')
img = base64.b64encode(b.getvalue()).decode()

payload = {
    'model_name': 'ovisocr2',
    'model_type': 'document_ocr',
    'input': {'image': img},
    'params': {'max_new_tokens': 128}
}

print('=== Sending infer ===')
try:
    r = requests.post('http://127.0.0.1:8765/infer', json=payload, timeout=300)
    print(f'HTTP {r.status_code}')
    print(r.text[:1000])
except Exception as e:
    print(f'Request error: {e}')

print('=== Service logs (first 50 lines) ===')
count=0
for line in p.stdout:
    print(line.rstrip())
    count += 1
    if count >= 50:
        break
p.terminate()
