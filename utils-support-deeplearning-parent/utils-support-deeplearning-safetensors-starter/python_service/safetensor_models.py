"""
SafeTensor 模型运行器 — 多模型备份 + ModelScope 自动下载
各类型提供 2+ 个备用模型，按优先级选择，失败自动回退。
"""
import logging, os, io, base64, tempfile
import numpy as np
from typing import Optional

# 禁用系统代理 & SSL 验证（Windows 代理 MITM 会拦截 HTTPS）
os.environ["HTTP_PROXY"] = ""
os.environ["HTTPS_PROXY"] = ""
os.environ["http_proxy"] = ""
os.environ["https_proxy"] = ""
os.environ["NO_PROXY"] = "*"

import ssl
ssl._create_default_https_context = ssl._create_unverified_context

import requests
import soundfile as sf
import scipy.signal
from pathlib import Path
from typing import Any

# HF 镜像（国内用户，必须在 import huggingface_hub 前设置）
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

log = logging.getLogger("safetensor.runner")
MODELSCOPE_CACHE = os.environ.get("MODELSCOPE_CACHE", "D:/safetensor_models")
_model_runners = {}

# ===== 模型注册表：每种类型提供多个备用模型 =====
MODEL_REGISTRY = {
    # ── LLM 文本生成 ──
    "llm": [
        {"id": "facade-text",    "hf": "jingyaogong/MiniMind2-small", "source": "huggingface"},
        {"id": "qwen2.5-0.5b",  "ms": "Qwen/Qwen2.5-0.5B-Instruct"},
        {"id": "qwen2.5-1.5b",  "ms": "Qwen/Qwen2.5-1.5B-Instruct"},
        {"id": "qwen2.5-3b",    "ms": "Qwen/Qwen2.5-3B-Instruct"},
        {"id": "qwen2.5-7b",    "ms": "Qwen/Qwen2.5-7B-Instruct"},
        {"id": "qwen2.5-14b",   "ms": "Qwen/Qwen2.5-14B-Instruct"},
        {"id": "qwen2.5-coder-7b", "ms": "Qwen/Qwen2.5-Coder-7B-Instruct"},
        {"id": "qwen2-0.5b",    "ms": "Qwen/Qwen2-0.5B-Instruct"},
        {"id": "qwen2-1.5b",    "ms": "Qwen/Qwen2-1.5B-Instruct"},
        {"id": "qwen2-7b",      "ms": "Qwen/Qwen2-7B-Instruct"},
        {"id": "llama3.2-1b",   "ms": "LLM-Research/Llama-3.2-1B-Instruct"},
        {"id": "llama3.2-3b",   "ms": "LLM-Research/Llama-3.2-3B-Instruct"},
        {"id": "minimind2-104m","hf": "jingyaogong/MiniMind2", "source": "huggingface"},
        {"id": "minimind2-small","hf": "jingyaogong/MiniMind2-small", "source": "huggingface"},
    ],
    # ── 图文理解 / VLM ──
    "vlm": [
        {"id": "facade-vlm",    "local": True},
        {"id": "gpt-4o",        "openai": True},
        {"id": "minimind-3v",    "ms": "gongjy/minimind-3v", "source": "modelscope",
         "vision_ms": "jingyaogong/siglip2-base-p32-256-ve", "vision_source": "huggingface"},
    ],
    # ── 人脸替换 ──
    "face_swap": [
        {"id": "inswapper_128", "hf": "Gourieff/ReActor", "source": "huggingface"},
        {"id": "reswapper_256", "hf": "somanchiu/ReSwapper", "source": "huggingface"},
    ],
    # ── 换装 (Virtual Try-On) ──
    "tryon": [
        {"id": "sal-vton",   "ms": "iic/cv_SAL-VTON_virtual-try-on", "pipeline": True},
        {"id": "nano_banana",   "ms": "qiyuanai/Nano-Banana_Trending_Disassemble_Clothes_One-Click-Generation", "rev": "20251206105653", "lora": True, "base_ms": "Qwen/Qwen-Image", "lora_shard": "20.safetensors"},
    ],
    # ── 文生图 (Text-to-Image) ──
    "image_gen": [
        {"id": "facade-image",   "hf": "segmind/tiny-sd", "source": "huggingface"},
        {"id": "facade-image2image", "ms": "AI-ModelScope/stable-diffusion-v1-5"},
        {"id": "tiny-sd",       "hf": "segmind/tiny-sd", "source": "huggingface"},
        {"id": "sd1.5",         "ms": "AI-ModelScope/stable-diffusion-v1-5"},
        {"id": "sdxl-turbo",    "ms": "AI-ModelScope/sdxl-turbo"},
        {"id": "sd3.5",         "ms": "AI-ModelScope/Stable-Diffusion-3.5"},
        {"id": "flux1-schn",    "ms": "AI-ModelScope/FLUX.1-schnell"},
        {"id": "flux1-dev",     "ms": "AI-ModelScope/FLUX.1-dev"},
        {"id": "kolors",        "ms": "AI-ModelScope/Kolors"},
        {"id": "florence2-base","ms": "iic/Florence-2-base"},
    ],
    # ── 文生音频 (TTS) ──
    "tts": [
        {"id": "facade-tts",    "local": True},
        {"id": "facade-audio",  "local": True},
        {"id": "kokoro",        "local": True},  # 本地 kokoro-onnx
    ],
    # ── 音频转文字 (ASR) ──
    "asr": [
        {"id": "whisper-large-v3","ms": "iic/Whisper-large-v3"},
        {"id": "paraformer",    "ms": "iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch"},
        {"id": "sensevoice",     "ms": "iic/SenseVoiceSmall"},
        {"id": "minimind-3o",   "hf": "jingyaogong/minimind-3o", "source": "huggingface", "runner": "omni"},
    ],
    # ── 文本嵌入 ──
    "text_embedding": [
        {"id": "qwen3-embedding-8b", "ms": "Qwen/Qwen3-Embedding-8B"},
        {"id": "text_embed",        "ms": "iic/nlp_corom_sentence-embedding_chinese-base"},
        # ── GTE 向量嵌入 ──
        {"id": "gte-large",        "ms": "iic/nlp_gte_sentence-embedding_chinese-large"},
        {"id": "gte-base",         "ms": "iic/nlp_gte_sentence-embedding_chinese-base"},
    ],
    # ── 图像增强/修复 ──
    "image_enhance": [
        {"id": "gpen-portrait",         "ms": "iic/cv_gpen_image-portrait-enhancement"},
        {"id": "gpen-portrait-hires",   "ms": "iic/cv_gpen_image-portrait-enhancement-hires"},
        {"id": "face-fusion",           "ms": "iic/cv_unet-image-face-fusion_damo"},
        {"id": "cartoon-handdrawn",     "ms": "iic/cv_unet_person-image-cartoon-handdrawn_compound-models"},
        # ── 图像上色 ──
        {"id": "ddcolor",               "ms": "iic/cv_ddcolor_image-colorization"},
        # ── 皮肤修饰 ──
        {"id": "skin-retouching",       "ms": "iic/cv_unet_skin-retouching"},
        # ── 去模糊 ──
        {"id": "nafnet-deblur",         "ms": "iic/cv_nafnet_image-deblur_gopro"},
        # ── 去噪 ──
        {"id": "nafnet-denoise",        "ms": "iic/cv_nafnet_image-denoise_sidd"},
        # ── 超分辨率 ──
        {"id": "rrdb-super-res",        "ms": "iic/cv_rrdb_image-super-resolution"},
        # ── 图像修复/填充 ──
        {"id": "lama-inpainting",       "ms": "iic/cv_fft_inpainting_lama"},
        {"id": "sd-inpainting",         "ms": "iic/cv_stable-diffusion-v2_image-inpainting_base"},
        # ── 图像调色 ──
        {"id": "csrnet-color",          "ms": "iic/cv_csrnet_image-color-enhance-models"},
        {"id": "adaint-color",          "ms": "iic/cv_adaint_image-color-enhance-models"},
        {"id": "deeplpf-color",         "ms": "iic/cv_deeplpfnet_image-color-enhance-models"},
        # ── 超分辨率（移动端）─
        {"id": "ecbsr-super-res",       "ms": "iic/cv_ecbsr_image-super-resolution_mobile"},
        # ── 去色带 ──
        {"id": "rrdb-debanding",        "ms": "iic/cv_rrdb_image-debanding"},
    ],
    # ── 图像分类/识别 ──
    "image_recognition": [
        # ── ResNeSt ──
        {"id": "resnest-general",   "ms": "iic/cv_resnest101_general_recognition"},
        {"id": "resnest-animal",    "ms": "iic/cv_resnest101_animal_recognition"},
        # ── ViT ──
        {"id": "vit-dailylife",     "ms": "iic/cv_vit-base_image-classification_Dailylife-labels"},
        {"id": "vit-imagenet",      "ms": "iic/cv_vit-base_image-classification_ImageNet-labels"},
        # ── BEiT v2 ──
        {"id": "beitv2-base",       "ms": "iic/cv_beitv2-base_image-classification_patch16_224_pt1k_ft22k_in1k"},
        {"id": "beitv2-large",      "ms": "iic/cv_beitv2-large_image-classification_patch16_224_pt1k_ft22k_in1k"},
        # ── BNexT / NextViT / TinyNAS / ConvNeXt ──
        {"id": "bnext-small",       "ms": "iic/cv_bnext-small_image-classification_ImageNet-labels"},
        {"id": "nextvit-small",     "ms": "iic/cv_nextvit-small_image-classification_Dailylife-labels"},
        {"id": "tinynas",           "ms": "iic/cv_tinynas_classification"},
        {"id": "resnet50-cc",       "ms": "iic/cv_resnet50_image-classification_cc"},
        {"id": "convnext-garbage",  "ms": "iic/cv_convnext-base_image-classification_garbage"},
        # ── 人脸质量/活体 ──
        {"id": "face-quality-fqa",  "ms": "iic/cv_manual_face-quality-assessment_fqa"},
        {"id": "face-liveness",     "ms": "iic/cv_manual_face-liveness_flrgb"},
        {"id": "face-liveness-ir",  "ms": "iic/cv_manual_face-liveness_flir"},
        {"id": "face-liveness-xc",  "ms": "iic/cv_manual_face-liveness_flxc"},
        # ── 表情识别 ──
        {"id": "facial-expression", "ms": "iic/cv_vgg19_facial-expression-recognition_fer"},
        # ── 人脸属性 ──
        {"id": "face-attribute",    "ms": "iic/cv_resnet34_face-attribute-recognition_fairface"},
        # ── 场景/行人属性 ──
        {"id": "live-category",     "ms": "iic/cv_resnet50_live-category"},
        {"id": "pedestrian-attr",   "ms": "iic/cv_resnet50_pedestrian-attribute-recognition_image"},
        # ── OFA 多模态分类 ──
        {"id": "ofa-classification",    "ms": "iic/ofa_image-classification_imagenet_large_en"},
    ],
    # ── 抠图/分割 ──
    "matting": [
        {"id": "unet-matting",          "ms": "iic/cv_unet_image-matting"},
        {"id": "unet-universal-matting","ms": "iic/cv_unet_universal-matting"},
    ],
    # ── 检测 ──
    "detection": [
        {"id": "facade-detect",         "local": True},
        # ── 通用目标检测 ──
        {"id": "yolox-nano-coco",       "ms": "iic/cv_cspnet_image-object-detection_yolox_nano_coco"},
        {"id": "yolox",                 "ms": "iic/cv_cspnet_image-object-detection_yolox"},
        {"id": "yolox-auto",            "ms": "iic/cv_yolox_image-object-detection-auto"},
        {"id": "damoyolo",              "ms": "iic/cv_tinynas_object-detection_damoyolo"},
        {"id": "damoyolo-m",            "ms": "iic/cv_tinynas_object-detection_damoyolo-m"},
        {"id": "damoyolo-t",            "ms": "iic/cv_tinynas_object-detection_damoyolo-t"},
        {"id": "tinynas-detection",     "ms": "iic/cv_tinynas_detection"},
        {"id": "resnet50-maskscoring",  "ms": "iic/cv_resnet50_object-detection_maskscoring"},
        {"id": "dino-swinl",            "ms": "iic/cv_swinl_image-object-detection_dino"},
        {"id": "vit-det-coco",          "ms": "iic/cv_vit_object-detection_coco"},
        {"id": "vidt-item",             "ms": "iic/ViDT-item-detection"},
        {"id": "vidt-logo",             "ms": "iic/ViDT-logo-detection"},
        {"id": "vidt-text",             "ms": "iic/ViDT-text-detection"},
        # ── 人体/头部/手部检测 ──
        {"id": "human-detection",       "ms": "iic/cv_resnet18_human-detection"},
        {"id": "human-detection-damoyolo", "ms": "iic/cv_tinynas_human-detection_damoyolo"},
        {"id": "head-detection",        "ms": "iic/cv_tinynas_head-detection_damoyolo"},
        {"id": "hand-detection",        "ms": "iic/cv_yolox-pai_hand-detection"},
        # ── 安全/防护检测 ──
        {"id": "facemask",              "ms": "iic/cv_tinynas_object-detection_damoyolo_facemask"},
        {"id": "safety-helmet",         "ms": "iic/cv_tinynas_object-detection_damoyolo_safety-helmet"},
        # ── 其他物品检测 ──
        {"id": "phone-detection",       "ms": "iic/cv_tinynas_object-detection_damoyolo_phone"},
        {"id": "traffic-sign",          "ms": "iic/cv_tinynas_object-detection_damoyolo_traffic_sign"},
        {"id": "cigarette-detection",   "ms": "iic/cv_tinynas_object-detection_damoyolo_cigarette"},
        {"id": "smokefire-detection",   "ms": "iic/cv_tinynas_object-detection_damoyolo_smokefire"},
        {"id": "uav-detection",         "ms": "iic/cv_tinynas_uav-detection_damoyolo"},
        # ── 证件/车牌 ──
        {"id": "license-plate",         "ms": "iic/cv_resnet18_license-plate-detection_damo"},
        {"id": "card-correction",       "ms": "iic/cv_resnet18_card_correction"},
        {"id": "card-detection",        "ms": "iic/cv_resnet_carddetection_scrfd34gkps"},
        # ── UAV / 烟雾/手机/香烟/交通标志 ──
        {"id": "uav-detection",         "ms": "iic/cv_tinynas_uav-detection_damoyolo"},
        {"id": "smokefire-detection",   "ms": "iic/cv_tinynas_object-detection_damoyolo_smokefire"},
        {"id": "phone-detection",       "ms": "iic/cv_tinynas_object-detection_damoyolo_phone"},
        {"id": "traffic-sign",          "ms": "iic/cv_tinynas_object-detection_damoyolo_traffic_sign"},
        {"id": "cigarette-detection",   "ms": "iic/cv_tinynas_object-detection_damoyolo_cigarette"},
    ],
    # ── 人脸检测 ──
    "face_detection": [
        {"id": "facade-face",           "local": True},
        {"id": "retinaface-r50",        "ms": "damo/cv_resnet50_face-detection_retinaface"},
        {"id": "ddsar",                 "ms": "iic/cv_ddsar_face-detection_iclr23-damofd"},
        {"id": "ddsar-2.5g",            "ms": "iic/cv_ddsar_face-detection_iclr23-damofd-2.5G"},
        {"id": "ddsar-10g",             "ms": "iic/cv_ddsar_face-detection_iclr23-damofd-10G"},
        {"id": "ddsar-34g",             "ms": "iic/cv_ddsar_face-detection_iclr23-damofd-34G"},
        {"id": "scrfd10gkps",           "ms": "iic/cv_resnet_facedetection_scrfd10gkps"},
        {"id": "scrfd34gkps",           "ms": "iic/cv_resnet_carddetection_scrfd34gkps"},
        {"id": "mtcnn",                 "ms": "iic/cv_manual_face-detection_mtcnn"},
        {"id": "nanodet-face-human-hand", "ms": "iic/cv_nanodet_face-human-hand-detection"},
        {"id": "vidt-face",             "ms": "iic/ViDT-face-detection"},
        {"id": "mogface",               "ms": "damo/cv_resnet101_face-detection_cvpr22papermogface"},
        # ── 轻量级人脸检测 ──
        {"id": "ulfd",                  "ms": "iic/cv_manual_face-detection_ulfd"},
        {"id": "tinymog",               "ms": "iic/cv_manual_face-detection_tinymog"},
    ],
    # ── 文字生成音乐 ──
    "music_gen": [
        {"id": "musicgen-small", "hf": "facebook/musicgen-small", "source": "huggingface"},
        {"id": "bark",           "hf": "suno/bark-small", "source": "huggingface"},
    ],
    # ── OCR 文字识别 ──
    "ocr": [
        {"id": "facade-ocr",            "local": True},
        # ── 文档识别 ──
        {"id": "convnext-ocr-doc",      "ms": "iic/cv_convnextTiny_ocr-recognition-document_damo"},
        {"id": "damo-ocr-doc",          "ms": "damo/cv_convnextTiny_ocr-recognition-document_damo"},
        {"id": "ofa-ocr-doc",           "ms": "iic/ofa_ocr-recognition_document_base_zh"},
        # ── 通用文字识别 ──
        {"id": "convnext-ocr-general",  "ms": "iic/cv_convnextTiny_ocr-recognition-general_damo"},
        {"id": "crnn-ocr-general",      "ms": "iic/cv_crnn_ocr-recognition-general_damo"},
        # ── 场景文字识别 ──
        {"id": "convnext-ocr-scene",    "ms": "iic/cv_convnextTiny_ocr-recognition-scene_damo"},
        # ── 手写体识别 ──
        {"id": "convnext-ocr-handwritten","ms": "iic/cv_convnextTiny_ocr-recognition-handwritten_damo"},
        {"id": "ofa-ocr-handwritten",   "ms": "iic/ofa_ocr-recognition_handwriting_base_zh"},
        # ── 车牌识别 ──
        {"id": "convnext-ocr-plate",    "ms": "iic/cv_convnextTiny_ocr-recognition-licenseplate_damo"},
        # ── OFA 通用识别 ──
        {"id": "ofa-ocr-general",       "ms": "iic/ofa_ocr-recognition_general_base_zh"},
        {"id": "ofa-ocr-web",           "ms": "iic/ofa_ocr-recognition_web_base_zh"},
        # ── 轻量级边缘OCR ──
        {"id": "lite-ocr",              "ms": "iic/cv_LightweightEdge_ocr-recognitoin-general_damo"},
        # ── PP-OCRv6 多语言文字识别 ──
        # 注: PP-OCRv6 检测/识别模型分开发布, 需分别指定 det + rec 目录
        # 模型统一存放于 H:\environment\ocr\PP-OCRv6\<size>\
        {"id": "ppocrv6-tiny",       "hf_det": "PaddlePaddle/PP-OCRv6_tiny_det_safetensors",
                                        "hf_rec": "PaddlePaddle/PP-OCRv6_tiny_rec_safetensors",
                                        "source": "huggingface", "runner": "ppocrv6",
                                        "size": "tiny"},
        {"id": "ppocrv6-small",      "hf_det": "PaddlePaddle/PP-OCRv6_small_det_safetensors",
                                        "hf_rec": "PaddlePaddle/PP-OCRv6_small_rec_safetensors",
                                        "source": "huggingface", "runner": "ppocrv6",
                                        "size": "small"},
        {"id": "ppocrv6-medium",     "hf_det": "PaddlePaddle/PP-OCRv6_medium_det_safetensors",
                                        "hf_rec": "PaddlePaddle/PP-OCRv6_medium_rec_safetensors",
                                        "source": "huggingface", "runner": "ppocrv6",
                                        "size": "medium"},
    ],
    # ── 端到端文档解析 (OvisOCR2) ──
    "document_ocr": [
        {"id": "unlimited_ocr", "hf": "baidu/Unlimited-OCR", "source": "huggingface", "runner": "unlimited_ocr"},
        {"id": "ovisocr2",  "ms": "ATH-MaaS/OvisOCR2", "runner": "ovis_ocr"},
    ],
}


def resolve_model_id(alias: str, model_type: str) -> Optional[dict]:
    """按别名查找模型注册表"""
    models = MODEL_REGISTRY.get(model_type, [])
    for m in models:
        if m["id"] == alias or alias in m["id"]:
            return m
    # 直接作为 ModelScope ID
    return {"id": alias, "ms": alias}


def get_model_runner(model_name: str, model_type: str, model_root: Path):
    cache_key = f"{model_type}:{model_name}"
    if cache_key in _model_runners:
        return _model_runners[cache_key]
    entry = resolve_model_id(model_name, model_type)
    runner = _create_runner(model_name, model_type, model_root, entry)
    _model_runners[cache_key] = runner
    return runner


def _create_runner(model_name: str, model_type: str, model_root: Path, entry: dict):
    # MiniMind-3o 需要 Omni 加载器（主模型 + SenseVoice 编码器）
    if entry.get("runner") == "omni":
        return MiniMindOmniRunner(model_name, model_type, model_root, entry)
    if entry.get("runner") == "ppocrv6":
        return PpOcrV6Runner(model_name, model_type, model_root, entry)
    if entry.get("runner") == "ovis_ocr":
        return OvisOcrRunner(model_name, model_type, model_root, entry)
    if entry.get("runner") == "unlimited_ocr":
        return UnlimitedOcrRunner(model_name, model_type, model_root, entry)
    if entry.get("local") and model_type in ("detection", "face_detection", "ocr"):
        return LocalVisionRunner(model_name, model_type, model_root, entry)
    if entry.get("local") and model_type == "vlm":
        return LocalVisionUnderstandingRunner(model_name, model_type, model_root, entry)
    if entry.get("openai"):
        return OpenAIVlmRunner(model_name, model_type, model_root, entry)
    runners = {
        "llm": LmRunner,
        "tryon": TryonRunner, "image_gen": ImageGenRunner,
        "tts": TtsRunner, "asr": AsrRunner,
        "text_embedding": TextEmbeddingRunner, "face_swap": FaceSwapRunner,
        "music_gen": MusicGenRunner,
        "image_enhance": ImageEnhanceRunner,
        "image_recognition": ImageRecognitionRunner,
        "vlm": VlmRunner,
        "matting": MattingRunner,
        "detection": DetectionRunner,
        "face_detection": DetectionRunner,
        "ocr": DetectionRunner,
    }
    cls = runners.get(model_type)
    if not cls:
        raise ValueError(f"不支持的模型类型: {model_type}")
    return cls(model_name, model_type, model_root, entry)


class BaseRunner:
    def __init__(self, model_name: str, model_type: str, model_root: Path, entry: dict):
        self.model_name = model_name
        self.model_type = model_type
        self.model_root = model_root
        self.entry = entry
        self._model = None
        self._loaded = False
        self.use_gpu = False
        self.device_id = -1

    def _apply_gpu(self, params: dict):
        """从 params 读取 GPU 配置"""
        self.use_gpu = params.get("use_gpu", False)
        self.device_id = params.get("device_id", -1)

    def _device_str(self) -> str:
        """返回 torch 设备字符串"""
        if self.use_gpu:
            return f"cuda:{self.device_id}" if self.device_id >= 0 else "cuda"
        return "cpu"

    def _auto_download(self) -> str:
        """从 ModelScope 或 HuggingFace 自动下载，返回模型目录"""
        source = self.entry.get("source", "modelscope")
        repo_id = self.entry.get("ms" if source == "modelscope" else "hf", self.model_name)
        revision = self.entry.get("rev")

        # 先检查本地是否已存在
        local_candidates = self._candidate_dirs(repo_id, source)
        for local_path in local_candidates:
            if local_path.exists() and any(f.suffix != '.lock' for f in local_path.iterdir()):
                log.info(f"[SafeTensor] 本地已存在: {local_path}")
                return str(local_path)

        if source == "huggingface":
            from huggingface_hub import snapshot_download as hf_download
            log.info(f"[SafeTensor] 从 HF 下载: {repo_id}")
            local_dir = self.model_root / "_hf_local" / repo_id.replace("/", "_")
            local_dir.mkdir(parents=True, exist_ok=True)
            kwargs = {"local_dir": str(local_dir), "local_dir_use_symlinks": False}
            if revision:
                kwargs["revision"] = revision
            return hf_download(repo_id, **kwargs)
        else:
            from modelscope.hub.snapshot_download import snapshot_download
            log.info(f"[SafeTensor] 从 ModelScope 下载: {repo_id}")
            kwargs = {"cache_dir": str(self.model_root)}
            if revision:
                kwargs["revision"] = revision
            return snapshot_download(repo_id, **kwargs)

    def _candidate_dirs(self, repo_id: str, source: str):
        from pathlib import Path

        candidates = []
        repo_norm = repo_id.replace("/", "_")
        candidates.append(self.model_root / repo_id)
        candidates.append(self.model_root / repo_norm)
        candidates.append(self.model_root / "_hf" / repo_id)
        candidates.append(self.model_root / "_hf" / repo_norm)
        candidates.append(self.model_root / "_hf_local" / repo_norm)
        # ModelScope 新版缓存结构: {root}/models/{repo_norm}/snapshots/{revision}
        ms_models = self.model_root / "models" / repo_norm / "snapshots"
        if ms_models.exists():
            for child in ms_models.iterdir():
                if child.is_dir():
                    candidates.append(child)
            refs = self.model_root / "models" / repo_norm / "refs"
            if refs.exists():
                for ref in ("main", "master"):
                    ref_file = refs / ref
                    if ref_file.exists():
                        try:
                            snapshot = ms_models / ref_file.read_text(encoding="utf-8").strip()
                            if snapshot.exists():
                                candidates.append(snapshot)
                        except Exception:
                            pass
        if source == "huggingface" and "/" in repo_id:
            org, name = repo_id.split("/", 1)
            hf_root = self.model_root / "_hf_local" / repo_norm
            candidates.append(hf_root)
            snapshots = hf_root / "snapshots"
            if snapshots.exists():
                for child in snapshots.iterdir():
                    if child.is_dir():
                        candidates.append(child)
            refs = hf_root / "refs"
            if refs.exists():
                for ref in ("main", "master"):
                    ref_file = refs / ref
                    if ref_file.exists():
                        try:
                            snapshot = snapshots / ref_file.read_text(encoding="utf-8").strip()
                            if snapshot.exists():
                                candidates.append(snapshot)
                        except Exception:
                            pass
        return candidates

    def run(self, inputs: dict, params: dict) -> Any:
        raise NotImplementedError


class LmRunner(BaseRunner):
    """LLM 文本生成"""
    def _load(self):
        from transformers import AutoModelForCausalLM, AutoTokenizer
        import torch
        model_dir = self._auto_download()
        dtype = torch.float16 if self.use_gpu else torch.float32
        self._tokenizer = AutoTokenizer.from_pretrained(model_dir, trust_remote_code=True)
        self._model = AutoModelForCausalLM.from_pretrained(
            model_dir, device_map=None, trust_remote_code=True, low_cpu_mem_usage=False, torch_dtype=dtype
        ).to(self._device_str())
        self._loaded = True

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        prompt = inputs.get("text", "")
        max_new = params.get("max_new_tokens", 128)
        import torch

        # 使用 chat template（如果 tokenizer 有）
        if self._tokenizer.chat_template:
            messages = [{"role": "user", "content": prompt}]
            formatted = self._tokenizer.apply_chat_template(
                messages, add_generation_prompt=True, tokenize=False
            )
        else:
            formatted = prompt

        inp = self._tokenizer(formatted, return_tensors="pt")
        inp.pop("token_type_ids", None)
        if self.use_gpu:
            inp = {k: v.to(self._device_str()) for k, v in inp.items()}
        out = self._model.generate(**inp, max_new_tokens=max_new)
        input_len = inp["input_ids"].shape[1]
        generated = out[0][input_len:]
        return self._tokenizer.decode(generated, skip_special_tokens=True)


class TryonRunner(BaseRunner):
    """换装 — 支持标准 DiffusionPipeline + Qwen-Image LoRA (Nano-Banana) + ModelScope Pipeline (SAL-VTON)"""

    def _load(self):
        import torch
        if self.entry.get("pipeline"):
            self._load_pipeline()
        elif self.entry.get("lora"):
            self._load_qwen_image_lora()
        else:
            from diffusers import DiffusionPipeline
            model_dir = self._auto_download()
            dtype = torch.float16 if self.use_gpu else torch.float32
            self._model = DiffusionPipeline.from_pretrained(
                str(model_dir), torch_dtype=dtype, safety_checker=None
            )
            self._model.to(self._device_str())
        self._loaded = True

    def _load_pipeline(self):
        """加载 ModelScope pipeline 虚拟试衣模型（如 SAL-VTON）"""
        from modelscope.pipelines import pipeline
        from modelscope.utils.constant import Tasks
        model_dir = self._auto_download()
        device = self._device_str()
        self._pipe = pipeline(Tasks.image_try_on, model=model_dir, device=device)
        self._loaded = True

    def _load_qwen_image_lora(self):
        """加载 Qwen-Image 基础模型 + Nano-Banana LoRA 权重，同时准备 txt2img/img2img 管道"""
        import torch, os

        # 1) 下载 LoRA 权重目录
        lora_dir = self._auto_download()

        # 2) 下载 Qwen-Image 基础模型
        base_ms = self.entry.get("base_ms", "Qwen/Qwen-Image")
        from modelscope.hub.snapshot_download import snapshot_download
        base_dir = snapshot_download(base_ms, cache_dir=str(self.model_root))

        device = self._device_str()
        dtype = torch.float16 if self.use_gpu else torch.float32
        from diffusers import DiffusionPipeline, QwenImageImg2ImgPipeline

        # 加载 txt2img 管道
        pipe = DiffusionPipeline.from_pretrained(base_dir, torch_dtype=dtype)
        pipe.to(device)

        # 3) 找到 LoRA shard 文件
        shard_name = self.entry.get("lora_shard", "20.safetensors")
        lora_path = os.path.join(lora_dir, shard_name)
        if not os.path.exists(lora_path):
            files = sorted(Path(lora_dir).glob("*.safetensors"))
            if files:
                lora_path = str(files[-1])
            else:
                raise FileNotFoundError(f"Nano-Banana LoRA 权重未找到: {lora_dir}")

        # 4) 加载并合并 LoRA → transformer
        from safetensors import safe_open
        with safe_open(lora_path, framework="pt") as f:
            lora_weights = {k: f.get_tensor(k) for k in f.keys()}

        transformer = pipe.transformer
        pairs = {}
        for key, tensor in lora_weights.items():
            if key.endswith(".lora_A.default.weight"):
                base = key[: -len(".lora_A.default.weight")]
                pairs.setdefault(base, {})["A"] = tensor
            elif key.endswith(".lora_B.default.weight"):
                base = key[: -len(".lora_B.default.weight")]
                pairs.setdefault(base, {})["B"] = tensor

        alpha = 1.0
        applied = 0
        for module_path, pair in pairs.items():
            if "A" not in pair or "B" not in pair:
                continue
            A, B = pair["A"], pair["B"]
            parts = module_path.split(".")
            mod = transformer
            try:
                for p in parts:
                    if p.isdigit():
                        mod = mod[int(p)]
                    else:
                        mod = getattr(mod, p)
                delta = alpha * (B @ A)
                mod.weight.data += delta.to(
                    device=mod.weight.device, dtype=mod.weight.dtype
                )
                applied += 1
            except Exception as e:
                log.warning(f"[QwenImageLoRA] 跳过 {module_path}: {e}")

        log.info(f"[QwenImageLoRA] 已合并 {applied}/{len(pairs)} 个 LoRA 模块")

        # 5) 用同一 transformer 创建 img2img 管道（共享已合并 LoRA 的权重）
        pipe_img2img = QwenImageImg2ImgPipeline(
            scheduler=pipe.scheduler,
            vae=pipe.vae,
            text_encoder=pipe.text_encoder,
            tokenizer=pipe.tokenizer,
            transformer=pipe.transformer,  # 共享，LoRA 已合并
        )
        pipe_img2img.to(device)

        self._model = pipe          # txt2img
        self._model_img2img = pipe_img2img  # img2img

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        if self.entry.get("pipeline"):
            return self._run_pipeline(inputs, params)

        prompt = inputs.get("prompt", "")
        image_input = inputs.get("image", "")
        steps = params.get("steps", 50)
        cfg = params.get("true_cfg_scale", 4.0)
        import base64, io
        from PIL import Image

        if image_input and self.entry.get("lora"):
            # 图生图 — 使用 QwenImageImg2ImgPipeline
            image = Image.open(io.BytesIO(base64.b64decode(image_input))).convert("RGB")
            strength = params.get("strength", 0.6)
            result = self._model_img2img(
                prompt=prompt,
                image=image,
                strength=strength,
                num_inference_steps=steps,
                true_cfg_scale=cfg,
            ).images[0]
        elif image_input:
            # 标准管道图生图
            image = Image.open(io.BytesIO(base64.b64decode(image_input))).convert("RGB")
            result = self._model(prompt, image=image, num_inference_steps=steps).images[0]
        else:
            # 文生图
            result = self._model(
                prompt=prompt,
                num_inference_steps=steps,
                true_cfg_scale=cfg,
            ).images[0]

        buf = io.BytesIO()
        result.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode()

    def _run_pipeline(self, inputs: dict, params: dict) -> str:
        """ModelScope pipeline 虚拟试衣推理（SAL-VTON）：
        输入三张 base64 图片：person_image, garment_image, mask_image
        输出试衣后图片的 base64
        """
        import base64, tempfile, os, shutil
        import cv2
        import numpy as np
        from modelscope.outputs import OutputKeys

        person_b64 = inputs.get("person_image", "")
        garment_b64 = inputs.get("garment_image", "")
        mask_b64 = inputs.get("mask_image", "")
        if not person_b64 or not garment_b64 or not mask_b64:
            raise ValueError("SAL-VTON 需要 person_image, garment_image, mask_image 三个 base64 输入")

        temp_dir = tempfile.mkdtemp()
        try:
            person_path = os.path.join(temp_dir, "person.jpg")
            garment_path = os.path.join(temp_dir, "garment.jpg")
            mask_path = os.path.join(temp_dir, "mask.jpg")

            for b64_data, save_path in [
                (person_b64, person_path),
                (garment_b64, garment_path),
                (mask_b64, mask_path),
            ]:
                img_bytes = base64.b64decode(b64_data)
                arr = np.frombuffer(img_bytes, np.uint8)
                img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if img is None:
                    raise ValueError(f"图片解码失败: {save_path}")
                cv2.imwrite(save_path, img)

            result = self._pipe({
                "person_input_path": person_path,
                "garment_input_path": garment_path,
                "mask_input_path": mask_path,
            })

            output_img = result[OutputKeys.OUTPUT_IMG]
            _, encoded = cv2.imencode(".png", output_img)
            return base64.b64encode(encoded.tobytes()).decode()
        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)


class ImageGenRunner(BaseRunner):
    """文生图 (Stable Diffusion)"""
    def _load(self):
        import torch
        from diffusers import StableDiffusionImg2ImgPipeline, StableDiffusionPipeline
        model_dir = self._auto_download()
        dtype = torch.float16 if self.use_gpu else torch.float32
        device = self._device_str()
        pipe = StableDiffusionPipeline.from_pretrained(
            str(model_dir), torch_dtype=dtype, safety_checker=None
        )
        self._model = pipe.to(device)
        self._model_img2img = StableDiffusionImg2ImgPipeline.from_pretrained(
            str(model_dir), torch_dtype=dtype, safety_checker=None
        ).to(device)
        self._loaded = True

    @staticmethod
    def _load_image(image_input):
        import base64
        import io
        import os
        from pathlib import Path
        from PIL import Image

        if image_input is None or image_input == "":
            return None
        if isinstance(image_input, bytes):
            return Image.open(io.BytesIO(image_input)).convert("RGB")
        if isinstance(image_input, str):
            candidate = Path(image_input)
            if candidate.exists():
                return Image.open(candidate).convert("RGB")
            if image_input.startswith("http://") or image_input.startswith("https://"):
                import requests
                resp = requests.get(image_input, timeout=120)
                resp.raise_for_status()
                return Image.open(io.BytesIO(resp.content)).convert("RGB")
            try:
                return Image.open(io.BytesIO(base64.b64decode(image_input))).convert("RGB")
            except Exception:
                return None
        return None

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        prompt = inputs.get("prompt", "")
        image_input = inputs.get("image", "")
        steps = params.get("steps", 30)
        if image_input:
            image = self._load_image(image_input)
            if image is None:
                raise ValueError("无法解析 image 输入")
            result = self._model_img2img(
                prompt=prompt,
                image=image,
                strength=float(params.get("strength", 0.65)),
                num_inference_steps=steps,
            ).images[0]
        else:
            result = self._model(prompt, num_inference_steps=steps).images[0]
        import base64, io
        buf = io.BytesIO()
        result.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode()


class TtsRunner(BaseRunner):
    """文生音频 (TTS)"""

    DEFAULT_KOKORO_ROOTS = (
        os.environ.get("KOKORO_MODEL_DIR"),
        r"H:\environment\onnx\voice\tts\kokoro",
        r"D:\safetensor_models\iic\CosyVoice-300M",
        r"D:\safetensor_models\iic\CosyVoice2-0___5B",
    )

    def _load(self):
        import base64
        import io
        import numpy as np
        import soundfile as sf
        from pathlib import Path

        model_root = self._resolve_kokoro_root()
        model_path = model_root / "onnx" / "model_q4.onnx"
        voices_path = model_root / "voices" / "voices.pkl"
        if not model_path.exists():
            model_path = model_root / "onnx" / "model.onnx"
        if not model_path.exists() or not voices_path.exists():
            raise FileNotFoundError(f"Kokoro 资源缺失: {model_root}")

        from kokoro_onnx import Kokoro
        self._kokoro = Kokoro(model_path=str(model_path), voices_path=str(voices_path))
        self._loaded = True
        log.info("[TTS] 加载完成: %s", model_root)

    def _resolve_kokoro_root(self):
        from pathlib import Path

        for item in self.DEFAULT_KOKORO_ROOTS:
            if not item:
                continue
            candidate = Path(item)
            if candidate.exists():
                return candidate
        raise FileNotFoundError("未找到可用的 Kokoro/CosyVoice 本地资源")

    def run(self, inputs: dict, params: dict) -> str:
        import base64
        import io
        import numpy as np
        import soundfile as sf

        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        text = inputs.get("text") or inputs.get("prompt") or ""
        if not text.strip():
            raise ValueError("文本不能为空")

        voice = inputs.get("voice") or params.get("voice") or "zf_xiaoxiao"
        speed = float(params.get("speed", 1.0))
        audio, sr = self._kokoro.create(text, voice=voice, speed=speed)
        audio_1d = np.squeeze(audio)
        buf = io.BytesIO()
        sf.write(buf, audio_1d, sr, format="WAV")
        return base64.b64encode(buf.getvalue()).decode()


class AsrRunner(BaseRunner):
    """音频转文字 (ASR) — 支持普通识别和对话识别模式"""

    # VAD / 标点 / CAM++ 模型路径（对话模式专用）
    VAD_MODEL = "iic/speech_fsmn_vad_zh-cn-16k-common-pytorch"
    PUNC_MODEL = "iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch"
    CAM_MODEL = "iic/speech_campplus_sv_zh-cn_16k-common"

    def _load(self):
        from funasr import AutoModel
        model_dir = self._auto_download()
        self._pipe = AutoModel(model=model_dir, vad_kwargs={"max_single_segment_time": 30000}, disable_update=True)
        self._loaded = True

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        import base64, tempfile, os, json

        audio_bytes = base64.b64decode(inputs.get("audio", ""))
        suffix = ".wav" if not inputs.get("audio_format") else f".{inputs['audio_format']}"
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as f:
            f.write(audio_bytes)
            tmp = f.name
        try:
            mode = params.get("mode", "plain")
            if mode == "dialogue":
                return json.dumps(self._diarize(tmp, params), ensure_ascii=False)
            result = self._pipe.generate(input=tmp)
            if isinstance(result, list) and len(result) > 0:
                return result[0].get("text", "")
            return str(result)
        finally:
            os.unlink(tmp)

    # ===== 对话识别流水线 =====

    def _load_vad(self, max_segment_ms: int = 6000):
        from funasr import AutoModel
        model_dir = self._resolve_model_dir(self.VAD_MODEL)
        return AutoModel(model=model_dir, max_single_segment_time=max_segment_ms, disable_update=True)

    def _load_punc(self):
        from funasr import AutoModel
        model_dir = self._resolve_model_dir(self.PUNC_MODEL)
        return AutoModel(model=model_dir, disable_update=True)

    def _load_cam(self):
        """加载 CAM++ 说话人特征提取器"""
        from funasr import AutoModel
        model_dir = self._resolve_model_dir(self.CAM_MODEL)
        return AutoModel(model=model_dir, disable_update=True)

    def _resolve_model_dir(self, ms_id: str):
        """从缓存目录解析模型路径，不存在则自动下载"""
        from modelscope.hub.snapshot_download import snapshot_download
        local = self.model_root / ms_id.replace("/", "_")
        if local.exists() and any(f.suffix != '.lock' and f.suffix != '.mdl' for f in local.iterdir()):
            return str(local)
        return snapshot_download(ms_id, cache_dir=str(self.model_root))

    def _ensure_16k(self, audio_path: str):
        """读取音频并确保为 16kHz mono，返回 (audio_16k, sr_orig)"""
        import soundfile as sf
        audio, sr = sf.read(audio_path)
        if audio.ndim > 1:
            audio = audio.mean(axis=-1)
        if sr != 16000:
            log.info(f"[Diarize] 重采样 {sr} → 16000")
            import scipy.signal
            ratio = 16000 / sr
            new_len = int(len(audio) * ratio)
            audio = scipy.signal.resample(audio, new_len)
            sr = 16000
        return audio, sr

    def _diarize(self, audio_path: str, params: dict) -> list:
        """VAD → 说话人分离 → ASR → 标点 → 结构化结果"""
        import numpy as np, tempfile, os, shutil
        log.info("[Diarize] 开始对话识别流水线")

        audio_16k, sr_16k = self._ensure_16k(audio_path)
        sr = sr_16k

        # 1) VAD — 切分语音段
        log.info("[Diarize] Step 1: VAD")
        max_seg = params.get("max_segment_time", 6000)
        vad = self._load_vad(max_seg)
        vad_result = vad.generate(input=audio_path)
        segments = self._extract_segments(vad_result)
        if not segments:
            return [{"start": 0.0, "end": 0.0, "speaker": "S0", "text": ""}]
        log.info(f"[Diarize] VAD 检测到 {len(segments)} 个语音段")

        # 过滤过短段 (< 300ms) — CAM++ 需要足够长的音频
        segments = [s for s in segments if s["end_ms"] - s["start_ms"] >= 300]
        if not segments:
            return [{"start": 0.0, "end": 0.0, "speaker": "S0", "text": ""}]

        # 2) CAM++ — 逐段提取说话人特征向量
        log.info("[Diarize] Step 2: 说话人特征提取")
        import soundfile as sf

        cam = self._load_cam()
        all_embs = []
        for seg in segments:
            start_sample = int(seg["start_ms"] * sr / 1000)
            end_sample = int(seg["end_ms"] * sr / 1000)
            seg_audio = audio_16k[start_sample:end_sample]
            # 跳过过短片段
            if len(seg_audio) < sr * 0.2:
                all_embs.append(np.zeros(192, dtype=np.float32))
                continue
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
                sf.write(f, seg_audio, sr)
                seg_wav = f.name
            try:
                emb_result = cam.generate(input=seg_wav)
                emb = self._extract_embedding(emb_result)
                all_embs.append(emb if emb is not None else np.zeros(192, dtype=np.float32))
            except Exception as e:
                log.warning(f"[Diarize] CAM++ 提取失败 (segment {seg['start_ms']}ms): {e}")
                all_embs.append(np.zeros(192, dtype=np.float32))
            finally:
                os.unlink(seg_wav)

        # 3) 聚类 → 说话人标签
        speakers = self._cluster_speakers(all_embs, params.get("speaker_count", -1))
        for i, seg in enumerate(segments):
            seg["speaker"] = f"发言人{speakers[i]+1}"

        # 4) ASR — 为每个语音段提取音频片段后识别
        log.info("[Diarize] Step 4: ASR 识别")
        for seg in segments:
            start_sample = int(seg["start_ms"] * sr / 1000)
            end_sample = int(seg["end_ms"] * sr / 1000)
            segment_audio = audio_16k[start_sample:end_sample]

            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
                sf.write(f, segment_audio, sr)
                seg_wav = f.name
            try:
                text_result = self._pipe.generate(input=seg_wav)
                seg["text"] = self._extract_text(text_result)
            finally:
                os.unlink(seg_wav)

        # 5) 标点恢复
        log.info("[Diarize] Step 5: 标点恢复")
        punc = self._load_punc()
        for seg in segments:
            if seg["text"]:
                try:
                    punc_result = punc.generate(input=seg["text"])
                    if isinstance(punc_result, list) and len(punc_result) > 0:
                        seg["text"] = punc_result[0].get("text", seg["text"])
                except Exception as e:
                    log.warning(f"[Diarize] 标点恢复失败: {e}")

        # 合并同说话人的相邻段
        merged = self._merge_adjacent(segments)

        # 转换 ms → 秒并过滤 <|...|> 标签
        result = []
        for seg in merged:
            text = seg["text"]
            # 清理 SenseVoice 标签
            import re
            text = re.sub(r'<\|[^|]+\|>', '', text).strip()
            result.append({
                "start": round(seg["start_ms"] / 1000.0, 2),
                "end": round(seg["end_ms"] / 1000.0, 2),
                "speaker": seg["speaker"],
                "text": text
            })
        log.info(f"[Diarize] 完成: {len(result)} 段")
        return result

    def _merge_adjacent(self, segments: list) -> list:
        """合并同一说话人的相邻语音段"""
        if len(segments) <= 1:
            return segments
        merged = [segments[0].copy()]
        for seg in segments[1:]:
            last = merged[-1]
            if last["speaker"] == seg["speaker"] and seg["start_ms"] - last["end_ms"] < 500:
                last["end_ms"] = seg["end_ms"]
                last["text"] = last["text"] + seg["text"]
            else:
                merged.append(seg.copy())
        return merged

    def _extract_all_embeddings(self, emb_result) -> list:
        """从 CAM++ 批量结果提取 embedding 列表"""
        import numpy as np
        embs = []
        try:
            if isinstance(emb_result, list) and len(emb_result) > 0:
                item = emb_result[0]
                if isinstance(item, dict):
                    emb_val = item.get("spk_embedding")
                    if emb_val is not None:
                        if hasattr(emb_val, 'cpu'):
                            emb_val = emb_val.cpu().detach().numpy()
                        emb_val = np.asarray(emb_val, dtype=np.float32)
                        # shape: (n_segments, 192) or (1, 192)
                        if emb_val.ndim == 1:
                            embs = [emb_val]
                        else:
                            embs = [emb_val[i] for i in range(emb_val.shape[0])]
        except Exception as e:
            log.warning(f"[Diarize] 提取 embedding 失败: {e}")
        return embs

    def _extract_segments(self, vad_result) -> list:
        """从 VAD 结果提取语音段 [{start_ms, end_ms}]"""
        segments = []
        if isinstance(vad_result, list) and len(vad_result) > 0:
            item = vad_result[0]
            if isinstance(item, dict) and "value" in item:
                for v in item["value"]:
                    start_ms = int(v[0])
                    end_ms = int(v[1])
                    if end_ms - start_ms > 200:  # 过滤 < 200ms 的段
                        segments.append({"start_ms": start_ms, "end_ms": end_ms})
        return segments

    def _extract_embedding(self, emb_result):
        """从 CAM++ 结果提取 embedding 向量 (1D, float32)"""
        import numpy as np
        try:
            if isinstance(emb_result, list) and len(emb_result) > 0:
                item = emb_result[0]
                if isinstance(item, dict) and "spk_embedding" in item:
                    emb = item["spk_embedding"]
                    if hasattr(emb, 'cpu'):
                        emb = emb.cpu().detach().numpy()
                    emb = np.asarray(emb, dtype=np.float32).flatten()
                    return emb if len(emb) > 0 else None
        except Exception:
            pass
        return None

    def _extract_text(self, asr_result) -> str:
        """从 ASR 结果提取文本"""
        if isinstance(asr_result, list) and len(asr_result) > 0:
            return asr_result[0].get("text", "")
        return str(asr_result) if asr_result else ""

    def _cluster_speakers(self, embeddings: list, speaker_count: int) -> list:
        """对 embedding 进行谱聚类，返回每段的说话人 ID"""
        import numpy as np
        n = len(embeddings)
        if n <= 1:
            return [0] * n

        # 检查多少是有效 embedding（非零向量）
        valid_mask = [e is not None and np.linalg.norm(e) > 0.01 for e in embeddings]
        labels = [0] * n

        valid_embs = [e for e, v in zip(embeddings, valid_mask) if v]
        valid_indices = [i for i, v in enumerate(valid_mask) if v]
        if len(valid_embs) <= 1:
            return [0] * n

        # 构建相似度矩阵
        X = np.array(valid_embs)
        norms = np.linalg.norm(X, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        X_norm = X / norms
        sim = np.dot(X_norm, X_norm.T)
        sim = np.clip(sim, 0.0, 1.0)  # 亲和矩阵必须非负

        from sklearn.cluster import SpectralClustering
        n_clusters = max(1, min(speaker_count if speaker_count > 0 else 2, len(valid_embs)))
        if n_clusters == 1:
            return [0] * n

        clustering = SpectralClustering(n_clusters=n_clusters, affinity='precomputed',
                                        assign_labels='discretize', random_state=42)
        cluster_labels = clustering.fit_predict(sim)

        for idx, label in zip(valid_indices, cluster_labels):
            labels[idx] = int(label)
        return labels


class MiniMindOmniRunner(BaseRunner):
    """MiniMind-3o Omni + SenseVoice 音频编码器"""
    SENSEVOICE_PATH = "D:/safetensor_models/iic/SenseVoiceSmall"

    @staticmethod
    def _strip_think(text: str) -> str:
        """移除 <think>...</think> 标签，保留最终答案"""
        import re
        text = re.sub(r'<think>.*?</think>', '', text, flags=re.DOTALL).strip()
        # 移除开头的 <think>（无闭合标签的情况）
        text = re.sub(r'<think>.*$', '', text, flags=re.DOTALL).strip()
        return text

    def _load(self):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer

        model_dir = self._auto_download()
        device = self._device_str()
        dtype = torch.float16 if self.use_gpu else torch.float32
        log.info(f"[MiniMindOmni] 加载主模型: {model_dir} device={device}")
        self._tokenizer = AutoTokenizer.from_pretrained(model_dir, trust_remote_code=True)
        self._model = AutoModelForCausalLM.from_pretrained(
            model_dir, trust_remote_code=True, torch_dtype=dtype, device_map=None, low_cpu_mem_usage=False
        ).to(device)
        self._model.eval()

        if not os.path.exists(self.SENSEVOICE_PATH):
            log.warning(f"[MiniMindOmni] SenseVoice 路径不存在: {self.SENSEVOICE_PATH}")
            self._has_audio_encoder = False
            self._loaded = True
            return

        from funasr import AutoModel
        log.info(f"[MiniMindOmni] 加载 SenseVoice: {self.SENSEVOICE_PATH}")
        sv = AutoModel(model=self.SENSEVOICE_PATH, trust_remote_code=True, disable_update=True, device=device)
        encoder = sv.model.encoder
        frontend = sv.kwargs["frontend"]
        for p in encoder.parameters():
            p.requires_grad = False
        encoder = encoder.eval().float()

        class SenseVoiceAudioProcessor:
            def __init__(self, fe):
                self.frontend = fe
            def __call__(self_ap, wav, sampling_rate=16000, **kwargs):
                if isinstance(wav, np.ndarray):
                    wav = torch.from_numpy(wav).float()
                if isinstance(wav, (list, tuple)):
                    wav = torch.tensor(wav, dtype=torch.float32)
                if wav.dim() == 1:
                    wav = wav.unsqueeze(0)
                with torch.no_grad():
                    fbank, flen = self_ap.frontend(wav, torch.tensor([wav.size(1)]))
                from types import SimpleNamespace
                return SimpleNamespace(
                    input_features=fbank,
                    attention_mask=(torch.arange(fbank.size(1)) < flen[0]).long().unsqueeze(0)
                )

        object.__setattr__(self._model, "audio_encoder", encoder)
        object.__setattr__(self._model, "audio_processor", SenseVoiceAudioProcessor(frontend.eval()))
        self._has_audio_encoder = True
        self._loaded = True
        log.info("[MiniMindOmni] 加载完成")

    def _audio_to_text(self, audio_np: np.ndarray, sr: int = 16000) -> str:
        import torch
        model = self._model
        tok = self._tokenizer

        proc = model.audio_processor(audio_np, sampling_rate=sr)
        fbank = proc.input_features
        flen = proc.attention_mask.sum().item()

        enc_dtype = next(model.audio_encoder.parameters()).dtype
        valid_fbank = fbank[:1].to(dtype=enc_dtype)
        valid_lens = torch.tensor([flen])
        with torch.no_grad():
            emb, _ = model.audio_encoder(valid_fbank, valid_lens)

        n_pads = min(flen, emb.size(1))

        audio_pad_id = 16
        prompt = (
            "<|im_start|>user\n"
            "<|audio_start|>" + "<|audio_pad|>" * n_pads + "<|audio_end|>"
            "<|im_end|>\n"
            "<|im_start|>assistant\n"
        )
        input_ids = tok.encode(prompt, return_tensors="pt")

        with torch.no_grad():
            out = model.forward(input_ids, audio_inputs=fbank, audio_lens=valid_lens, use_cache=True)
            all_ids = input_ids.clone()
            past_kv = out.past_key_values
            for _ in range(200):
                last_logits = out.logits[0, -1, :]
                next_tok = last_logits.argmax().unsqueeze(0).unsqueeze(0)
                all_ids = torch.cat([all_ids, next_tok], dim=-1)
                if next_tok.item() == 2:
                    break
                out = model.forward(next_tok, past_key_values=past_kv, use_cache=True)
                past_kv = out.past_key_values

        generated = all_ids[0, input_ids.size(1):]
        raw = tok.decode(generated, skip_special_tokens=True).strip()
        return self._strip_think(raw)

    def _text_generate(self, text: str) -> str:
        """纯文本生成（绕过 MiniMindOmni 自定义 generate）"""
        import torch
        model = self._model
        tok = self._tokenizer

        if tok.chat_template:
            messages = [{"role": "user", "content": text}]
            formatted = tok.apply_chat_template(messages, add_generation_prompt=True, tokenize=False)
        else:
            formatted = text

        input_ids = tok.encode(formatted, return_tensors="pt")

        with torch.no_grad():
            out = model.forward(input_ids, use_cache=True)
            all_ids = input_ids.clone()
            past_kv = out.past_key_values
            for _ in range(512):
                last_logits = out.logits[0, -1, :]
                next_tok = last_logits.argmax().unsqueeze(0).unsqueeze(0)
                all_ids = torch.cat([all_ids, next_tok], dim=-1)
                if next_tok.item() == 2:
                    break
                out = model.forward(next_tok, past_key_values=past_kv, use_cache=True)
                past_kv = out.past_key_values

        generated = all_ids[0, input_ids.size(1):]
        raw = tok.decode(generated, skip_special_tokens=True).strip()
        return self._strip_think(raw)

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        # ASR（音频输入）
        audio_data = inputs.get("audio")
        if audio_data:
            import base64, tempfile, soundfile as sf
            audio_bytes = base64.b64decode(audio_data)
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
                f.write(audio_bytes)
                tmp = f.name
            try:
                wav, sr = sf.read(tmp)
                if wav.ndim > 1:
                    wav = wav.mean(-1)
                if sr != 16000:
                    import librosa
                    wav = librosa.resample(wav, orig_sr=sr, target_sr=16000)
                    sr = 16000
                return self._audio_to_text(wav, sr)
            finally:
                os.unlink(tmp)

        # 纯文本生成
        text_input = inputs.get("text", "")
        if text_input:
            return self._text_generate(text_input)

        raise ValueError("输入必须包含 audio 或 text 字段")


class VlmRunner(BaseRunner):
    """图文理解 / VLM — MiniMind-V + SigLIP2."""

    DEFAULT_VISION_MODEL = "jingyaogong/siglip2-base-p32-256-ve"

    @staticmethod
    def _strip_think(text: str) -> str:
        import re
        text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()
        return re.sub(r"<think>.*$", "", text, flags=re.DOTALL).strip()

    def _load_model_dir(self, repo_id: str, source: str = "modelscope", revision: Optional[str] = None) -> str:
        candidates = self._candidate_dirs(repo_id, source)
        for candidate in candidates:
            if candidate.exists() and any(candidate.iterdir()):
                return str(candidate)

        if source == "huggingface":
            from huggingface_hub import snapshot_download as hf_download
            local_dir = self.model_root / "_hf_local" / repo_id.replace("/", "_")
            local_dir.mkdir(parents=True, exist_ok=True)
            kwargs = {"local_dir": str(local_dir), "local_dir_use_symlinks": False}
            if revision:
                kwargs["revision"] = revision
            return hf_download(repo_id, **kwargs)

        from modelscope.hub.snapshot_download import snapshot_download
        kwargs = {"cache_dir": str(self.model_root)}
        if revision:
            kwargs["revision"] = revision
        return snapshot_download(repo_id, **kwargs)

    def _load(self):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer, SiglipImageProcessor, SiglipVisionModel

        repo_id = self.entry.get("ms", self.model_name)
        source = self.entry.get("source", "modelscope")
        revision = self.entry.get("rev")
        vision_repo = self.entry.get("vision_ms", self.DEFAULT_VISION_MODEL)
        vision_source = self.entry.get("vision_source", "modelscope")
        vision_revision = self.entry.get("vision_rev")

        model_dir = self._load_model_dir(repo_id, source, revision)
        vision_dir = self._load_model_dir(vision_repo, vision_source, vision_revision)

        self._device = torch.device(self._device_str())
        dtype = torch.float16 if self._device.type == "cuda" else torch.float32

        self._tokenizer = AutoTokenizer.from_pretrained(model_dir, trust_remote_code=True)
        self._model = AutoModelForCausalLM.from_pretrained(
            model_dir,
            trust_remote_code=True,
            torch_dtype=dtype,
            device_map=None,
        ).to(self._device)

        vision_model = SiglipVisionModel.from_pretrained(vision_dir, torch_dtype=dtype).to(self._device)
        processor = SiglipImageProcessor.from_pretrained(vision_dir)
        object.__setattr__(self._model, "vision_encoder", vision_model.eval())
        object.__setattr__(self._model, "processor", processor)
        self._model.eval()
        self._image_token = self.entry.get("image_special_token", "<|image_pad|>")
        self._image_token_len = int(self.entry.get("image_token_len", 64))
        self._loaded = True
        log.info("[VLM] 加载完成: model=%s, vision=%s", model_dir, vision_dir)

    def _build_prompt(self, prompt: str) -> str:
        question = (prompt or "").strip() or "请详细描述这张图片。"
        image_tokens = self._image_token * self._image_token_len
        return f"{image_tokens}\n{question}"

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        import base64, io
        from PIL import Image
        import torch

        image_b64 = inputs.get("image", "")
        if not image_b64:
            raise ValueError("缺少 image 输入")

        prompt = inputs.get("text", inputs.get("prompt", ""))
        image = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")
        processed = self._model.processor(images=image, return_tensors="pt")
        pixel_values = {k: v.to(self._device) for k, v in processed.items()}
        input_ids = self._tokenizer.encode(self._build_prompt(prompt), return_tensors="pt").to(self._device)

        max_new_tokens = int(params.get("max_new_tokens", 128))
        temperature = float(params.get("temperature", 0.2))

        with torch.no_grad():
            output_ids = self._model.generate(
                input_ids=input_ids,
                pixel_values=pixel_values,
                max_new_tokens=max_new_tokens,
                do_sample=temperature > 0.0,
                temperature=max(temperature, 1e-5),
                eos_token_id=self._tokenizer.eos_token_id,
                pad_token_id=self._tokenizer.eos_token_id,
            )

        text = self._tokenizer.decode(output_ids[0][input_ids.shape[1]:], skip_special_tokens=True).strip()
        return self._strip_think(text)


class TextEmbeddingRunner(BaseRunner):
    """文本嵌入 (sentence-transformers)"""
    def _load(self):
        from sentence_transformers import SentenceTransformer
        model_dir = self._auto_download()
        self._model = SentenceTransformer(str(model_dir), device=self._device_str())
        self._loaded = True
    def run(self, inputs: dict, params: dict) -> list:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        texts = inputs.get("texts", [inputs.get("text", "")])
        if isinstance(texts, str): texts = [texts]
        embeddings = self._model.encode(texts)
        return embeddings.tolist()

class _MsPipelineRunner(BaseRunner):
    """基类：使用 ModelScope pipeline 自动推理"""
    _requires_image = True

    # 模型类型 → ModelScope task 映射
    _TASK_MAP = {
        "face_detection": "face-detection",
        "ocr": "ocr-detection",
        "detection": "object-detection",
        "image_recognition": "image-classification",
        "matting": "portrait-matting",
        "image_enhance": "image-enhancement",
    }

    def _load(self):
        from modelscope.pipelines import pipeline
        model_dir = self._auto_download()
        import json
        from pathlib import Path

        # 1) 从 configuration.json 读取 task
        task = None
        for cfg_name in ("configuration.json", "config.json"):
            cfg_path = Path(model_dir) / cfg_name
            if cfg_path.exists():
                with open(cfg_path) as f:
                    cfg = json.load(f)
                task = cfg.get("task") or cfg.get("_task")
                if task:
                    break

        # 2) 配置里没有 → 从模型类型推断
        if not task:
            task = self._TASK_MAP.get(self.model_type)

        # 3) 还拿不到 → 报错
        if not task:
            raise ValueError(
                f"无法确定 pipeline task，请检查 {model_dir}/configuration.json "
                f"或补充 _TASK_MAP 映射"
            )

        log.info(f"[_MsPipelineRunner] task={task}, model={model_dir}")
        import torch
        device_arg = self._device_str() if hasattr(self, '_device_str') else ("cuda" if torch.cuda.is_available() else "cpu")
        try:
            self._pipe = pipeline(task=task, model=model_dir, device=device_arg)
        except TypeError:
            # 某些 modelscope pipeline 不支持 device 参数
            self._pipe = pipeline(task=task, model=model_dir)
        self._loaded = True

    def _decode_image(self, inputs: dict) -> any:
        """解码 base64 图像为 numpy ndarray (BGR)"""
        import base64, numpy as np, cv2, io
        img_b64 = inputs.get("image", "")
        if not img_b64:
            raise ValueError("缺少 image 输入")
        img_bytes = base64.b64decode(img_b64)
        arr = np.frombuffer(img_bytes, np.uint8)
        return cv2.imdecode(arr, cv2.IMREAD_COLOR)

    def _to_json(self, obj) -> dict:
        """将 pipeline 结果序列化（处理 numpy 类型）"""
        import json
        return json.loads(json.dumps(obj, default=lambda x: x.tolist() if hasattr(x, 'tolist') else str(x)))

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        import json
        img = self._decode_image(inputs)
        raw = self._pipe(img)
        return self._to_json(raw)


class ImageRecognitionRunner(_MsPipelineRunner):
    """图像分类/识别 — ModelScope pipeline"""
    pass


class ImageEnhanceRunner(_MsPipelineRunner):
    """图像增强/修复 — ModelScope pipeline，返回 base64 编码的增强图像 PNG"""
    def run(self, inputs, params):
        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        import base64
        import cv2
        import numpy as np
        img = self._decode_image(inputs)
        raw = self._pipe(img)
        # direct numpy or try common output keys (avoid JSON round-trip)
        output_img = raw if isinstance(raw, np.ndarray) else None
        if output_img is None and isinstance(raw, dict):
            for key in ("output_img", "result_img", "output", "image"):
                output_img = raw.get(key)
                if output_img is not None:
                    break
        if output_img is not None:
            arr = np.array(output_img)
            # ensure uint8
            if arr.dtype in (np.float32, np.float64):
                arr = np.clip(arr * 255, 0, 255).astype(np.uint8)
            else:
                arr = arr.astype(np.uint8)
            success, encoded = cv2.imencode('.png', arr)
            if success:
                return base64.b64encode(encoded.tobytes()).decode()
        return ""


class MattingRunner(_MsPipelineRunner):
    """抠图/分割 — ModelScope pipeline，返回 base64 编码的掩码 PNG"""
    def run(self, inputs, params):
        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        import base64
        import cv2
        import numpy as np
        img = self._decode_image(inputs)
        raw = self._pipe(img)
        output_mask = raw if isinstance(raw, np.ndarray) else raw.get("output_mask")
        if output_mask is not None:
            arr = np.array(output_mask, dtype=np.float32)
            arr = np.clip(arr * 255, 0, 255).astype(np.uint8)
            # squeeze single-channel 3D to 2D
            if arr.ndim == 3 and arr.shape[2] == 1:
                arr = arr[:, :, 0]
            success, encoded = cv2.imencode('.png', arr)
            if success:
                return base64.b64encode(encoded.tobytes()).decode()
        return ""


class DetectionRunner(_MsPipelineRunner):
    """目标检测 — ModelScope pipeline"""
    pass


class PpOcrV6Runner(BaseRunner):
    """PP-OCRv6 多语言文字识别 — 调用 paddleocr PaddleOCR 接口

    支持三个精度层级: ppocrv6-tiny (1.5M), ppocrv6-small (7.7M), ppocrv6-medium (34.5M)
    支持 50 种语言统一模型（中文、英文、日文 + 46 拉丁语系）
    模型来源: https://huggingface.co/collections/PaddlePaddle/pp-ocrv6
    """

    def _load(self):
        from paddleocr import PaddleOCR
        import os

        source = self.entry.get("source", "huggingface")
        size = self.entry.get("size", self.model_name.replace("ppocrv6-", ""))

        if source == "huggingface":
            det_repo = self.entry.get("hf_det")
            rec_repo = self.entry.get("hf_rec")
            ocr_root = self.model_root / "PP-OCRv6" / size
            det_dir = self._download_hf(det_repo, ocr_root / "det")
            rec_dir = self._download_hf(rec_repo, ocr_root / "rec")
        else:
            model_dir = self._auto_download()
            det_dir = os.path.join(model_dir, f"ppocrv6_{size}", "det")
            rec_dir = os.path.join(model_dir, f"ppocrv6_{size}", "rec")

        use_gpu = self.use_gpu and self._gpu_available()
        self._ocr = PaddleOCR(
            text_detection_model_dir=det_dir,
            text_recognition_model_dir=rec_dir,
            lang="ch",          # 50 语言统一模型
            use_doc_orientation_classify=False,
            use_textline_orientation=False,
            ocr_version="PP-OCRv6",
            use_gpu=use_gpu,
            gpu_id=self.device_id if self.device_id >= 0 else 0,
        )
        self._loaded = True

    def _download_hf(self, repo_id: str, target_dir: Path) -> str:
        """从 HuggingFace 下载单个模型（det 或 rec）到指定目录"""
        target_dir.mkdir(parents=True, exist_ok=True)
        if not any(f.suffix != '.lock' for f in target_dir.iterdir() if f.is_file()):
            from huggingface_hub import snapshot_download as hf_download
            return hf_download(repo_id, local_dir=str(target_dir), local_dir_use_symlinks=False)
        return str(target_dir)

    def _gpu_available(self) -> bool:
        try:
            import paddle
            return paddle.device.cuda.device_count() > 0
        except Exception:
            return False

    def run(self, inputs: dict, params: dict) -> list:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        import base64, numpy as np, cv2

        img_b64 = inputs.get("image", "")
        if not img_b64:
            raise ValueError("缺少 image 输入")

        arr = np.frombuffer(base64.b64decode(img_b64), np.uint8)
        image = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("图像解码失败")

        results = self._ocr.predict(image, use_textline_orientation=False)
        boxes = []
        if not results:
            return boxes

        # paddleocr 3.7.0 returns list of OCRResult objects
        for page in results:
            if not isinstance(page, dict):
                continue
            texts = page.get("rec_texts", [])
            scores = page.get("rec_scores", [])
            polys = page.get("rec_polys", page.get("dt_polys", []))
            for i, text in enumerate(texts):
                score = scores[i] if i < len(scores) else 0.0
                poly = polys[i] if i < len(polys) else None
                box = None
                if poly is not None:
                    box = [[int(x), int(y)] for x, y in poly]
                boxes.append({
                    "text": text,
                    "score": float(score),
                    "box": box,
                })
        return boxes


class OvisOcrRunner(BaseRunner):
    """
    OvisOCR2 端到端文档解析 — 将文档图像转换为 Markdown 格式。

    架构基于 Ovis (Qwen3.5-0.8B backbone) 单阶段 pipeline，可直接输出结构化 Markdown，
    适用于合同、发票、报表等文档图像识别。

    模型来源: https://modelscope.ai/models/ATH-MaaS/OvisOCR2
    依赖:
        - 推荐安装 ovis 包: pip install ovis
        - 若未安装 ovis，自动降级使用 ModelScope pipeline (需 modelscope)
    输入:
        image (base64): 文档图像
        text (可选): 自定义提示词，默认使用“请将这张文档图片转换为 Markdown 格式。”
    输出:
        str: 解析后的 Markdown 文本
    超时建议: 首次加载模型需下载约 2GB 文件，请设置 HTTP 超时 >= 600 秒。
    """

    def _load(self):
        import torch
        model_dir = self._auto_download()
        device = self._device_str()
        dtype = torch.bfloat16 if self.use_gpu else torch.float32

        try:
            from ovis import AutoModelForCausalLM, AutoProcessor
            log.info(f"[OvisOCR2] ovis 已安装，加载模型: {model_dir}, device={device}")
        except ImportError:
            from transformers import AutoModelForImageTextToText, AutoProcessor
            log.info(f"[OvisOCR2] ovis 未安装，使用 transformers AutoModelForImageTextToText 加载: {model_dir}, device={device}")

        self._processor = AutoProcessor.from_pretrained(model_dir, trust_remote_code=True)
        self._model = AutoModelForImageTextToText.from_pretrained(
            model_dir, dtype=dtype, trust_remote_code=True, device_map=None
        ).to(device).eval()
        self._loaded = True
        log.info("[OvisOCR2] 加载完成")

    def _load_modelscope_pipeline(self, model_dir: str):
        """通过 ModelScope pipeline 加载（降级路径）"""
        from modelscope.pipelines import pipeline
        task = "image-text-to-text"
        log.info(f"[OvisOCR2] 尝试 ModelScope pipeline task={task}")
        try:
            self._pipe = pipeline(task, model=model_dir, device=self._device_str(), trust_remote_code=True)
        except (TypeError, ValueError, Exception) as e:
            log.warning(f"[OvisOCR2] pipeline(task={task}) 失败: {e}，尝试无 device 参数")
            try:
                self._pipe = pipeline(task, model=model_dir, trust_remote_code=True)
            except Exception as e2:
                log.warning(f"[OvisOCR2] pipeline 降级也失败: {e2}")
                raise RuntimeError(
                    f"OvisOCR2 ModelScope pipeline 加载失败，请安装 ovis 包:\n"
                    f"  pip install git+https://github.com/ath-maas/ovis.git\n\n"
                    f"原错误: {e2}"
                )
        self._loaded = True

    def _clean_ocr_output(self, text: str) -> str:
        import re
        if not text:
            return text
        s = text
        sidx = s.rfind('assistant')
        if sidx >= 0:
            s = s[sidx + len('assistant'):].strip()
        s = re.sub(r'<[^>]+>', '', s)
        return s.strip()

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        import base64, io
        from PIL import Image

        image_b64 = inputs.get("image", "")
        if not image_b64:
            raise ValueError("缺少 image 输入")

        image = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")

        prompt = inputs.get("text") or inputs.get("prompt") or ""
        query = prompt if prompt.strip() else "请将这张文档图片转换为 Markdown 格式。"

        import torch

        # Qwen3.5 VLM：先经 chat 模板生成含影像占位符的字符串，再交给 processor
        chat_messages = [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": query}]}]
        chat_prompt = self._processor.apply_chat_template(chat_messages, add_generation_prompt=True, tokenize=False)
        inputs_tok = self._processor(text=chat_prompt, images=[image], return_tensors="pt")

        if self.use_gpu:
            inputs_tok = {k: v.to(self._device_str()) if hasattr(v, 'to') else v for k, v in inputs_tok.items()}
        max_tokens = int(params.get("max_new_tokens", 2048))

        with torch.no_grad():
            output = self._model.generate(**inputs_tok, max_new_tokens=max_tokens)

        raw = self._processor.decode(output[0], skip_special_tokens=True)
        # 剥离 chat 回显与 think/response 标签，仅保留最终内容
        return self._clean_ocr_output(raw)


class UnlimitedOcrRunner(BaseRunner):
    """
    百度 Unlimited-OCR 端到端文档解析 — 支持长文档、多页图像序列。

    基于 R-SWA (Reference Sliding Window Attention) 机制，可在单次推理中处理长文档，
    支持单张图片或连续多页图像序列，CPU 也能流畅运行。

    模型: baidu/Unlimited-OCR (3B)
    官网: https://github.com/baidu/Unlimited-OCR
    论文: arXiv:2606.23050
    输入:
        image (base64): 文档图像 (若为多页，需拼接为长图或传递多张)
        text (可选): 自定义提示词，默认 "document parsing."
    输出:
        str: 解析后的 Markdown 文本
    参数 (params):
        image_mode: "gundam" (单张裁剪，默认) 或 "base" (多页/不裁剪)
        max_new_tokens: 最大生成 tokens，默认 32768
    """

    DEFAULT_PROMPT = "document parsing."

    def _load(self):
        import torch
        from transformers import AutoModel, AutoTokenizer

        model_dir = self._auto_download()
        device = self._device_str()
        dtype = torch.bfloat16 if self.use_gpu else torch.float32

        log.info(f"[UnlimitedOCR] 加载模型: {model_dir}, device={device}")
        self._tokenizer = AutoTokenizer.from_pretrained(
            model_dir, trust_remote_code=True
        )
        self._model = AutoModel.from_pretrained(
            model_dir,
            trust_remote_code=True,
            use_safetensors=True,
            torch_dtype=dtype,
        ).to(device).eval()

        self._loaded = True
        log.info("[UnlimitedOCR] 模型加载完成")

    def run(self, inputs: dict, params: dict) -> str:
        """执行文档解析，返回 Markdown 文本结果。

        支持的 image_mode:
            - gundam: 单张图片模式，自动裁剪 (默认，推荐)
            - base: 多页/PDF 模式，不裁剪
        """
        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        import base64
        import tempfile
        import os
        from PIL import Image

        image_mode = params.get("image_mode", "gundam")
        prompt = inputs.get("text") or params.get("prompt") or self.DEFAULT_PROMPT
        max_new_tokens = int(params.get("max_new_tokens", 32768))
        temperature = float(params.get("temperature", 0.0))
        no_repeat_ngram_size = int(params.get("no_repeat_ngram_size", 35))
        ngram_window = int(params.get("ngram_window", 128))

        # prompt 必须以 <image> 开头（官方要求）
        if not prompt.strip().startswith("<image>"):
            prompt = "<image>" + prompt

        # 解码输入图像并写入临时文件（model.infer 需要文件路径）
        image_b64 = inputs.get("image", "")
        if not image_b64:
            raise ValueError("缺少 image 输入")

        image = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
            image.save(tmp.name)
            tmp_path = tmp.name

        try:
            image_size = 640 if image_mode == "gundam" else 1024
            base_size = params.get("base_size", 1024)
            crop_mode = (image_mode == "gundam")

            result = self._model.infer(
                self._tokenizer,
                prompt=prompt,
                image_file=tmp_path,
                output_path=None,
                base_size=base_size,
                image_size=image_size,
                crop_mode=crop_mode,
                max_length=max_new_tokens,
                temperature=temperature,
                no_repeat_ngram_size=no_repeat_ngram_size,
                ngram_window=ngram_window,
                save_results=False,
            )

            if isinstance(result, str):
                return result
            if isinstance(result, dict):
                return result.get("markdown", result.get("output", str(result)))
            return str(result)
        finally:
            os.unlink(tmp_path)


class LocalVisionRunner(BaseRunner):
    """OpenCV 本地轻量视觉 runner，用于 facade-* 默认能力，避免拉取重模型。"""

    def _decode_image(self, inputs: dict):
        import base64
        import cv2
        import numpy as np

        img_b64 = inputs.get("image", "")
        if not img_b64:
            raise ValueError("缺少 image 输入")
        arr = np.frombuffer(base64.b64decode(img_b64), np.uint8)
        image = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("图像解码失败")
        return image

    def run(self, inputs: dict, params: dict):
        image = self._decode_image(inputs)
        if self.model_type == "face_detection":
            return self._detect_faces(image, params)
        if self.model_type == "ocr":
            return self._detect_text_regions(image, params)
        return self._detect_foreground(image, params)

    def _detect_faces(self, image, params: dict):
        import cv2
        import os

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        cascade_path = os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_default.xml")
        detector = cv2.CascadeClassifier(cascade_path)
        min_size = int(params.get("min_size", 24))
        faces = detector.detectMultiScale(
            gray,
            scaleFactor=float(params.get("scale_factor", 1.1)),
            minNeighbors=int(params.get("min_neighbors", 4)),
            minSize=(min_size, min_size),
        )
        result = []
        for (x, y, w, h) in faces:
            result.append({
                "label": "face",
                "score": 1.0,
                "box": [int(x), int(y), int(x + w), int(y + h)],
            })
        return result

    def _detect_foreground(self, image, params: dict):
        import cv2
        import numpy as np

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        blur = cv2.GaussianBlur(gray, (5, 5), 0)
        _, binary = cv2.threshold(blur, int(params.get("threshold", 245)), 255, cv2.THRESH_BINARY_INV)
        kernel = np.ones((3, 3), np.uint8)
        binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        min_area = float(params.get("min_area", 30))
        result = []
        for contour in contours:
            area = cv2.contourArea(contour)
            if area < min_area:
                continue
            x, y, w, h = cv2.boundingRect(contour)
            result.append({
                "label": "object",
                "score": min(1.0, max(0.01, area / float(image.shape[0] * image.shape[1]))),
                "box": [int(x), int(y), int(x + w), int(y + h)],
            })
        return sorted(result, key=lambda item: item["score"], reverse=True)

    def _detect_text_regions(self, image, params: dict):
        import cv2
        import numpy as np

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        grad = cv2.morphologyEx(gray, cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8))
        _, binary = cv2.threshold(grad, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 3))
        connected = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)
        contours, _ = cv2.findContours(connected, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        min_area = float(params.get("min_area", 40))
        result = []
        for contour in contours:
            area = cv2.contourArea(contour)
            if area < min_area:
                continue
            x, y, w, h = cv2.boundingRect(contour)
            result.append({
                "text": "",
                "score": 0.0,
                "box": [[int(x), int(y)], [int(x + w), int(y)], [int(x + w), int(y + h)], [int(x), int(y + h)]],
            })
        return sorted(result, key=lambda item: item["box"][0][1])


class LocalVisionUnderstandingRunner(BaseRunner):
    """OpenCV/Pillow 本地图像理解摘要，用于 facade-vlm 默认低资源路径。"""

    def run(self, inputs: dict, params: dict):
        import base64
        import io
        from PIL import Image, ImageStat

        image_b64 = inputs.get("image", "")
        if not image_b64:
            raise ValueError("缺少 image 输入")
        image = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")
        stat = ImageStat.Stat(image.resize((32, 32)))
        mean = [int(v) for v in stat.mean]
        width, height = image.size
        dominant = self._color_name(mean)
        prompt = (inputs.get("text") or inputs.get("prompt") or "").strip()
        text = f"图像尺寸 {width}x{height}，平均颜色 RGB({mean[0]},{mean[1]},{mean[2]})，整体偏{dominant}。"
        if prompt:
            text = f"{text} 提问：{prompt}"
        return {"text": text, "width": width, "height": height, "mean_rgb": mean, "dominant_color": dominant}

    def _color_name(self, rgb):
        r, g, b = rgb
        if max(rgb) - min(rgb) < 25:
            if sum(rgb) / 3 > 210:
                return "白色"
            if sum(rgb) / 3 < 60:
                return "黑色"
            return "灰色"
        if r >= g and r >= b:
            return "红色"
        if g >= r and g >= b:
            return "绿色"
        return "蓝色"


class FaceSwapRunner(BaseRunner):
    """人脸替换 — 使用 insightface inswapper_128 / ReSwapper
    首次运行会自动从 HuggingFace 下载 buffalo_l 检测模型 + 换脸模型。
    """

    def _load(self):
        import os as _os
        # Windows SSL 修复：使用 certifi 证书包
        try:
            import certifi
            _os.environ.setdefault("SSL_CERT_FILE", certifi.where())
            _os.environ.setdefault("REQUESTS_CA_BUNDLE", certifi.where())
        except ImportError:
            pass
        import ssl
        try:
            _create_unverified_https_context = ssl._create_unverified_context
        except AttributeError:
            pass
        else:
            ssl._create_default_https_context = _create_unverified_https_context

        try:
            import insightface
            from insightface.app import FaceAnalysis
            from insightface.model_zoo import get_model as if_get_model
        except ImportError:
            raise ImportError(
                "FaceSwap 需要 insightface + onnxruntime，请运行:\n"
                "  pip install insightface onnxruntime"
            )

        face_dir = self.model_root / "face_swap"
        face_dir.mkdir(parents=True, exist_ok=True)

        # 下载 buffalo_l 检测模型（det_10g + 2dfan4）
        # insightface FaceAnalysis 默认查找 {root}/buffalo_l/
        buffalo_dir = self.model_root / "buffalo_l"
        if not (buffalo_dir / "det_10g.onnx").exists():
            buffalo_dir.mkdir(parents=True, exist_ok=True)
            self._download_buffalo_l(buffalo_dir)

        # 下载 inswapper / reswapper 模型
        swapper_path = face_dir / self._swapper_filename()
        if not swapper_path.exists():
            self._download_swapper(face_dir)

        # 初始化 FaceAnalysis（人脸检测 + 关键点）
        ort_providers = ['CUDAExecutionProvider', 'CPUExecutionProvider'] if self.use_gpu else ['CPUExecutionProvider']
        self._fa = FaceAnalysis(
            name="buffalo_l",
            root=str(self.model_root),
            providers=ort_providers,
        )
        self._fa.prepare(ctx_id=self.device_id if self.device_id >= 0 and self.use_gpu else 0, det_thresh=0.5, det_size=(640, 640))

        # 加载换脸模型
        self._swapper = if_get_model(str(swapper_path), providers=ort_providers)
        self._loaded = True
        log.info(f"[FaceSwap] 加载完成: {self.model_name}")

    def _swapper_filename(self) -> str:
        if "reswapper" in self.model_name or "256" in self.model_name:
            return "reswapper_256.onnx"
        return "inswapper_128.onnx"

    def _download_buffalo_l(self, buffalo_dir: Path):
        import zipfile, requests as _req
        buffalo_dir.mkdir(parents=True, exist_ok=True)
        log.info("[FaceSwap] 下载 buffalo_l 检测模型...")
        _req.packages.urllib3.disable_warnings()
        zip_path = buffalo_dir / "buffalo_l.zip"
        # 从 HuggingFace dataset 或 GitHub release 下载
        url = "https://huggingface.co/datasets/Gourieff/ReActor/resolve/main/models/buffalo_l.zip"
        r = _req.get(url, verify=False, timeout=300)
        if r.status_code != 200:
            # 回退到 insightface GitHub release
            url = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"
            r = _req.get(url, verify=False, timeout=300)
        r.raise_for_status()
        with open(zip_path, 'wb') as f:
            f.write(r.content)
        with zipfile.ZipFile(zip_path, 'r') as zf:
            zf.extractall(str(buffalo_dir))
        zip_path.unlink()
        log.info("[FaceSwap] buffalo_l 解压完成 → %s", buffalo_dir)

    def _download_swapper(self, face_dir: Path):
        import requests as _req
        _req.packages.urllib3.disable_warnings()
        fname = self._swapper_filename()
        log.info(f"[FaceSwap] 下载 {fname} ...")

        if "reswapper" in fname:
            url = "https://huggingface.co/somanchiu/ReSwapper/resolve/main/reswapper_256-1567500_originalInswapperClassCompatible.onnx"
        else:
            url = "https://huggingface.co/datasets/Gourieff/ReActor/resolve/main/models/inswapper_128.onnx"

        r = _req.get(url, verify=False, timeout=600)
        r.raise_for_status()
        with open(str(face_dir / fname), 'wb') as f:
            f.write(r.content)
        log.info(f"[FaceSwap] {fname} 下载完成")

    def run(self, inputs: dict, params: dict) -> str:
        if not self._loaded:
            self._apply_gpu(params)
            self._load()

        import base64, cv2, numpy as np

        target_b64 = inputs.get("target_image", "")
        source_b64 = inputs.get("source_image", "")
        if not target_b64 or not source_b64:
            raise ValueError("需要 target_image（目标图）和 source_image（源人脸图）")

        target_img = self._decode_b64(target_b64)
        source_img = self._decode_b64(source_b64)

        source_faces = self._fa.get(source_img)
        target_faces = self._fa.get(target_img)

        if len(source_faces) == 0:
            raise ValueError("源图中未检测到人脸")
        if len(target_faces) == 0:
            raise ValueError("目标图中未检测到人脸")

        source_face = source_faces[0]
        target_idx = params.get("target_face_index", 0)
        if target_idx >= len(target_faces):
            target_idx = 0
        target_face = target_faces[target_idx]

        result_img = self._swapper.get(target_img, target_face, source_face, paste_back=True)

        _, buf = cv2.imencode('.png', result_img)
        return base64.b64encode(buf).decode()

    @staticmethod
    def _decode_b64(b64_str: str) -> np.ndarray:
        import base64, cv2, numpy as np
        img_bytes = base64.b64decode(b64_str)
        arr = np.frombuffer(img_bytes, np.uint8)
        return cv2.imdecode(arr, cv2.IMREAD_COLOR)


class MusicGenRunner(BaseRunner):
    """文字 + 音乐模板 + 人声 → 完整歌曲

    流水线: MusicGen(伴奏) + GPT-SoVITS(人声) → numpy 混音 → WAV
    GPT-SoVITS 完全开源免费，本地部署后设置环境变量即可。
    """

    GPT_SOVITS_API = os.environ.get("GPT_SOVITS_API", "http://127.0.0.1:9880")

    def run(self, inputs: dict, params: dict) -> str:
        style = params.get("style", "pop")
        duration = int(params.get("duration", 30))
        sample_rate = int(params.get("sample_rate", 32000))
        lyrics = inputs.get("prompt", "")
        ref_voice = params.get("ref_voice", "")

        if not self._loaded:
            self._apply_gpu(params)
            self._load()
        accompaniment = self._gen_accompaniment(style, duration, sample_rate)

        # 2) 人声合成
        vocals = None
        if lyrics.strip():
            try:
                vocals = self._gen_vocals(lyrics, ref_voice, sample_rate)
            except Exception as e:
                log.warning(f"[MusicGen] GPT-SoVITS 失败: {e}")

        # 3) 混音
        final = self._mix(accompaniment, vocals, sample_rate)
        buf = io.BytesIO()
        sf.write(buf, final, sample_rate, format="WAV")
        return base64.b64encode(buf.getvalue()).decode()

    def _load(self):
        from transformers import AutoProcessor, MusicgenForConditionalGeneration
        import torch

        model_dir = self._auto_download()
        device = self._device_str()
        dtype = torch.float16 if self.use_gpu else torch.float32
        self._processor = AutoProcessor.from_pretrained(model_dir)
        self._model = MusicgenForConditionalGeneration.from_pretrained(
            model_dir, torch_dtype=dtype
        ).to(device)
        self._model.eval()
        self._loaded = True

    def _gen_accompaniment(self, style: str, duration: float, sample_rate: int) -> np.ndarray:
        import torch

        prompt = f"{style} style instrumental background music, no vocals"
        if style in ("pop", "rock", "jazz", "folk", "electronic", "classical"):
            prompt = f"{style} style instrumental music, clear rhythm, no singing"

        inputs = self._processor(text=[prompt], return_tensors="pt")
        if self.use_gpu:
            inputs = {k: v.to(self._device_str()) for k, v in inputs.items()}
        max_tokens = min(int(duration * 50 / 3), 1500)

        pad_token_id = self._model.config.pad_token_id or 0
        attention_mask = inputs.get("attention_mask")
        if attention_mask is None:
            seq_len = inputs["input_ids"].shape[1]
            attention_mask = torch.ones((1, seq_len), dtype=torch.long)

        with torch.no_grad():
            audio_values = self._model.generate(
                **inputs,
                attention_mask=attention_mask,
                max_new_tokens=max_tokens,
                pad_token_id=pad_token_id,
                do_sample=True,
                temperature=0.8,
                top_p=0.95,
            )

        audio = audio_values[0].cpu().numpy()
        if audio.ndim == 2 and audio.shape[0] == 1:
            audio = audio[0, :]

        if self._model.config.audio_encoder.sampling_rate != sample_rate:
            ratio = sample_rate / self._model.config.audio_encoder.sampling_rate
            audio = scipy.signal.resample(audio, int(len(audio) * ratio))

        return audio.astype(np.float32)

    def _gen_vocals(self, lyrics: str, ref_voice: str, sample_rate: int) -> Optional[np.ndarray]:
        payload = {"text": lyrics, "text_language": "zh"}
        if ref_voice:
            payload["ref_audio_path"] = ref_voice

        resp = requests.post(f"{self.GPT_SOVITS_API}/tts", json=payload, timeout=120)
        resp.raise_for_status()
        result = resp.json()
        audio_b64 = result.get("data", {}).get("audio") or result.get("audio")
        if not audio_b64:
            return None

        vocals, sr = sf.read(io.BytesIO(base64.b64decode(audio_b64)))
        if sr != sample_rate:
            ratio = sample_rate / sr
            vocals = scipy.signal.resample(vocals, int(len(vocals) * ratio))
        return vocals.astype(np.float32)

    def _mix(self, accompaniment: np.ndarray, vocals: Optional[np.ndarray],
             sample_rate: int) -> np.ndarray:
        if vocals is None:
            return accompaniment

        acc = accompaniment.copy().astype(np.float32)
        voc = vocals.copy().astype(np.float32)

        min_len = min(len(acc), len(voc))
        acc, voc = acc[:min_len], voc[:min_len]

        a_max, v_max = np.abs(acc).max(), np.abs(voc).max()
        if a_max > 0:
            acc /= a_max
        if v_max > 0:
            voc /= v_max

        # 人声 -5dB ≈ 0.56
        mixed = acc * 0.9 + voc * 0.56
        m_max = np.abs(mixed).max()
        if m_max > 1.0:
            mixed /= m_max * 1.05


class OpenAIVlmRunner(BaseRunner):
    """GPT-4o 在线 VLM — 通过 OpenAI API 进行图文理解。

    需要:
      - OPENAI_API_KEY 环境变量（或 params.api_key）
      - 互联网连通性（访问 api.openai.com）
    支持文本 + 图片（vision）输入，不走本地模型。
    """

    def __init__(self, model_name: str, model_type: str, model_root: Path, entry: dict):
        super().__init__(model_name, model_type, model_root, entry)
        self._api_key = ""

    def run(self, inputs: dict, params: dict) -> str:
        self._api_key = os.environ.get("OPENAI_API_KEY") or params.get("api_key", "")
        if not self._api_key:
            raise ValueError(
                "GPT-4o 需要 OPENAI_API_KEY 环境变量，"
                "或在 params 中设置 api_key"
            )

        prompt = inputs.get("text", inputs.get("prompt", ""))
        image_b64 = inputs.get("image", "")

        # ── 构建 OpenAI Chat Completions 请求体 ──
        messages = [{"role": "user", "content": []}]

        if prompt:
            messages[0]["content"].append({"type": "text", "text": prompt})

        if image_b64:
            messages[0]["content"].append({
                "type": "image_url",
                "image_url": {
                    "url": f"data:image/jpeg;base64,{image_b64}",
                    "detail": params.get("detail", "auto"),
                }
            })

        if not messages[0]["content"]:
            raise ValueError("输入必须包含 text 或 image 字段")

        body = {
            "model": params.get("model", "gpt-4o"),
            "messages": messages,
            "max_tokens": int(params.get("max_tokens", 512)),
            "temperature": float(params.get("temperature", 0.1)),
        }

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        endpoint = params.get("endpoint", "https://api.openai.com/v1/chat/completions")

        log.info("[OpenAI] GPT-4o 推理: prompt_len=%d, image=%s",
                 len(prompt), "yes" if image_b64 else "no")

        resp = requests.post(endpoint, json=body, headers=headers, timeout=120.0)
        resp.raise_for_status()
        data = resp.json()

        return data["choices"][0]["message"]["content"]


