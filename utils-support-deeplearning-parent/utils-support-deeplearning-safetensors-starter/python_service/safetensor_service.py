#!/usr/bin/env python3
"""
SafeTensor Service — Python HTTP 推理服务
Java 模块通过 HTTP/TCP 调用此服务进行推理。
FastAPI + uvicorn

启动方式:
  1. 便携环境: H:/environment/onnx/python/Scripts/python.exe safetensor_service.py
  2. 系统 Python: python safetensor_service.py
"""
import os, json, sys, asyncio, logging
from pathlib import Path
from typing import Optional

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("safetensor")

# ── 模型根目录 ──
# 优先级: SAFETENSOR_MODEL_ROOT 环境变量 > 系统属性 > 默认值
_model_root_env = os.environ.get("SAFETENSOR_MODEL_ROOT", "")
if _model_root_env and Path(_model_root_env).exists():
    MODEL_ROOT = Path(_model_root_env)
else:
    # 默认: D:\safetensor_models 或 H:\environment\ocr
    _candidates = [
        Path("D:/safetensor_models"),
        Path("H:/environment/ocr"),
        Path(os.path.expanduser("~/safetensor_models")),
    ]
    for _cand in _candidates:
        if _cand.exists():
            MODEL_ROOT = _cand
            break
    else:
        MODEL_ROOT = Path("D:/safetensor_models")
MODEL_ROOT.mkdir(parents=True, exist_ok=True)

# ── 已加载的模型实例缓存 ──
_model_cache = {}

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn

app = FastAPI(title="SafeTensor Service", version="1.0.0")


class InferenceRequest(BaseModel):
    model_name: str
    model_type: str  # llm, text_embedding, image_caption, image_gen, tts, asr, etc.
    input: dict      # 输入数据，格式取决于模型类型
    params: Optional[dict] = None


class DownloadRequest(BaseModel):
    model_name: str
    source: str = "modelscope"  # modelscope / huggingface
    revision: Optional[str] = None


# ===== 模型下载 =====
def download_modelscope(model_name: str, revision: str = None):
    """从 ModelScope 下载模型"""
    from modelscope.hub.snapshot_download import snapshot_download

    target_dir = MODEL_ROOT / model_name.replace("/", "_")
    if target_dir.exists():
        log.info(f"模型已存在: {target_dir}")
        return str(target_dir)

    log.info(f"下载模型: {model_name} -> {target_dir}")
    model_dir = snapshot_download(
        model_id=model_name,
        cache_dir=str(MODEL_ROOT),
        revision=revision
    )
    return model_dir


@app.post("/download")
def download(req: DownloadRequest):
    """下载模型"""
    try:
        if req.source == "modelscope":
            path = download_modelscope(req.model_name, req.revision)
        else:
            raise HTTPException(400, f"不支持的下载源: {req.source}")
        return {"status": "ok", "path": path}
    except Exception as e:
        raise HTTPException(500, str(e))


@app.post("/infer")
def infer(req: InferenceRequest):
    """模型推理"""
    try:
        from safetensor_models import get_model_runner
        runner = get_model_runner(req.model_name, req.model_type, MODEL_ROOT)
        result = runner.run(req.input, req.params or {})
        return {"status": "ok", "output": result}
    except Exception as e:
        log.exception("推理失败")
        raise HTTPException(500, str(e))


@app.post("/train")
def train(req: InferenceRequest):
    """
    模型训练 / 微调端点。
    支持 train（批量训练）、train_step（单步训练）、eval（评估）、save/load（保存/加载）。
    """
    try:
        from safetensor_train import run_train
        result = run_train(req.model_name, req.model_type, req.input, req.params or {})
        return {"status": "ok", "output": result}
    except Exception as e:
        log.exception("训练失败")
        raise HTTPException(500, str(e))


@app.get("/health")
def health():
    return {"status": "ok", "model_root": str(MODEL_ROOT)}


@app.get("/models")
def list_models():
    models = []
    for p in MODEL_ROOT.iterdir():
        if p.is_dir():
            models.append(p.name)
    return {"models": models}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="SafeTensor 推理服务")
    parser.add_argument("--host", type=str, default=os.environ.get("SAFETENSOR_HOST", "127.0.0.1"),
                        help="监听地址 (默认 127.0.0.1)")
    parser.add_argument("--port", type=int, default=int(os.environ.get("SAFETENSOR_PORT", 8765)),
                        help="监听端口 (默认 8765)")
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port)
