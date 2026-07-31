#!/usr/bin/env python3
"""
OvisOCR2 端到端测试脚本。
验证：
  1. 模型自动下载（本地不存在时从 ModelScope 拉取）
  2. 文档图片 → Markdown 推理
  3. 多轮调用（Runner 缓存）

用法:
  # 先启动服务（另一个终端）:
  python safetensor_service.py

  # 然后运行测试:
  python test_ovisocr2.py

  # 或跳过下载测试（仅推理）:
  python test_ovisocr2.py --infer-only
"""
import argparse, base64, io, json, logging, os, sys, time
from pathlib import Path
from urllib import request as urlreq
from urllib.error import URLError

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("test_ovisocr2")

SERVICE_URL = "http://127.0.0.1:8765"

# ── 测试用文档图片（生成一个简单的白底黑字 PNG）──
def create_test_document_image(size=(800, 600)):
    """生成测试用文档图片：白底 + 标题 + 表格 + 公式"""
    try:
        from PIL import Image, ImageDraw, ImageFont
        img = Image.new("RGB", size, "white")
        draw = ImageDraw.Draw(img)

        # 尝试加载字体
        try:
            title_font = ImageFont.truetype("arial.ttf", 28)
            body_font = ImageFont.truetype("arial.ttf", 16)
        except (OSError, IOError):
            title_font = ImageFont.load_default()
            body_font = ImageFont.load_default()

        # 标题
        draw.text((50, 30), "Sample Document Title", fill="black", font=title_font)

        # 正文段落
        text = (
            "This is a sample paragraph that demonstrates document parsing. "
            "It contains multiple lines of text that should be extracted "
            "and converted to Markdown format by OvisOCR2."
        )
        draw.text((50, 90), text, fill="black", font=body_font)

        # 表格
        draw.text((50, 170), "Table 1: Sample Data", fill="darkblue", font=body_font)
        for i, header in enumerate(["Name", "Age", "City"]):
            x = 60 + i * 150
            draw.text((x, 200), header, fill="black", font=body_font)
            draw.rectangle([x-2, 198, x+140, 222], outline="gray")
        for row in range(3):
            for col, val in enumerate([f"Person{row+1}", f"{20+row}", "City A"]):
                x = 60 + col * 150
                y = 228 + row * 26
                draw.text((x, y), val, fill="dimgray", font=body_font)

        # 公式示意
        draw.text((50, 330), "Formula:", fill="black", font=body_font)
        draw.text((50, 360), "E = mc^2", fill="darkred", font=body_font)

        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode()
    except ImportError:
        # 没有 Pillow → 用纯字节构造一个极简 PNG（白底黑字 1x1）
        log.warning("Pillow 未安装，使用内置极小测试图（仅验证流程，无法验证输出质量）")
        return _minimal_png()


def _minimal_png() -> str:
    """生成一个最小的有效 PNG（白底）"""
    import struct, zlib

    def _chunk(ctype, data):
        c = ctype + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = _chunk(b"IHDR", struct.pack(">IIBBBBB", 10, 10, 8, 2, 0, 0, 0))
    raw = b""
    for _ in range(10):
        raw += b"\x00" + b"\xff\xff\xff" * 10
    idat = _chunk(b"IDAT", zlib.compress(raw))
    iend = _chunk(b"IEND", b"")
    return base64.b64encode(sig + ihdr + idat + iend).decode()


# ── 测试函数 ──
def call_health():
    """健康检查"""
    resp = urlreq.urlopen(f"{SERVICE_URL}/health", timeout=10)
    return json.loads(resp.read().decode())


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


def call_models():
    """列出已下载模型"""
    resp = urlreq.urlopen(f"{SERVICE_URL}/models", timeout=10)
    return json.loads(resp.read().decode())


def call_download(model_name: str, source: str = "modelscope"):
    """手动触发下载（不推理）"""
    payload = {"model_name": model_name, "source": source}
    data = json.dumps(payload).encode("utf-8")
    req = urlreq.Request(
        f"{SERVICE_URL}/download",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    resp = urlreq.urlopen(req, timeout=3600)
    return json.loads(resp.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description="OvisOCR2 测试脚本")
    parser.add_argument("--infer-only", action="store_true", help="跳过下载测试，仅推理")
    args = parser.parse_args()

    # ── Step 1: 检查服务 ──
    log.info("=" * 60)
    log.info("OvisOCR2 端到端测试")
    log.info("=" * 60)

    log.info("\n[Step 1] 健康检查...")
    try:
        health = call_health()
        log.info(f"  ✅ 服务运行中: {health}")
    except Exception as e:
        log.error(f"  ❌ 服务未运行: {e}")
        log.error("  请先运行: python safetensor_service.py")
        sys.exit(1)

    # ── Step 2: 检查依赖 ──
    log.info("\n[Step 2] 检查 Python 依赖...")
    deps_ok = True
    for dep in ("torch", "PIL", "modelscope", "transformers"):
        try:
            __import__(dep)
            log.info(f"  ✅ {dep}")
        except ImportError:
            log.warning(f"  ❌ {dep} 未安装")
            deps_ok = False

    if not deps_ok:
        log.warning("  部分依赖缺失，测试可能失败")

    # ── Step 3: 检查 ovis 包 ──
    log.info("\n[Step 3] 检查 ovis 推理包...")
    try:
        import ovis
        log.info("  ✅ ovis 包已安装，将使用 Ovis 原生推理")
        ovis_available = True
    except ImportError:
        log.info("  ⚠️  ovis 包未安装，将降级到 ModelScope pipeline")
        ovis_available = False

    # ── Step 4: 确认模型尚未下载（为自动下载测试做准备）──
    if not args.infer_only:
        log.info("\n[Step 4] 检查本地是否已有 OvisOCR2...")
        model_root = Path(os.environ.get("SAFETENSOR_MODEL_ROOT", "D:/safetensor_models"))
        ovis_dirs = [
            model_root / "ATH-MaaS/OvisOCR2",
            model_root / "ATH-MaaS_OvisOCR2",
        ]
        already_downloaded = any(d.exists() for d in ovis_dirs)
        if already_downloaded:
            log.info("  ⚠️  OvisOCR2 已存在本地，自动下载测试步骤将跳过（模型已存在）")
        else:
            log.info("  ✅ 本地未找到 OvisOCR2，首次推理将触发自动下载！")
            log.info("  📥 自动下载流程: /infer → resolve_model_id → _auto_download → snapshot_download")
            log.info("  ⏳ 模型约 1.6GB，下载可能需要 5-30 分钟")
    else:
        log.info("\n[Step 4] 跳过下载检查（--infer-only）")

    # ── Step 5: 推理测试（首次触发自动下载）──
    log.info("\n[Step 5] 文档 → Markdown 推理（首次触发自动下载）")
    log.info("  生成测试文档图片...")

    image_b64 = create_test_document_image()
    log.info(f"  测试图片: {len(image_b64)} bytes (base64)")

    # 第1次推理（含模型加载 + 自动下载）
    log.info("\n  --- 第 1 次推理（含模型加载） ---")
    t0 = time.time()
    try:
        result = call_infer(
            "ovisocr2",
            "document_ocr",
            {"image": image_b64},
            {"max_new_tokens": 512}
        )
        elapsed = time.time() - t0
        status = result.get("status")
        output = result.get("output", "")

        if status == "ok":
            log.info(f"  ✅ 推理成功! 耗时: {elapsed:.1f}s")
            log.info(f"\n  ==== Markdown 输出 ====")
            log.info(f"  {output[:500]}")
            log.info(f"  {'=' * 30}")
        else:
            log.error(f"  ❌ 推理失败: {result}")
    except Exception as e:
        elapsed = time.time() - t0
        log.error(f"  ❌ 第1次推理失败 ({elapsed:.0f}s): {e}")
        log.info("  ⚠️  这可能是模型首次下载 + 加载，需要较长时间")
        log.info("  ⚠️  如果超时，请增加服务端 timeout 或使用 GPU")

    # 第2次推理（Runner 缓存，应为热调用）
    log.info("\n  --- 第 2 次推理（Runner 缓存） ---")
    t0 = time.time()
    try:
        result = call_infer(
            "ovisocr2",
            "document_ocr",
            {"image": image_b64},
            {"max_new_tokens": 512}
        )
        elapsed = time.time() - t0
        status = result.get("status")
        output = result.get("output", "")

        if status == "ok":
            log.info(f"  ✅ 推理成功! 耗时: {elapsed:.1f}s")
            log.info(f"\n  ==== Markdown 输出 ====")
            log.info(f"  {output[:300]}")
            log.info(f"  {'=' * 30}")
        else:
            log.error(f"  ❌ 推理失败: {result}")
    except Exception as e:
        elapsed = time.time() - t0
        log.info(f"  ⚠️  第2次推理耗时: {elapsed:.0f}s (如仍很长说明模型未完全缓存)")

    # ── 列出已下载模型 ──
    log.info("\n[Step 6] 查看已下载模型列表...")
    try:
        models = call_models()
        model_list = models.get("models", [])
        ovis_dirs = [m for m in model_list if "ovis" in m.lower() or "ath" in m.lower()]
        if ovis_dirs:
            log.info(f"  ✅ OvisOCR2 已下载: {ovis_dirs}")
        else:
            log.info(f"  所有已下载模型: {len(model_list)} 个")
            log.info("  ⚠️  OvisOCR2 可能在子目录中")
    except Exception as e:
        log.warning(f"  获取模型列表失败: {e}")

    # ── 汇总 ──
    log.info("\n" + "=" * 60)
    log.info("测试完成")
    log.info("=" * 60)


if __name__ == "__main__":
    main()
