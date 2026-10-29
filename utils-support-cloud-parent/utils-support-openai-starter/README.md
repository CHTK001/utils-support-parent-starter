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
| `InceptionChatClient` | `inceptionlabs` / `mercury` | Inception Labs Mercury 对话客户端（`chat.inceptionlabs.ai`），支持浏览器通道与纯 JDK HttpClient 通道 |
| `PteroChatClient` | `ptero` | Ptero.pro 公开对话客户端（`ptero.pro/wp-json/mlp/v1`），OpenAI 兼容，纯 JDK HttpClient 实现；游客可用，无需 API Key |

### Ptero 示例

```java
ChatClient client = ChatClient.create(ChatClientSetting.builder()
        .provider("ptero")
        .model("deepseek-v4.1-flash:free")
        .extraHeaders(Map.of(
                "x-mlp-guest-token", "guest0123456789",
                "x-ptero-mode", "quick"))
        .build());
System.out.println(client.chatSync("你好"));
```

- `appKey` 留空即游客身份；填入后按 `Authorization: Bearer <key>` 发送，游客模式请勿填。
- 模式：`smart`（默认）/ `fast` / `quick` / `complex` / `full`，通过 `x-ptero-mode` 选择。
- 上游虽返回标准 SSE，但长回答通常整段生成后一次性下发，首个内容分片延迟可能等于总耗时。

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-openai-starter
├── utils-support-common-starter
```