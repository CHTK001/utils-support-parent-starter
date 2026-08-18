import subprocess, sys, time, requests, base64, os

workdir = r"D:\ch\project\utils-support-parent-starter\utils-support-deeplearning-parent\utils-support-deeplearning-safetensors-starter\python_service"
logfile = os.path.join(workdir, "svc.log")
if os.path.exists(logfile):
    os.remove(logfile)

p = subprocess.Popen(
    [sys.executable, "safetensor_service.py"],
    cwd=workdir,
    stdout=open(logfile, "w", encoding="utf-8"),
    stderr=subprocess.STDOUT,
    text=True,
)

for _ in range(30):
    try:
        r = requests.get("http://127.0.0.1:8765/health", timeout=2)
        if r.status_code == 200:
            break
    except Exception:
        pass
    time.sleep(1)

with open(r"D:\images\document-html.png", "rb") as f:
    img_b64 = base64.b64encode(f.read()).decode()
print("image bytes:", len(img_b64))

payload = {
    "model_name": "ovisocr2",
    "model_type": "document_ocr",
    "input": {"image": img_b64},
    "params": {"max_new_tokens": 128, "use_gpu": False},
}
try:
    r = requests.post("http://127.0.0.1:8765/infer", json=payload, timeout=900)
    print("HTTP", r.status_code, r.text[:500])
except Exception as e:
    print("req error", e)

p.terminate()
try:
    p.wait(timeout=10)
except Exception:
    p.kill()

print("=== log tail ===")
with open(logfile, "r", encoding="utf-8", errors="replace") as f:
    for line in f.readlines()[-50:]:
        print(line.rstrip())