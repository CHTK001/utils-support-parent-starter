import requests, base64, os, sys, time, subprocess

SERVICE = "http://127.0.0.1:8765"
INPUT_DIR = r"D:\images"
OUTPUT_ROOT = r"D:\images\output"

exts = {".png", ".jpg", ".jpeg", ".bmp", ".webp", ".tiff"}


def wait_service(timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            r = requests.get(f"{SERVICE}/health", timeout=2)
            if r.status_code == 200:
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("service not ready")


def infer_ocr(image_path, model_name, model_type, max_tokens=2048):
    with open(image_path, "rb") as f:
        img_b64 = base64.b64encode(f.read()).decode()
    payload = {
        "model_name": model_name,
        "model_type": model_type,
        "input": {"image": img_b64},
        "params": {"max_new_tokens": max_tokens, "use_gpu": False},
    }
    r = requests.post(f"{SERVICE}/infer", json=payload, timeout=1800)
    data = r.json()
    if data.get("status") != "ok":
        raise RuntimeError(f"infer 失败 http={r.status_code}: {data.get('detail')}")
    return data.get("output", "")


def main():
    model_name = sys.argv[1] if len(sys.argv) > 1 else "ovisocr2"
    model_type = sys.argv[2] if len(sys.argv) > 2 else "document_ocr"

    workdir = os.path.dirname(os.path.abspath(__file__))
    logfile = os.path.join(workdir, "batch_svc.log")
    if os.path.exists(logfile):
        os.remove(logfile)

    # 输出目录按模型分
    out_dir = os.path.join(OUTPUT_ROOT, model_name)
    os.makedirs(out_dir, exist_ok=True)

    # 启动 Python 服务子进程（保证用最新代码）
    print("=== 启动 SafeTensor 服务 ===")
    with open(logfile, "w", encoding="utf-8") as f:
        p = subprocess.Popen(
            [sys.executable, "safetensor_service.py"],
            cwd=workdir,
            stdout=f,
            stderr=subprocess.STDOUT,
            text=True,
        )
    wait_service()
    print("服务就绪")

    files = [f for f in os.listdir(INPUT_DIR) if os.path.splitext(f)[1].lower() in exts]
    print(f"模型: {model_name} ({model_type})  输入: {INPUT_DIR} ({len(files)} 张)")
    print(f"输出: {out_dir}")
    print()

    summary = []
    try:
        for name in sorted(files):
            src = os.path.join(INPUT_DIR, name)
            stem = os.path.splitext(name)[0]
            out_md = os.path.join(out_dir, stem + ".md")
            t0 = time.time()
            try:
                md = infer_ocr(src, model_name, model_type)
                with open(out_md, "w", encoding="utf-8") as f:
                    f.write(md)
                elapsed = time.time() - t0
                print(f"[OK] {name} -> {out_md} ({len(md)} chars, {elapsed:.1f}s)")
                summary.append((name, "PASS", len(md), round(elapsed, 1)))
            except Exception as e:
                print(f"[FAIL] {name}: {e}")
                summary.append((name, "FAIL", 0, 0))
    finally:
        p.terminate()
        try:
            p.wait(timeout=10)
        except Exception:
            p.kill()

    print("\n===== 验收汇总 =====")
    for name, status, chars, secs in summary:
        print(f"  {name}: status={status} bytes={chars} time={secs}s")
    fails = [s for s in summary if s[1] == "FAIL"]
    print(f"\n总 {len(summary)} 张, 通过 {len(summary)-len(fails)}, 失败 {len(fails)}")
    print(f"输出目录: {out_dir}")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()