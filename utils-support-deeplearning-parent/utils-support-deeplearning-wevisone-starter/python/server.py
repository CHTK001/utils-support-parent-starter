"""WeVisOne-VL 推理服务（供 utils-support-deeplearning-wevisone-starter 调用）。

服务只做三件事：
1. 启动时把官方 ``wevisone_vl`` 包与权重加载进显存/内存；
2. 把 HTTP 请求原样交给 ``WeVisOneVL.predict``（提示词由官方 ``prompts.py`` 生成，
   本文件不重写任何任务措辞，避免与官方 checkpoint 的训练分布漂移）；
3. 把模型的结构化输出（``<ref>…</ref><box>…</box>`` 与 alpha matte）整理成
   Java 侧 ``VlmResult`` 需要的形状：归一化框/点 + 8 位灰度 PNG 掩码。

协议（与 WevisoneHttpClient 一一对应）：

    POST /infer
      {
        "image": "<base64>",                 # 原始 JPEG/PNG 字节，可带 data: 前缀
        "task": "detection",                 # 官方 11 个任务键之一
        "query": "cat\nperson",              # 类别/描述/待定位文字/自由指令
        "prompt": "",                        # 非空时直接作为提示词，优先级最高
        "reference_boxes": [[x1,y1,x2,y2]],  # 归一化 0..1，仅 visual_prompt 使用
        "decode_matting": false              # 是否解码 alpha matte
      }
      → {
        "ok": true,
        "task": "detection",
        "text": "…模型原始响应…",
        "prompt": "…实际使用的提示词…",
        "objects": [{"label": "cat", "box": [x1,y1,x2,y2]}, {"label": "cat", "point": [x,y]}],
        "masks": [{"width": 640, "height": 480, "png": "<base64 8 位灰度 PNG>"}],
        "image_width": 640,
        "image_height": 480,
        "latency_s": 1.23
      }

    GET /status → {"model_loaded": true, "loading": false, "model": "...", "device": "cpu", "dtype": "bfloat16"}

坐标口径：请求与响应里的框/点**都是归一化 0..1**。官方 ``build_prompt`` 的参考框要的是
像素坐标，因此在调用 ``predict`` 前由本文件按图片实际宽高换算；模型输出的 token 坐标
（1/1000）由本文件还原成 0..1，Java 侧不再参与坐标换算。

运行（Linux/macOS，推荐 12GB 以上内存；CPU 上 2B 模型 bf16 约 5GB 权重）：

    pip install -r requirements.txt && pip install -e /path/to/WeVisOne-VL
    WEVISONE_MODEL=/models/WeVisOne-VL-2B WEVISONE_DEVICE=cpu uvicorn server:app --host 0.0.0.0 --port 8765

在 Windows 上 ``dtype=bfloat16`` 需要 torch 支持（CPU 上部分版本会静默转 float32），
内存不足时改用 ``WEVISONE_DTYPE=float32`` 只会更耗内存，正确做法是换机器或上 GPU。
"""

from __future__ import annotations

import base64
import contextlib
import io
import logging
import os
import re
import threading
import time
from typing import Any, Dict, List, Optional

import numpy as np
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from PIL import Image
from pydantic import BaseModel, Field

LOGGER = logging.getLogger("wevisone.server")

#: 模型输出的目标段：``<ref>cat</ref><box><10><20><300><400></box>``
_REF_RE = re.compile(
    r"<ref>(.*?)</ref>\s*<(box|point|polygon)>(.*?)(?:</(?:box|point|polygon)>|<ref>|$)",
    re.DOTALL | re.IGNORECASE,
)

#: 目标段里是否紧跟了掩码占位符
_SEG_RE = re.compile(r"<(?:OBJ|SEG)>", re.IGNORECASE)

#: 1/1000 归一化坐标 token
_COORD_RE = re.compile(r"<(\d{1,4})>")

#: 结束标记（正常由引擎剥掉，这里兜底）
_END_RE = re.compile(r"<\|im_end\|>|<\|endoftext\|>")


class InferRequest(BaseModel):
    """一次推理请求。字段与 Java 侧 ``VlmRequest`` 对齐。"""

    image: str = Field(..., description="图像 base64（可带 data:image/png;base64, 前缀）")
    task: str = Field("freeform", description="官方任务键：detection/referring/point/...")
    query: str = Field("", description="类别（多行分隔）、指代描述、待定位文字或自由指令")
    prompt: str = Field("", description="自定义提示词，非空时优先于 task/query")
    reference_boxes: List[List[float]] = Field(
        default_factory=list, description="归一化 0..1 参考框，仅 visual_prompt 使用"
    )
    decode_matting: bool = Field(False, description="是否解码 alpha matte（分割/抠图任务）")


class _State:
    """进程级模型状态（单例）。"""

    def __init__(self) -> None:
        self.model: Optional[Any] = None
        self.error: Optional[str] = None
        self.loading: bool = False
        self.model_id: str = os.environ.get("WEVISONE_MODEL", "tencent/WeVisOne-VL-2B")
        self.device: str = os.environ.get("WEVISONE_DEVICE", "auto")
        self.dtype: str = os.environ.get("WEVISONE_DTYPE", "bfloat16")
        self.backend: str = os.environ.get("WEVISONE_BACKEND", "transformers")
        self.lock = threading.Lock()
        self.loaded_at: Optional[float] = None

    @property
    def loaded(self) -> bool:
        return self.model is not None


STATE = _State()


def _env_int(name: str, default: int) -> int:
    """读取整数环境变量，非法值回退默认值。"""
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        LOGGER.warning("环境变量 %s=%r 不是整数，使用默认值 %s", name, raw, default)
        return default


def _load_model() -> None:
    """加载官方模型；失败只记录原因，不阻塞服务启动（便于 /status 暴露真实状态）。"""
    started = time.perf_counter()
    STATE.loading = True
    STATE.error = None
    try:
        from wevisone_vl import WeVisOneVL

        STATE.model = WeVisOneVL(
            STATE.model_id,
            device=STATE.device,
            dtype=STATE.dtype,
            min_pixels=_env_int("WEVISONE_MIN_PIXELS", 16 * 32 * 32),
            max_pixels=_env_int("WEVISONE_MAX_PIXELS", 2025 * 32 * 32),
            decoder_batch_size=_env_int("WEVISONE_DECODER_BATCH", 16),
            backend=STATE.backend,
        )
        STATE.loaded_at = time.time()
        LOGGER.info(
            "模型加载完成 model=%s device=%s dtype=%s backend=%s 耗时=%.1fs",
            STATE.model_id,
            STATE.device,
            STATE.dtype,
            STATE.backend,
            time.perf_counter() - started,
        )
    except Exception as exc:  # noqa: BLE001 - 启动期任何失败都要反映到 /status
        STATE.error = f"{type(exc).__name__}: {exc}"
        LOGGER.exception("模型加载失败")
    finally:
        STATE.loading = False


def _decode_image(raw: str) -> Image.Image:
    """把 base64（可带 data URL 前缀）解码成 RGB 图像。"""
    text = raw.strip()
    if text.startswith("data:"):
        _, _, text = text.partition(",")
    text = "".join(text.split())
    padding = (-len(text)) % 4
    if padding:
        text += "=" * padding
    try:
        payload = base64.b64decode(text, validate=True)
    except Exception as exc:  # noqa: BLE001
        raise ValueError(f"image 不是合法 base64: {exc}") from exc
    if not payload:
        raise ValueError("image 解码后为空")
    return Image.open(io.BytesIO(payload)).convert("RGB")


def _parse_objects(text: str) -> List[Dict[str, Any]]:
    """把模型响应里的 ``<ref>``/``<box>``/``<point>`` 解析成归一化目标列表。

    与官方 demo 的 ``parse_predictions_with_masks`` 同口径：box 取 4 个 token、
    point 取 2 个 token，1/1000 归一；退化框（x2<=x1 或 y2<=y1）直接丢弃，
    不产出零面积框。
    """
    clean = _END_RE.split(text or "", maxsplit=1)[0]
    objects: List[Dict[str, Any]] = []
    for label, shape, body in _REF_RE.findall(clean):
        tag = (label or "object").strip() or "object"
        kind = shape.lower()
        step = 2 if kind == "point" else 4
        for segment in (body or "").split(","):
            values = [int(v) for v in _COORD_RE.findall(segment)]
            for offset in range(0, len(values) - step + 1, step):
                chunk = values[offset : offset + step]
                if step == 2:
                    objects.append(
                        {
                            "label": tag,
                            "point": [
                                round(chunk[0] / 1000.0, 6),
                                round(chunk[1] / 1000.0, 6),
                            ],
                        }
                    )
                    continue
                box = [
                    round(chunk[0] / 1000.0, 6),
                    round(chunk[1] / 1000.0, 6),
                    round(chunk[2] / 1000.0, 6),
                    round(chunk[3] / 1000.0, 6),
                ]
                if box[2] <= box[0] or box[3] <= box[1]:
                    continue
                objects.append({"label": tag, "box": box})
    return objects


def _encode_mask(mask: Any) -> Optional[Dict[str, Any]]:
    """把 0..1 概率图（或 0..255 灰度图）编码为 8 位灰度 PNG。"""
    array = np.asarray(mask, dtype=np.float32)
    if array.ndim != 2 or array.size == 0:
        LOGGER.warning("跳过形状非法的掩码: shape=%s", array.shape)
        return None
    if float(array.max(initial=0.0)) > 1.0:
        array = array / 255.0
    pixels = np.clip(array, 0.0, 1.0)
    image = Image.fromarray(np.rint(pixels * 255.0).astype(np.uint8), mode="L")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return {
        "width": image.width,
        "height": image.height,
        "png": base64.b64encode(buffer.getvalue()).decode("ascii"),
    }


def _infer(payload: InferRequest) -> Dict[str, Any]:
    """执行一次推理，返回协议响应体（不含外层 ok 包装）。"""
    if not STATE.loaded:
        raise RuntimeError(f"模型未加载: {STATE.error or '尚未初始化'}")

    image = _decode_image(payload.image)
    width, height = image.size

    task = (payload.task or "freeform").strip().lower()
    reference_boxes: List[List[float]] = []
    for box in payload.reference_boxes or []:
        if len(box) < 4:
            continue
        reference_boxes.append(
            [
                float(box[0]) * width,
                float(box[1]) * height,
                float(box[2]) * width,
                float(box[3]) * height,
            ]
        )

    # 推理是单模型串行资源，并发请求排队而不是并行抢占显存。
    with STATE.lock:
        result = STATE.model.predict(
            image,
            task=task,
            query=payload.query or "",
            prompt=(payload.prompt or "").strip() or None,
            reference_boxes=reference_boxes or None,
            decode_matting=bool(payload.decode_matting),
        )

    text = getattr(result, "response", "") or ""
    masks = []
    for mask in getattr(result, "masks", []) or []:
        encoded = _encode_mask(mask)
        if encoded is not None:
            masks.append(encoded)

    return {
        "task": task,
        "text": text,
        "prompt": getattr(result, "prompt", "") or "",
        "objects": _parse_objects(text),
        "masks": masks,
        "image_width": width,
        "image_height": height,
        "latency_s": float(getattr(result, "latency_s", 0.0) or 0.0),
    }


@contextlib.asynccontextmanager
async def _lifespan(_app: FastAPI):
    """启动即异步加载模型。

    加载放在守护线程里，``/status`` 立刻可查（``loading=true`` / ``model_loaded=false``），
    ``/infer`` 在加载完成前返回 500 并说明原因；加载完成后同一进程内即可推理。
    """
    threading.Thread(target=_load_model, name="wevisone-loader", daemon=True).start()
    yield


app = FastAPI(
    title="WeVisOne-VL Inference Service",
    version="1.0.0",
    lifespan=_lifespan,
)


@app.get("/status")
def status() -> Dict[str, Any]:
    """服务与模型状态；``model_loaded=false`` 时附带失败原因。"""
    body: Dict[str, Any] = {
        "model_loaded": STATE.loaded,
        "loading": STATE.loading,
        "model": STATE.model_id,
        "device": STATE.device,
        "dtype": STATE.dtype,
        "backend": STATE.backend,
    }
    if STATE.error:
        body["error"] = STATE.error
    if STATE.loaded_at:
        body["loaded_at"] = STATE.loaded_at
    return body


@app.post("/infer")
def infer(payload: InferRequest) -> Any:
    """执行一次推理；参数错误回 400、模型未就绪或推理失败回 500，均带 ``ok=false``。"""
    try:
        data = _infer(payload)
    except ValueError as exc:
        LOGGER.warning("参数错误: %s", exc)
        return JSONResponse(status_code=400, content={"ok": False, "error": str(exc)})
    except Exception as exc:  # noqa: BLE001
        LOGGER.exception("推理失败")
        return JSONResponse(
            status_code=500, content={"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        )
    body = {"ok": True}
    body.update(data)
    return body
