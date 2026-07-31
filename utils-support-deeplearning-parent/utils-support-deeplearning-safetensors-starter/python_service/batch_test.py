#!/usr/bin/env python3
"""
智能批量测试 — 下载并测试 🟡 状态的 safetensor Python 模型。
自动跳过不存在的模型、处理部分下载。
"""
import json, logging, os, sys, time, traceback
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("batch")

MODEL_ROOT = Path("D:/safetensor_models")
os.environ["MODELSCOPE_CACHE"] = str(MODEL_ROOT)

# ── 要测试的模型 ──
TASKS = [
    # LLM (transformers, 已安装)
    ("qwen2.5-3b",    "llm", "Qwen/Qwen2.5-3B-Instruct",
     {"text": "你好，请用一句话介绍自己"}, {"max_new_tokens": 64}, "Qwen2.5-3B"),

    # TTS (modelscope pipeline)
    ("kokoro",       "tts", "", True),  # local

    # 文生图 (需 diffusers)
    ("sd1.5",         "image_gen", "AI-ModelScope/stable-diffusion-v1-5",
     {"prompt": "a cute cat"}, {"steps": 20}, "SD1.5"),

    ("sdxl-turbo",    "image_gen", "AI-ModelScope/sdxl-turbo",
     {"prompt": "a cute cat"}, {"steps": 4}, "SDXL-Turbo"),
]

def remove_lock_file(ms_id: str):
    """删除可能存在的锁文件"""
    lock_dir = MODEL_ROOT / ".lock"
    if lock_dir.exists():
        for f in lock_dir.iterdir():
            if ms_id.replace("/", "_") in f.name or ms_id.replace("/", "___") in f.name:
                try:
                    f.unlink()
                    log.info(f"  删除锁: {f.name}")
                except:
                    pass


def auto_download(ms_id: str) -> str:
    """下载模型，返回本地路径 (支持断点续传)"""
    # 检查各种可能的本地路径形式
    candidates = [
        MODEL_ROOT / ms_id,
        MODEL_ROOT / ms_id.replace("/", "_"),
    ]
    for p in candidates:
        if p.exists():
            safetensors = list(p.glob("*.safetensors")) + list(p.glob("*.bin"))
            if safetensors:
                log.info(f"  本地已存在 ({len(safetensors)} 个权重文件): {p}")
                return str(p)
            else:
                log.info(f"  目录存在但无权重文件: {p}，重新下载")

    # 清理锁文件
    remove_lock_file(ms_id)

    log.info(f"  开始下载: {ms_id} ...")
    t0 = time.time()
    from modelscope.hub.snapshot_download import snapshot_download
    result = snapshot_download(ms_id, cache_dir=str(MODEL_ROOT))
    elapsed = time.time() - t0
    log.info(f"  下载完成: {elapsed:.0f}s")
    return result


def test_model(name, mtype, inp, params) -> tuple:
    """测试模型推理"""
    from safetensor_models import get_model_runner
    runner = get_model_runner(name, mtype, MODEL_ROOT)
    t0 = time.time()
    try:
        result = runner.run(inp, params)
        elapsed = (time.time() - t0) * 1000
        return True, elapsed, str(result)[:80]
    except Exception as e:
        elapsed = (time.time() - t0) * 1000
        return False, elapsed, str(e)[:150]


def check_deps(mtype) -> bool:
    """检查模型类型所需的依赖是否安装"""
    deps = {
        "llm": ("transformers",),
        "tts": ("modelscope",),
        "image_gen": ("diffusers",),
        "tryon": ("diffusers",),
        "asr": ("funasr",),
        "text_embedding": ("sentence_transformers",),
        "face_swap": ("insightface",),
    }
    needed = deps.get(mtype, ())
    for d in needed:
        try:
            __import__(d)
        except ImportError:
            return False
    return True


def main():
    results = []
    tested = 0
    skipped = 0
    failed_dl = 0

    for i, (name, mtype, ms_id, inp, params, dname) in enumerate(TASKS, 1):
        log.info(f"\n[{i}/{len(TASKS)}] {dname} ({name}, {mtype})")

        # 检查依赖
        if not check_deps(mtype):
            log.warning(f"  ⏭️ 依赖缺失，跳过")
            results.append((dname, "🟡", 0, "依赖缺失"))
            skipped += 1
            continue

        # 下载
        try:
            model_dir = auto_download(ms_id)
        except Exception as e:
            log.error(f"  ❌ 下载失败: {e}")
            results.append((dname, "🔴", 0, f"下载失败: {str(e)[:80]}"))
            failed_dl += 1
            continue

        # 测试 (3 次预热)
        log.info(f"  推理测试 (3 次运行)...")
        all_ok = True
        times = []
        final_result = ""
        for run_idx in range(3):
            ok, elapsed, result = test_model(name, mtype, inp, params)
            times.append(elapsed)
            if ok:
                log.info(f"    第 {run_idx+1} 次: {elapsed:.0f}ms")
            else:
                log.info(f"    第 {run_idx+1} 次: ❌ {elapsed:.0f}ms | {result[:60]}")
                all_ok = False
                final_result = result
                break
            final_result = result

        if all_ok:
            avg = sum(times) / len(times)
            log.info(f"  ✅ 平均: {avg:.0f}ms")
            results.append((dname, "🟢", avg, final_result))
            tested += 1
        else:
            results.append((dname, "🔴", times[0], final_result))

    # ── 汇总 ──
    log.info("\n" + "=" * 70)
    log.info(f"测试完成: 🟢 {sum(1 for _,s,_,_ in results if s=='🟢')} | 🔴 {sum(1 for _,s,_,_ in results if s=='🔴')} | 🟡 {sum(1 for _,s,_,_ in results if s=='🟡')}")
    log.info("-" * 70)
    log.info(f"{'模型':25s} {'状态':4s} {'耗时(ms)':12s} {'结果'}")
    log.info("-" * 70)
    for dname, status, elapsed, result in results:
        elapsed_str = f"{elapsed:.0f}" if elapsed > 0 else "-"
        log.info(f"{dname:25s} {status:4s} {elapsed_str:>10s}    {result[:50]}")

    # 保存 JSON
    output = {"results": [
        {"name": dname, "status": status, "elapsed_ms": elapsed, "summary": result[:80]}
        for dname, status, elapsed, result in results
    ]}
    out_path = MODEL_ROOT / ".batch_test_results.json"
    with open(out_path, "w") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)
    log.info(f"\n结果已保存: {out_path}")


if __name__ == "__main__":
    main()
