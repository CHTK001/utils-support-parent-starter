# utils-support-mosi-starter

MOSS 开放平台（mosi.cn）大模型集成模块，提供文生图（Image 图像生成）与语音合成（TTS）能力。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-mosi-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 使用

```java
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.audio.TextToAudioClient;
import java.awt.image.BufferedImage;

// 文生图：通过 SPI 门面获取客户端，provider 可用 "mosi" 或 "moss"
try (ImageClient client = ImageClient.create("mosi", "$MOSS_API_KEY")) {
    client.model("moss-image-base-1.0")
          .size(1024, 1024)
          .prompt("一只橘猫坐在窗边，窗外是雨后的花园，柔和的自然光")
          .negativePrompt("nsfw, worst quality, bad anatomy, watermark, jpeg artifact")
          .seed(42L)
          .steps(30);

    BufferedImage image = client.generate();
    // 或一次性传参：client.generate("一只可爱的猫")
}

// 语音合成：音色必须填上游的 voice id，不是 alloy 这类占位名
byte[] mp3 = TextToAudioClient.create("moss", "$MOSS_API_KEY")
        .model("moss-tts-1.5-flash")
        .voice("94aa4989-c7e9-5007-ae42-ab401823e6c9")
        .format("mp3")
        .synthesize("欢迎使用 Moss API。[pause 0.8s]现在开始生成语音。");
```

> 出图可能耗时较长（官方建议请求超时不低于 900 秒），默认请求超时设置为 900 秒。

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MossImageClient` | MOSS 平台文生图客户端，基于 `POST https://api.mosi.cn/v1/images/generations` 的 `ImageClient` 实现，支持 `moss-image-base-1.0` / `moss-image-sft-1.0` 模型，支持 `negative_prompt`、`prompt_enhance`、`seed`、`num_inference_steps`、`guidance_scale`、`output_watermark` 参数。 |
| `MossTextToAudioClient` | MOSS 平台语音合成客户端，基于 `POST https://api.mosi.cn/v1/audio/speech` 的 `TextToAudioClient` 实现，支持 `moss-tts-1.5-flash` / `moss-tts-1.0-pro`。音色字段为 `voice_id`，支持 `[pause 0.8s]` 停顿标记与 `delivery_method=audio\|url`，异步走 `async=true` + `GET /v1/audio/tasks/{id}`。 |

SPI 注册名：

| 接口 | 名称 |
|------|------|
| `com.chua.common.support.ai.image.ImageClient` | `mosi`、`moss` |
| `com.chua.common.support.ai.audio.TextToAudioClient` | `mosi`、`moss` |

> **注意：TTS 侧刻意<b>不</b>注册 `moss-tts`。** 该名字属于
> `utils-support-deeplearning-onnx-starter` 中的本地 MOSS-TTS-Nano 模型
> （`com.chua.deeplearning.support.onnx.audio.moss.MossTextToAudioClient`），
> 跑的是 ONNX 权重、无需密钥；SPI 注册表对同名定义是累加而非覆盖，
> 重复注册会让 `ServiceProvider.getNewExtension` 取到不可预期的实现。

---

## 配置说明

本模块为零配置模块，引入依赖后即可通过 SPI 使用。API Key 通过 `ImageClient.create(provider, apiKey)` /
`TextToAudioClient.create(provider, apiKey)` 传入。

`baseUrl` 可通过 `ImageClientSetting.baseUrl` / `TextToAudioClientSetting.baseUrl` 覆盖。
两个客户端对 `/v1` 的处理不同，请注意：

- **文生图**：填 `https://api.mosi.cn` 或 `https://api.mosi.cn/v1` 都可以，客户端会自行剥掉结尾的 `/v1`
  再拼接 `/v1/images/generations`（实现里路径自带 `/v1`）。
- **语音合成**：请填 `https://api.mosi.cn/v1`，客户端拼接的是 `/audio/speech`（不含 `/v1`）。

---

## 依赖关系

```
utils-support-mosi-starter
└── utils-support-common-starter
```
