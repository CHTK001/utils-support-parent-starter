# SafeTensor 多模型推理服务

通过 Python FastAPI 服务托管多种 AI 模型，Java 端通过 HTTP 调用进行推理。

## 支持的模型类型

| 类型 | model_type | 说明 |
|------|-----------|------|
| LLM 文本生成 | `llm` | Qwen2.5/MiniMind2 等 |
| ASR 语音识别 | `asr` | Whisper/SenseVoice/Paraformer/MiniMind-3o |
| TTS 语音合成 | `tts` | Kokoro ONNX 本地模型 |
| 文生图 | `image_gen` | Stable Diffusion 系列 |
| 文本嵌入 | `text_embedding` | Sentence-Transformers |
| 换装 | `tryon` | Nano-Banana + Qwen-Image LoRA |
| 图像增强 | `image_enhance` | GPEN/人脸融合/卡通化 |
| 图像识别 | `image_recognition` | ResNeSt 分类 |
| 抠图分割 | `matting` | UNet 人像抠图 |
| 目标检测 | `detection` | 车牌/卡片检测 |
| 人脸检测 | `face_detection` | RetinaFace/MogFace |
| OCR | `ocr` | 文档文字识别 |

## 已测试模型性能 (CPU)

### MiniMind-3o (Omni) — ASR + 文本生成

| 任务 | 输入 | 输出 | 耗时 | token/s |
|------|------|------|------|---------|
| ASR 中文 | zh.mp3 语音 | "早上8点5分。" | ~8s | — |
| 中文问答 | "1+1等于几？" | "1+1等于2。" + 解释 | 14.0s | 6.1 |
| 中文问答 | "写一个笑话" | 一句话笑话 | 0.8s | 25.4 |
| 英文问答 | "What is the capital of France?" | "The capital of France is Paris." | ~2s | ~15 |

> 模型路径: `D:/safetensor_models/_hf/models--jingyaogong--minimind-3o/`
> SenseVoice 编码器: `D:/safetensor_models/iic/SenseVoiceSmall/`

### Qwen2.5-0.5B-Instruct — LLM 文本生成

| 任务 | 输入 | 输出 | 耗时 | token/s |
|------|------|------|------|---------|
| 中文介绍 | "你好，请用中文介绍一下自己" | 自我介绍 | 19.2s | 12.0 |
| 英文问答 | "What is machine learning?" | 解释 | 11.6s | 11.1 |
| 英文创作 | "Write a haiku about spring" | 俳句 | 1.6s | 9.9 |
| 翻译 (中→英) | "今天天气真好，我们一起去公园散步吧。" | "Today the weather is really good..." | ~3s | ~15 |

> 模型路径: `D:/safetensor_models/Qwen/Qwen2.5-0.5B-Instruct/`

## Java 端调用

```java
SafeTensorServiceClient client = new SafeTensorServiceClient("127.0.0.1", 8765);

// ASR 语音识别（MiniMind-3o）
Map<String, Object> result = client.infer("minimind-3o", "asr",
    Map.of("audio", Base64.getEncoder().encodeToString(audioBytes)),
    Map.of());

// LLM 文本生成（Qwen2.5）
Map<String, Object> result = client.infer("qwen2.5-0.5b", "llm",
    Map.of("text", "你的问题"),
    Map.of());

// 翻译（同 LLM，直接在 prompt 中指示翻译方向）
Map<String, Object> result = client.infer("qwen2.5-0.5b", "llm",
    Map.of("text", "Translate to English: 今天天气真好"),
    Map.of());
```

## Python 服务启动

```bash
# 便携环境
H:/environment/onnx/python/Scripts/python.exe python_service/safetensor_service.py

# 系统 Python
python python_service/safetensor_service.py --port 8765
```

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| GET | `/models` | 列出已下载模型 |
| POST | `/infer` | 模型推理 |
| POST | `/download` | 下载模型 |

## 前置依赖

```
pip install fastapi uvicorn transformers torch
pip install funasr librosa soundfile       # ASR (SenseVoice/Whisper)
pip install modelscope huggingface-hub    # 模型下载
pip install safetensors                   # SafeTensors 格式支持
```

## 模型注册表

所有可用模型定义在 `python_service/safetensor_models.py` 的 `MODEL_REGISTRY` 中。添加新模型只需在对应类型列表中追加条目：

```python
MODEL_REGISTRY = {
    "asr": [
        {"id": "whisper-large-v3", "ms": "iic/Whisper-large-v3"},
        {"id": "minimind-3o", "hf": "jingyaogong/minimind-3o", "source": "huggingface", "runner": "omni"},
        # 添加新模型...
    ],
}
```

### 模型下载源

- `source: "modelscope"` — 从 ModelScope 下载（默认，国内快速）
- `source: "huggingface"` — 从 HuggingFace 下载（自动使用 HF 镜像）

## 硬件要求

| 场景 | 最低要求 | 推荐配置 |
|------|---------|---------|
| LLM 0.5B (CPU) | 4GB RAM | 8GB RAM |
| ASR (SenseVoice) | 4GB RAM | 8GB RAM |
| 文生图 (SD) | 8GB RAM, 6GB VRAM | 16GB RAM, 8GB+ VRAM |
| GPU 推理 | CUDA 11.8+ | CUDA 12.x + 8GB+ VRAM |
