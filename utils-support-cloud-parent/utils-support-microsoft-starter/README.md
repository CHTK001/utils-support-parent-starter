# utils-support-microsoft-starter

> 微软云集成模块：Azure OpenAI 大模型对话与 DALL·E 图片生成的 SPI 实现。本模块是 `utils-support-common-starter` 中 `ChatClient` / `ImageClient` 契约的**微软侧实现**，通过 SPI 名称 `microsoft`（别名 `azure`）装配。
>
> 版本：`4.0.0.51`｜JDK：`25`（仓库根 POM `java.version=25`）｜包名：`com.chua.microsoft.support`（对话）、`com.chua.microsoft.support.image`（图片）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-microsoft-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 对话 | `MicrosoftChatClient`：基于 Azure OpenAI Service API 的 `ChatClient` HTTP 实现，兼容 OpenAI API 格式 | 不是对话契约本身（`ChatClient` 接口在 `utils-support-common-starter`） |
| 图片 | `MicrosoftImageClient`：基于 Azure OpenAI Images generation API 的 `ImageClient` HTTP 实现，调用 DALL·E 3 / DALL·E 2 | 不是图片契约本身（`ImageClient` 接口在 `utils-support-common-starter`） |
| 装配 | 通过 `META-INF/extensions` 以 SPI 名称 `microsoft` / `azure` 注册 | 不是 Spring Boot Starter：无自动配置、无 `application.yml` 配置键 |
| 依赖 | 只依赖 `utils-support-common-starter`、`microsoft-graph`、`slf4j-api`、`lombok` | 不依赖 Spring、不依赖数据库、不持有任何业务表 |

两个实现类均标注 `@Spi({"microsoft", "azure"})`，`@since 4.0.0.42`，作者 `CH`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| Azure OpenAI 对话 | `ChatClient.create("microsoft", appKey)` | 按 SPI 名 `microsoft` 装配 `MicrosoftChatClient` |
| 指定资源地址 | `ChatClient.create("microsoft", appKey, baseUrl)` | Azure 资源地址形如 `https://<resource>.openai.azure.com`；不传时用默认 `https://api.openai.azure.com/v1` |
| 发起对话（回调） | `client.chat(prompt, consumer)` | 收到 `START` → `STOP`（`content`/`fullContent` 为响应体）/`ERROR` 状态 |
| 设置模型与参数 | `.model(...)` `.temperature(...)` `.maxTokens(...)` `.system(...)` | 未设置时默认 `gpt-4`、`0.3`、`2048` |
| 深度思考 / 联网搜索 | `.thinking(true)` `.smartSearch(true)` | 分别向请求体追加 `thinking:true` 与 `enable_search:true` |
| 多轮历史 | `.addUserHistory(...)` `.addAssistantHistory(...)` `.history(List<ChatMessage>)` `.newChat()` | 外部历史优先于内部累积历史 |
| 用量统计 | `ChatResponse` 携带 `AiUsage` | 含 `inputTokens` / `outputTokens` / `totalTokens`、`provider="microsoft"`、耗时 |
| DALL·E 图片生成 | `ImageClient.create("microsoft", appKey).prompt(...).generate(...)` | 同步生成，返回 `java.awt.image.BufferedImage` |
| 模型指标 | `client.indicator()` | 优先用 `metricsUrl`，未配置时回退免 Key 的 Models.dev 聚合端点（按 `azure` 厂商过滤，见类 Javadoc） |
| 代理 | `ChatClientSetting.proxy` | 形如 `host:port` 或 `scheme://host:port` |

### 2.2 MicrosoftChatClient

`com.chua.microsoft.support.MicrosoftChatClient implements ChatClient`，构造参数为 `ChatClientSetting`。

| 方法 | 行为 |
| --- | --- |
| `model(String)` / `temperature(double)` / `maxTokens(int)` / `system(String)` | 覆盖默认参数 |
| `thinking(boolean)` / `thinkingEffort(String)` / `smartSearch(boolean)` | 深度思考与搜索开关；`thinkingEffort`、`skill(SkillManager)`（经 `SkillPrompt.inject` 注入系统提示词）已支持链式设置 |
| `addImage(String)` | 记录图片附件 URL |
| `addUserHistory(String)` / `addAssistantHistory(String)` / `history(List<ChatMessage>)` | 维护会话历史；`history(...)` 设置外部完整历史 |
| `session(String)` / `newChat()` | 会话标识 / 清空历史与图片 |
| `chatSync(String)` | 按 `ChatClient` 契约聚合 `STREAMING` 片段并返回字符串。**注意**：当前 `chat(...)` 实现只发出 `START` / `STOP` / `ERROR` 状态、不发出 `STREAMING`，因此 `chatSync` 返回值为空字符串；取回内容请用 `chat(prompt, consumer)` 读取 `STOP` 状态的 `content` / `fullContent` |
| `chat(String, Consumer<ChatResponse>)` | 同步 POST `{baseUrl}/chat/completions`（`api-key` 请求头，连接超时 30s，读取超时 90s）；HTTP 200 → `STOP` 状态（`content`/`fullContent` 为原始 JSON 响应体，附 `AiUsage`）；非 200 → `ERROR` 状态（含状态码与响应体）；抛异常时先发 `ERROR` 再回调 `onError`（两参重载会抛 `RuntimeException`） |
| `addAttachment(...)` / `addAttachmentUrl(...)` | 抛 `UnsupportedOperationException`：该服务商不支持文件附件 |
| `indicator()` / `getSetting()` | 模型指标 / 返回构造时传入的 `ChatClientSetting` |

### 2.3 MicrosoftImageClient

`com.chua.microsoft.support.image.MicrosoftImageClient implements ImageClient`，构造参数为 `ImageClientSetting`。

| 方法 | 行为 |
| --- | --- |
| `model(String)` | 默认 `dall-e-3` |
| `size(int width, int height)` | 默认 `1024x1024` |
| `prompt(String)` / `negativePrompt(String)` / `quality(String)` / `style(String)` / `seed(Long)` / `steps(Integer)` | 链式参数。当前请求体只使用 `model`、`prompt`、`n:1`、`size`、`quality`、`style`、`response_format:"url"`；`negativePrompt`、`seed`、`steps` 暂未参与请求体构建 |
| `generate(String prompt)` | 同步 POST `{baseUrl}/openai/images/generations?api-version=2024-02-01`（`api-key` 请求头，连接超时 30s，读取超时 120s）；非 200 抛 `RuntimeException`；成功后解析 `data[0].url` 并下载图片，返回 `BufferedImage` |
| `referenceImage(...)` / `imageStrength(double)` / `controlType(String)` | 抛 `UnsupportedOperationException`：不支持参考图 / ControlNet |
| `createTask(String)` / `queryTask(String)` | 抛 `UnsupportedOperationException`：不支持异步任务模式，请用 `generate(...)` 同步生成 |
| `models()` | 抛 `UnsupportedOperationException`：不支持模型列表查询 |
| `close()` | 空实现 |

---

## 3. 快速开始

### 3.1 对话

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;

// SPI 名：microsoft（或 azure）；生产环境建议用三参重载指定 Azure 资源地址
ChatClient client = ChatClient.create("microsoft", "你的AzureOpenAIKey",
        "https://你的资源名.openai.azure.com/v1");

client.model("gpt-4")
      .system("你是一名严谨的工程师")
      .temperature(0.3)
      .maxTokens(2048);

client.chat("解释一下什么是 WAL", resp -> {
    switch (resp.getState()) {
        case START -> System.out.println("[start]");
        case STOP -> System.out.println(resp.getFullContent()); // 原始 JSON 响应体
        case ERROR -> System.err.println(resp.getErrorMessage());
        default -> { }
    }
});
```

> `ChatClient.create(String provider, String apiKey)` 与 `create(String provider, String apiKey, String baseUrl)` 是 `ChatClient` 接口提供的静态工厂；本模块经 `META-INF/extensions` 以 `microsoft` 名称被装配。

### 3.2 图片生成

```java
import com.chua.common.support.ai.image.ImageClient;
import java.awt.image.BufferedImage;

ImageClient imageClient = ImageClient.create("microsoft", "你的AzureOpenAIKey",
        "https://你的资源名.openai.azure.com");

BufferedImage image = imageClient
        .model("dall-e-3")
        .size(1024, 1024)
        .quality("standard")     // 可选
        .style("vivid")          // 可选
        .generate("一只在草庐前看书的橘猫，水墨画风格");
```

### 3.3 编译验证

```bash
mvn -o -q compile -DskipTests
```

---

## 4. 配置项

本模块**没有** Spring Boot 自动配置，也没有 `application.yml` / `application.properties` 配置键（模块 POM 不含 Spring 依赖）。所有配置通过构造参数 `ChatClientSetting` / `ImageClientSetting`（定义于 `utils-support-common-starter`）以代码方式传入。

### 4.1 ChatClientSetting 中本模块实际读取的字段

| 字段 | 作用 | 未设置时的默认行为 |
| --- | --- | --- |
| `appKey` | Azure OpenAI Key，作为 `api-key` 请求头发送 | 必填，否则请求失败 |
| `baseUrl` | API 基础地址（末尾 `/` 会被去除） | `https://api.openai.azure.com/v1` |
| `model` | 模型名 | `gpt-4` |
| `temperature` | 温度 | `0.3` |
| `maxTokens` | 最大输出令牌 | `2048` |
| `system` | 系统提示词 | 不追加 system 消息 |
| `proxy` | 代理，`host:port` 或 `scheme://host:port` | 不走代理 |
| `metricsUrl` | 模型指标端点（供 `indicator()` 使用） | 回退 Models.dev 免 Key 端点（按 `azure` 过滤） |

### 4.2 ImageClientSetting 中本模块实际读取的字段

| 字段 | 作用 | 未设置时的默认行为 |
| --- | --- | --- |
| `appKey` | Azure OpenAI Key，作为 `api-key` 请求头发送 | 必填 |
| `baseUrl` | API 基础地址（末尾 `/` 会被去除） | `https://api.openai.azure.com` |
| `model` | 模型名 | `dall-e-3` |
| `width` / `height` | 图片尺寸 | `1024 x 1024` |

> 图片的 `quality` / `style` / `seed` / `steps` / `negativePrompt` 不从 Setting 读取，仅通过链式方法设置（其中 `quality`、`style` 会进入请求体，其余当前不生效）。

### 4.3 系统属性与环境变量

无。源码未读取任何 `System.getProperty` / 环境变量配置。

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，不定义新扩展点。登记文件位于 `src/main/resources/META-INF/extensions/`：

| 登记文件（接口全限定名） | 内容 |
| --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `microsoft=com.chua.microsoft.support.MicrosoftChatClient` |
| `com.chua.common.support.ai.image.ImageClient` | `microsoft=com.chua.microsoft.support.image.MicrosoftImageClient`<br>`azure=com.chua.microsoft.support.image.MicrosoftImageClient` |

说明：

- 两个实现类的 `@Spi` 注解都声明了 `{"microsoft", "azure"}` 两个别名；但 `ChatClient` 的登记文件当前只登记了 `microsoft` 一个名称，`ImageClient` 登记了 `microsoft` 与 `azure` 两个名称。
- 为支持 GraalVM native-image，`src/main/resources/META-INF/native-image/com.chua/utils-support-microsoft-starter/` 下提供：
  - `reflect-config.json`：对 `MicrosoftChatClient` 与 `MicrosoftImageClient` 开放全部构造器、方法、字段；
  - `resource-config.json`：包含 `META-INF/extensions/.*` 资源。

---

## 6. 构建与依赖

### 6.1 依赖树

```
utils-support-microsoft-starter（parent: utils-support-cloud-parent 4.0.0.51）
├── com.chua:utils-support-common-starter        （契约层：ChatClient / ImageClient / Settings / HttpClientFactory / Spi 等）
├── com.microsoft.graph:microsoft-graph          （版本由父 POM 依赖管理，模块 POM 未写死版本号）
├── org.slf4j:slf4j-api
└── org.projectlombok:lombok（provided）
```

### 6.2 构建

```bash
# 在仓库根目录 utils-support-parent-starter 下
mvn -o -q compile -DskipTests

# 仅构建本模块
mvn -o -q -pl utils-support-cloud-parent/utils-support-microsoft-starter -am compile -DskipTests
```

JDK 要求：Java 25（仓库根 POM `maven.compiler.source=25`、`maven.compiler.target=25`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| `ChatClient` / `ImageClient` 接口、`ChatClientSetting` / `ImageClientSetting`、SPI 装配机制 | `utils-support-common-starter` |
| HTTP 底层（连接池、执行器切换） | `utils-support-common-starter` 的 `HttpClientFactory`（执行器 SPI：okhttp / httpclient5 / jdk） |
| Microsoft Graph 业务调用（邮件、日历、OneDrive 等） | 本模块 POM 虽声明了 `microsoft-graph` 依赖，但当前源码中没有使用该 SDK 的实现类 |
| 文件附件对话、参考图 / ControlNet、异步图片任务、图片模型列表查询 | Azure OpenAI 侧不支持，相关方法直接抛 `UnsupportedOperationException` |
| 其它云厂商（OpenAI 原生、Claude、Gemini、通义、智谱等） | 由 `utils-support-cloud-parent` 下对应 `*-starter` 模块提供 |
