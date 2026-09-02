# utils-support-openai-starter

OpenAI 大模型集成模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-openai-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | SPI 名称 | 说明 |
|---------|----------|------|
| `OpenAiChatClient` | `openai` / `siliconflow` / `sensetime` / `github` / `gitee` | OpenAI 大模型对话客户端，基于 OpenAI Java SDK，支持 OpenAI 兼容接口的所有服务商 |
| `OpenAiImageClient` | `openai` 等 | OpenAI 图片生成客户端（DALL-E 系列，`/images/generations`） |
| `OpenAiEmbeddingClient` | `openai` 等 | OpenAI 嵌入向量客户端（`text-embedding-3-small` 等） |
| `OpenAiTextToAudioClient` | `openai` / `openai-tts` / `siliconflow` / `sensetime` / `github` / `gitee` | OpenAI 文字转语音（TTS）客户端，`POST /v1/audio/speech`，支持 tts-1 / tts-1-hd / gpt-4o-mini-tts |
| `OpenAiAudioClient` | `openai` / `openai-asr` / `siliconflow` / `sensetime` / `github` / `gitee` | OpenAI 语音识别（STT/ASR）客户端，`POST /v1/audio/transcriptions`，支持 whisper-1 / gpt-4o-transcribe 等 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-openai-starter
├── utils-support-common-starter
```