#!/usr/bin/env python3
"""
SafeTensor 模型批量测试脚本 — 通过 HTTP 调用服务，测试所有 🟡 状态的模型。
用法: python test_safetensor_models.py [--quick]
"""
import argparse, base64, io, json, logging, os, sys, time, traceback
from pathlib import Path
from urllib import request as urlreq
from urllib.error import URLError

logging.basicConfig(level=logging.INFO, format="%(message)s")
log = logging.getLogger("test")

SERVICE_URL = "http://127.0.0.1:8765"

def call_infer(model_name: str, model_type: str, input_data: dict, params: dict = None):
    """调用 /infer 接口"""
    payload = {
        "model_name": model_name,
        "model_type": model_type,
        "input": input_data,
        "params": params or {}
    }
    data = json.dumps(payload).encode("utf-8")
    req = urlreq.Request(
        f"{SERVICE_URL}/infer",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    resp = urlreq.urlopen(req, timeout=1800)
    return json.loads(resp.read().decode("utf-8"))

def test_model(name: str, mtype: str, input_data: dict, params: dict = None, runs: int = 1):
    """测试单个模型，返回 (success, elapsed_ms, result_summary)"""
    times = []
    result = None
    for i in range(runs):
        t0 = time.time()
        try:
            resp = call_infer(name, mtype, input_data, params)
            elapsed = (time.time() - t0) * 1000
            times.append(elapsed)
            result = resp.get("output", "")
            ok = resp.get("status") == "ok"
        except Exception as e:
            elapsed = (time.time() - t0) * 1000
            times.append(elapsed)
            return False, elapsed, str(e)[:120]
    avg_ms = sum(times) / len(times)
    result_str = str(result)[:80] if result else ""
    return True, avg_ms, result_str

# ===== 测试用例 =====
TEST_CASES = [
    # (name, model_type, input, params, display_name)

    # --- LLM ---
    ("qwen2.5-3b", "llm",
     {"text": "你好，请用一句话介绍自己"},
     {"max_new_tokens": 64}, "Qwen2.5-3B"),

    ("qwen2.5-7b", "llm",
     {"text": "你好，请用一句话介绍自己"},
     {"max_new_tokens": 64}, "Qwen2.5-7B"),

    ("qwen2.5-14b", "llm",
     {"text": "你好，请用一句话介绍自己"},
     {"max_new_tokens": 64}, "Qwen2.5-14B"),

    # --- 文生图 (CPU) ---
    ("tiny-sd", "image_gen",
     {"prompt": "a cute cat, cartoon style"},
     {"steps": 10}, "TinySD"),

    # --- 文生图 (GPU) ---
    ("sd1.5", "image_gen",
     {"prompt": "a cute cat"},
     {"steps": 20}, "SD1.5"),

    ("sdxl-turbo", "image_gen",
     {"prompt": "a cute cat"},
     {"steps": 4}, "SDXL-Turbo"),

    # --- 换装 ---
    ("hr-viton", "tryon",
     {"prompt": "a woman wearing a dress", "image": ""},
     {"steps": 20}, "HR-VITON"),

    # --- Qwen-Image LoRA ---
    ("nano_banana", "tryon",
     {"prompt": "a woman wearing a dress, disassemble clothes"},
     {"steps": 50, "true_cfg_scale": 4.0}, "Nano-Banana"),
]

def main():
    parser = argparse.ArgumentParser(description="批量测试 safetensor 模型")
    parser.add_argument("--quick", action="store_true", help="仅测试已下载的模型")
    args = parser.parse_args()

    # 检查服务是否运行
    try:
        resp = urlreq.urlopen(f"{SERVICE_URL}/health", timeout=5)
        health = json.loads(resp.read().decode())
        log.info(f"✅ 服务运行中: {health}")
    except Exception as e:
        log.error(f"❌ 服务未运行: {e}")
        sys.exit(1)

    # 列出已下载模型
    try:
        resp = urlreq.urlopen(f"{SERVICE_URL}/models", timeout=5)
        models_data = json.loads(resp.read().decode())
        downloaded = set(models_data.get("models", []))
        log.info(f"📦 已下载模型: {len(downloaded)} 个\n")
    except Exception as e:
        downloaded = set()
        log.warning(f"无法获取模型列表: {e}")

    results = []
    for name, mtype, inp, params, dname in TEST_CASES:
        # 检查模型本地路径是否已下载
        ms_id = None
        entry = {"ms": name}
        # 在 MODEL_REGISTRY 中查找
        from safetensor_models import MODEL_REGISTRY, resolve_model_id
        resolved = resolve_model_id(name, mtype)
        if resolved:
            ms_id = resolved.get("ms", name)
        else:
            ms_id = name

        # 检查本地是否存在
        model_root = Path(os.environ.get("SAFETENSOR_MODEL_ROOT", "D:/safetensor_models"))
        local_dir = model_root / ms_id.replace("/", "_")
        local_dir2 = model_root / ms_id
        is_downloaded = local_dir.exists() or local_dir2.exists()

        if args.quick and not is_downloaded:
            log.info(f"⏭️  {dname:20s} → 未下载，跳过")
            results.append((name, dname, "🟡", 0, "未下载"))
            continue

        log.info(f"🔵 测试 {dname:20s} type={mtype:15s} ... ", end="", flush=True)

        ok, elapsed, result = test_model(name, mtype, inp, params)

        if ok:
            log.info(f"🟢 {elapsed:8.0f}ms | {result[:60]}")
        else:
            log.info(f"🔴 {elapsed:8.0f}ms | {result[:60]}")

        results.append((name, dname, "🟢" if ok else "🔴", elapsed, result))

    # 汇总
    log.info("\n" + "=" * 60)
    log.info(f"{'模型':20s} {'状态':4s} {'耗时(ms)':10s} {'结果'}")
    log.info("-" * 60)
    for name, dname, status, elapsed, result in results:
        elapsed_str = f"{elapsed:.0f}" if elapsed > 0 else "-"
        log.info(f"{dname:20s} {status:4s} {elapsed_str:>8s}    {result[:50]}")

if __name__ == "__main__":
    main()
