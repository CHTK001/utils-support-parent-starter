#!/usr/bin/env python3
"""Mage 推理服务（FastAPI 封装）。

将微软 Mage 家族模型暴露为 OpenAI 风格的 HTTP 接口，供 utils-support-deeplearning-mage-starter 的
Java 客户端（provider="mage"）远程调用：

    POST /v1/images/generations   文生图（Mage-Flow）
    POST /v1/images/edits         指令图像编辑（Mage-Flow-Edit，images_b64 为参考图 Base64 列表）
    POST /v1/chat/completions     图像/视频理解（Mage-VL），content 支持 text/image_url/video_url 分片
    GET  /v1/models               可用模型清单

部署（GPU 机器）：
    # 1) 安装 Mage-Flow（官方仓库）
    git clone https://github.com/microsoft/Mage && cd Mage/mage_flow
    pip install -r requirements.txt && pip install -e . --no-deps
    # flash-attn 需单独编译：uv pip install --no-build-isolation flash-attn==2.8.3

    # 2) 安装服务依赖
    pip install fastapi "uvicorn[standard]" pillow opencv-python-headless transformers accelerate

    # 3) 启动（首次调用各模型时自动从 🤗 Hub 拉取权重并缓存）
    MAGE_API_KEY=sk-my-secret python server.py --host 0.0.0.0 --port 7861
    # 可选环境变量：
    #   MAGE_API_KEY      Bearer 鉴权密钥（缺省不鉴权）
    #   MAGE_DEVICE       推理设备（默认 cuda）
    #   MAGE_VL_MODEL     Mage-VL 权重（默认 microsoft/Mage-VL）

Java 调用示例见本模块 README.md。
"""

from __future__ import annotations

import argparse
import base64
import io
import os
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from PIL import Image

# ---------------------------------------------------------------------------
# 模型注册表：ID -> HuggingFace 权重与默认推理参数
# ---------------------------------------------------------------------------

FLOW_MODELS: Dict[str, Dict[str, Any]] = {
    "mage-flow-base": {"repo": "microsoft/Mage-Flow-Base", "steps": 30, "cfg": 5.0},
    "mage-flow": {"repo": "microsoft/Mage-Flow", "steps": 20, "cfg": 5.0},
    "mage-flow-turbo": {"repo": "microsoft/Mage-Flow-Turbo", "steps": 4, "cfg": 1.0},
    "mage-flow-edit-base": {"repo": "microsoft/Mage-Flow-Edit-Base", "steps": 30, "cfg": 5.0},
    "mage-flow-edit": {"repo": "microsoft/Mage-Flow-Edit", "steps": 30, "cfg": 5.0},
    "mage-flow-edit-turbo": {"repo": "microsoft/Mage-Flow-Edit-Turbo", "steps": 4, "cfg": 1.0},
}

VL_MODELS: Dict[str, str] = {
    "mage-vl": os.environ.get("MAGE_VL_MODEL", "microsoft/Mage-VL"),
}

DEVICE = os.environ.get("MAGE_DEVICE", "cuda")
API_KEY = os.environ.get("MAGE_API_KEY")

app = FastAPI(title="Mage Inference Server", version="1.0.0")

# 懒加载单例与互斥锁（扩散管线与 VLM 均不可安全并发复用同一实例时由锁串行化）
_flow_lock = threading.Lock()
_vl_lock = threading.Lock()
_pipelines: Dict[str, Any] = {}
_vl: Dict[str, Any] = {}


def _load_pipeline(model_id: str):
    """按需加载 Mage-Flow 管线。"""
    spec = FLOW_MODELS[model_id]
    with _flow_lock:
        if model_id not in _pipelines:
            from mage_flow import MageFlowPipeline

            print(f"[mage-server] loading {spec['repo']} ...")
            _pipelines[model_id] = MageFlowPipeline.from_pretrained(spec["repo"], device=DEVICE)
        return _pipelines[model_id]


def _load_vl():
    """按需加载 Mage-VL 多模态模型。"""
    with _vl_lock:
        if "vl" not in _vl:
            import torch
            from transformers import AutoModelForCausalLM, AutoProcessor

            repo = VL_MODELS["mage-vl"]
            print(f"[mage-server] loading {repo} ...")
            processor = AutoProcessor.from_pretrained(repo, trust_remote_code=True)
            model = AutoModelForCausalLM.from_pretrained(
                repo, trust_remote_code=True, torch_dtype="auto", device_map="auto"
            ).eval()
            _vl["vl"] = (processor, model)
        return _vl["vl"]


def _check_auth(request: Request) -> None:
    """校验 Bearer 密钥；未配置 MAGE_API_KEY 时跳过。"""
    if not API_KEY:
        return
    auth = request.headers.get("Authorization", "")
    if auth != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail="invalid api key")


def _decode_data_uri(url: str) -> Image.Image:
    """解码 data URI 或本地文件为 RGB 图片；http(s) 地址交由 requests 拉取。"""
    if url.startswith("data:"):
        payload = url.split(",", 1)[1]
        return Image.open(io.BytesIO(base64.b64decode(payload))).convert("RGB")
    if url.startswith(("http://", "https://")):
        import requests

        return Image.open(io.BytesIO(requests.get(url, timeout=60).content)).convert("RGB")
    path = Path(url.removeprefix("file://"))
    return Image.open(path).convert("RGB")


def _to_b64(image: Image.Image) -> str:
    """PIL 图片编码为 PNG Base64。"""
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def _round16(value: int, low: int = 512, high: int = 2048) -> int:
    """尺寸对齐到 16 的倍数并约束在 Mage-Flow 支持范围内。"""
    value = max(low, min(high, int(value)))
    return max(16, (value // 16) * 16)


def _sample_video_frames(video_path: str, num_frames: int) -> List[Image.Image]:
    """视频均匀抽帧（frames 后端，与官方 inference_base.py 一致）。"""
    import cv2
    import numpy as np

    capture = cv2.VideoCapture(video_path)
    frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    if frame_count <= 0:
        capture.release()
        raise HTTPException(status_code=400, detail=f"无法读取视频: {video_path}")
    indices = np.linspace(0, frame_count - 1, min(num_frames, frame_count), dtype=int)
    frames = []
    for index in indices:
        capture.set(cv2.CAP_PROP_POS_FRAMES, int(index))
        ok, frame = capture.read()
        if not ok:
            capture.release()
            raise HTTPException(status_code=400, detail=f"解码失败: 帧 {index}")
        frames.append(Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)))
    capture.release()
    return frames


# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------


@app.get("/v1/models")
async def list_models():
    """返回全部可用模型及其能力。"""
    models = []
    for model_id, spec in FLOW_MODELS.items():
        models.append({
            "id": model_id,
            "object": "model",
            "owned_by": "microsoft",
            "capabilities": ["image-editing"] if "edit" in model_id else ["text-to-image"],
            "_repo": spec["repo"],
        })
    for model_id in VL_MODELS:
        models.append({
            "id": model_id,
            "object": "model",
            "owned_by": "microsoft",
            "capabilities": ["chat", "vision", "video"],
            "_repo": VL_MODELS[model_id],
        })
    return {"object": "list", "data": models}


@app.post("/v1/images/generations")
async def images_generations(request: Request):
    """文生图：请求体含 model/prompt/negative_prompt/width/height/steps/cfg/seed。"""
    _check_auth(request)
    body = await request.json()
    model_id = body.get("model", "mage-flow-turbo")
    if model_id not in FLOW_MODELS:
        raise HTTPException(status_code=404, detail=f"未知生成模型: {model_id}")
    prompt = body.get("prompt")
    if not prompt:
        raise HTTPException(status_code=400, detail="prompt 不能为空")

    spec = FLOW_MODELS[model_id]
    width = _round16(body.get("width") or 1024)
    height = _round16(body.get("height") or 1024)
    steps = int(body.get("steps") or spec["steps"])
    cfg = float(body.get("cfg") or spec["cfg"])
    seed = body.get("seed")
    negative = body.get("negative_prompt") or ""

    pipe = _load_pipeline(model_id)
    kwargs = dict(steps=steps, cfg=cfg, heights=[height], widths=[width])
    if seed is not None:
        kwargs["seeds"] = [int(seed)]
    if negative.strip():
        kwargs["neg_prompts"] = [negative]
    try:
        images = pipe.generate([str(prompt)], **kwargs)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"生成失败: {exc}") from exc
    return {"created": 0, "data": [{"b64_json": _to_b64(images[0])}]}


@app.post("/v1/images/edits")
async def images_edits(request: Request):
    """指令编辑：请求体含 model/prompt/images_b64[]（参考图 Base64 列表）/max_size。"""
    _check_auth(request)
    body = await request.json()
    model_id = body.get("model", "mage-flow-edit-turbo")
    if model_id not in FLOW_MODELS:
        raise HTTPException(status_code=404, detail=f"未知编辑模型: {model_id}")
    prompt = body.get("prompt")
    if not prompt:
        raise HTTPException(status_code=400, detail="prompt 不能为空")
    raw_images = body.get("images_b64") or []
    if not raw_images:
        raise HTTPException(status_code=400, detail="images_b64 不能为空")

    references = []
    for encoded in raw_images:
        try:
            references.append(Image.open(io.BytesIO(base64.b64decode(encoded))).convert("RGB"))
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=400, detail="参考图 Base64 解码失败") from exc

    spec = FLOW_MODELS[model_id]
    steps = int(body.get("steps") or spec["steps"])
    cfg = float(body.get("cfg") or spec["cfg"])
    max_size = _round16(body.get("max_size") or 1024)

    pipe = _load_pipeline(model_id)
    try:
        images = pipe.edit([str(prompt)], [references], steps=steps, cfg=cfg, max_size=max_size)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"编辑失败: {exc}") from exc
    return {"created": 0, "data": [{"b64_json": _to_b64(images[0])}]}


@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    """多模态对话：OpenAI 风格消息；image_url/video_url 分片支持 data URI、http(s) 与本地路径。"""
    _check_auth(request)
    body = await request.json()
    model_id = body.get("model", "mage-vl")
    if model_id not in VL_MODELS:
        raise HTTPException(status_code=404, detail=f"未知理解模型: {model_id}")
    messages = body.get("messages") or []
    if not messages:
        raise HTTPException(status_code=400, detail="messages 不能为空")
    num_frames = int(body.get("num_frames") or 32)
    max_new_tokens = int(body.get("max_tokens") or 512)

    # 组装 Mage-VL chat template 消息：媒体仅挂载到最后一条用户消息，
    # 其余轮次退化为纯文本以保持模板兼容。
    processor_messages: List[Dict[str, Any]] = []
    images: List[Image.Image] = []
    videos: List[Any] = []

    last_user_index = max(i for i, m in enumerate(messages) if m.get("role") == "user")
    for index, message in enumerate(messages):
        role = message.get("role", "user")
        content = message.get("content")
        is_last_user = role == "user" and index == last_user_index
        if isinstance(content, str):
            if content.strip():
                processor_messages.append({"role": role, "content": content})
            continue
        if not isinstance(content, list):
            continue
        if is_last_user:
            parts: List[Dict[str, Any]] = []
            for part in content:
                part_type = part.get("type")
                if part_type == "text":
                    parts.append({"type": "text", "text": part.get("text", "")})
                elif part_type == "image_url":
                    url = (part.get("image_url") or {}).get("url", "")
                    images.append(_decode_data_uri(url))
                    parts.append({"type": "image"})
                elif part_type == "video_url":
                    url = (part.get("video_url") or {}).get("url", "")
                    videos.append(_resolve_video_source(url))
                    parts.append({"type": "video"})
                elif part_type in ("image", "video"):
                    parts.append({"type": part_type})
            processor_messages.append({"role": "user", "content": parts or ""})
        else:
            texts = [p.get("text", "") for p in content if p.get("type") == "text"]
            flat = " ".join(t for t in texts if t).strip() or "(media)"
            processor_messages.append({"role": role, "content": flat})

    processor, model = _load_vl()
    import torch

    text = processor.apply_chat_template(
        processor_messages, tokenize=False, add_generation_prompt=True
    )
    call_kwargs: Dict[str, Any] = {"text": [text], "return_tensors": "pt", "padding": True}
    if images:
        call_kwargs["images"] = images
    video_lists: List[Any] = []
    for source in videos:
        if isinstance(source, list):  # 已经是帧列表
            video_lists.append(source)
        else:  # 本地/远程视频文件路径 -> 抽帧
            video_lists.append(_sample_video_frames(str(source), num_frames))
    if video_lists:
        call_kwargs["videos"] = video_lists

    inputs = processor(**call_kwargs)
    inputs = {k: (v.to(model.device) if hasattr(v, "to") else v) for k, v in inputs.items()}
    if "pixel_values" in inputs:
        inputs["pixel_values"] = inputs["pixel_values"].to(model.dtype)
    try:
        with torch.inference_mode():
            output = model.generate(**inputs, max_new_tokens=max_new_tokens, do_sample=False)
        answer = processor.tokenizer.decode(
            output[0, inputs["input_ids"].shape[1]:], skip_special_tokens=True
        ).strip()
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=f"理解失败: {exc}") from exc

    return {
        "id": f"chatcmpl-mage-{os.urandom(6).hex()}",
        "object": "chat.completion",
        "model": model_id,
        "choices": [{
            "index": 0,
            "finish_reason": "stop",
            "message": {"role": "assistant", "content": answer},
        }],
        "usage": {
            "prompt_tokens": int(inputs["input_ids"].shape[1]),
            "completion_tokens": max(0, len(answer.split())),
            "total_tokens": int(inputs["input_ids"].shape[1]) + max(0, len(answer.split())),
        },
    }


def _resolve_video_source(url: str) -> Any:
    """解析 video_url 分片来源：data URI / http(s) 落盘临时文件，本地路径直接使用。"""
    if url.startswith("data:video"):
        import tempfile

        payload = base64.b64decode(url.split(",", 1)[1])
        temp = tempfile.NamedTemporaryFile(prefix="mage-", suffix=".mp4", delete=False)
        temp.write(payload)
        temp.close()
        return temp.name
    if url.startswith("file://"):
        return Path(url[len("file://"):])
    if url.startswith(("http://", "https://")):
        import tempfile

        import requests

        response = requests.get(url, timeout=120)
        response.raise_for_status()
        temp = tempfile.NamedTemporaryFile(prefix="mage-", suffix=".mp4", delete=False)
        temp.write(response.content)
        temp.close()
        return temp.name
    return Path(url)


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException):
    """统一错误响应。"""
    return JSONResponse(status_code=exc.status_code, content={"error": {"message": exc.detail}})


def main() -> None:
    """命令行入口。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7861)
    parser.add_argument("--preload", default="", help="启动即预载的模型 ID，逗号分隔（如 mage-flow-turbo,mage-vl）")
    args = parser.parse_args()

    for preload in filter(None, (item.strip() for item in args.preload.split(","))):
        if preload in FLOW_MODELS:
            _load_pipeline(preload)
        elif preload == "mage-vl":
            _load_vl()

    import uvicorn

    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
