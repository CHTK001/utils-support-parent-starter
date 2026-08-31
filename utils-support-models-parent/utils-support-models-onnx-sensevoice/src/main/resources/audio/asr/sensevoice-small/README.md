# SenseVoice-Small ONNX 嵌入式模型副本

本目录保留 SenseVoice-Small ONNX int8 量化模型的占位文件结构：

```
audio/asr/sensevoice-small/
├── model.int8.onnx      # 实际 ONNX 模型（int8 量化，约 228MB）
├── tokens.txt           # 词表（~250 token，CTC 索引）
└── README.md            # 本文件
```

## 来源

- 官方仓库：https://github.com/QwenAudio/SenseVoice
- ModelScope：https://www.modelscope.cn/models/iic/SenseVoiceSmall
- HuggingFace：https://huggingface.co/FunAudioLLM/SenseVoiceSmall

## 双模式装载

1. **嵌入式（默认）**：模型打包进 `utils-support-models-onnx-sensevoice-4.0.0.42.jar`，
   首次使用时由 `NativeLoader` 自动解压到 `%TEMP%/audio/asr/sensevoice-small/`
2. **downloadUrl 模式**：从 `achtk/utils-support-sensevoice-onnx` 自动下载
   （详见 `OnnxModelRegistrar.java` 的 `reg("sensevoice", ...)` 调用）

## 许可

- 模型权重遵循官方 [FunASR 模型协议 v1.1](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)
- 允许商业使用（仅需保留署名 + 模型名）
