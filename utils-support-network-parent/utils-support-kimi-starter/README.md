# utils-support-kimi-starter

> 把 Kimi 网页版（`https://www.kimi.com`）的 connect-rpc 会话协议封装成统一的 `ChatClient`：无需浏览器、无需官方开放平台 API Key，凭网页版 access token / refresh token 即可对话，支持思考链、联网搜索、多轮上下文与图片/视频链接提取。
>
> 版本：`4.0.0.51`（随父 POM `utils-support-network-parent`，核心类自 `4.0.0.42` 引入）｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.kimi.support`

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

模块自述（POM `description`）：**Kimi 网页版逆代：Cookie/Token 免费调用 chat.kimi.com 会话能力**。

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-kimi-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 对话能力 | Kimi 网页版 `ChatService/Chat` 的 connect-rpc 直连客户端，实现统一 `com.chua.common.support.ai.chat.ChatClient` SPI（名称 `kimi-proxy`） | 不是 Kimi 官方开放平台 API SDK |
| 令牌 | `appKey` 即 token：JWT access token 直接使用；refresh token 自动换取并缓存 | 不做登录、扫码、验证码等账号流程，token 需自行获取后传入 |
| 依赖 | 只依赖 `utils-support-common-starter`；HTTP 走统一 `HttpClientFactory`（OkHttp/HttpClient5/JDK 自动选择） | 不是 Spring Boot starter，无自动配置类与配置属性 |
| 生成能力 | `generateImage` / `generateVideo` 以对话方式让模型给出媒体链接并从回答文本中提取 | 不提供官方图片/视频生成接口的参数级控制 |
| 运行形态 | 纯 HTTP 调用，不启动浏览器 | 不含浏览器自动化（相关能力在 webview/jvppeteer/playwright/cdp 等 starter） |

---

## 2. 核心 API 与速查表

### 2.1 类型总览

| 类型 | 职责 |
| --- | --- |
| `KimiChatClient` | `ChatClient` SPI 实现（`@Spi("kimi-proxy")`），负责链式参数收集、请求编排与响应解析 |
| `KimiSession` | 会话客户端：令牌管理（JWT/refresh 自动刷新）、设备指纹请求头、底层 HTTP 调用，实现 `AutoCloseable` |
| `KimiProtocol` | 协议常量与编解码工具（connect 帧编码、JWT 解析、设备/会话 ID 生成），final 工具类 |
| `KimiChatResult` | 单次对话解析结果 `record(text, thinkingContent, remoteChatId, lastAssistantMessageId, errorMessage)`，`isSuccess()` 判定成败 |

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `ChatClient.create(ChatClientSetting)`，`provider("kimi-proxy")` | `appKey` 填 token 串；也可用 `ChatClient.create("kimi-proxy", token)` |
| 同步对话 | `chatSync(String)` | 返回完整回答文本 |
| 流式/回调对话 | `chat(String, Consumer<ChatResponse>)` | 依次回调 `START` / `STREAMING` / `STOP`，失败回调 `ERROR` |
| 带完成/失败回调 | `chat(prompt, consumer, onComplete, onError)` | 失败时默认 `chat` 会抛 `RuntimeException`，此重载交给 `onError` |
| 多轮上下文 | `session(id)` / `newChat()` / `addUserHistory` / `addAssistantHistory` / `history(List<ChatMessage>)` | 客户端内部用 `chat_id` + `parent_id` 自动续接；`newChat()` 清空上下文 |
| 思考模式 | `thinking(true)` | 思考链内容通过 `ChatResponse.getReasoningContent()` 返回 |
| 联网搜索 | `smartSearch(true)` | 请求工具列表注入 `TOOL_TYPE_SEARCH` |
| 图片/视频生成 | `generateImage(...)` / `generateVideo(...)` | 对话获取回答后从文本提取图片（最多 10 张）或 mp4/webm/ogg 链接 |
| 令牌自动刷新 | `KimiSession.getAccessToken()` | refresh token 在过期前 5 分钟（300 秒）自动换取新 access token |
| 协议编解码 | `KimiProtocol.encodeConnectRequest(JsonObject)` | 5 字节帧头（1 字节标志 + 4 字节大端长度）+ JSON |

### 2.3 工作原理（与源码注释一致）

1. **协议**：请求体编码为 `5 字节 connect 帧头 + JSON`（首字节标志位 `0x00`，后 4 字节大端长度），以 `Content-Type: application/connect+json` POST 到 `/apiv2/kimi.gateway.chat.v1.ChatService/Chat`；响应为连续 gRPC 帧流（同样 5 字节帧头，首字节最高位为 1 表示压缩/跳过），逐帧解析 delta 得到回答/思考内容。
2. **令牌**：`appKey` 若是 JWT access token（`eyJ` 开头且 payload 满足 `app_id=kimi`、`typ=access`）则直接使用；否则视为 refresh token，通过 `/api/auth/token/refresh` 换取短期 access token 并缓存，过期前 300 秒自动刷新（`ReentrantLock` 保证并发安全）。
3. **多轮上下文**：从响应中提取 `chat.id` 与最后一条 assistant 消息 `id`，下次请求分别作为 `chat_id` 与 `parent_id` 携带；同一 `KimiChatClient` 实例连续调用即自动续接，`newChat()` 重置。
4. **消息格式**：历史与系统提示词按 `system:` / `user:` / `assistant:` 逐行拼接成纯文本，当前问题以 `user:{prompt}` 结尾。
5. **请求头指纹**：携带 `X-Msh-Device-Id` / `X-Msh-Session-Id`（各 16 位随机数字）、`X-Msh-Platform: web`、`R-Timezone: Asia/Shanghai` 与 Chrome 131 User-Agent，模拟网页端调用。
6. **超时**：连接超时 30 秒、读取超时 120 秒。

---

## 3. 快速开始

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;

// 1. 创建客户端：appKey = Kimi 网页版 token（JWT access token 或 refresh token）
ChatClient client = ChatClient.create(ChatClientSetting.builder()
        .provider("kimi-proxy")
        .appKey("<你的-token>")
        .build());

// 2. 同步对话
String answer = client.chatSync("用一句话介绍你自己");
System.out.println(answer);

// 3. 流式对话 + 思考链
client.thinking(true).chat("9.11 和 9.9 谁大", event -> {
    switch (event.getState()) {
        case STREAMING -> System.out.print(event.getContent());
        case STOP -> System.out.println("\n[完成] 思考内容: " + event.getReasoningContent());
        case ERROR -> System.err.println(event.getErrorMessage());
    }
});

// 4. 多轮对话：同一实例连续调用自动续接上下文；开新会话调用 newChat()
client.newChat();

// 5. 释放资源
client.close();
```

> 说明：`chat(...)` 的 `STREAMING` 回调在完整响应帧流解析完成后触发一次（携带全部文本），并非逐 token 推送；`chatSync(...)` 即聚合该文本返回。

---

## 4. 配置项

### 4.1 `ChatClientSetting` 字段（构造 `KimiChatClient` 时消费）

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `provider` | 是 | — | 固定 `kimi-proxy`（SPI 名称） |
| `appKey` | 是 | — | token 串：JWT access token 或 refresh token；未配置时刷新令牌抛 `IllegalStateException("Kimi token 未配置")` |
| `baseUrl` | 否 | `https://www.kimi.com`（`KimiProtocol.BASE_URL`） | 反代/镜像地址；末尾多余 `/` 自动去除 |
| `model` | 否 | `kimi-k2.6`（`DEFAULT_MODEL`） | 仅用于用量上报（`AiUsage.model`），请求载荷不携带模型字段 |
| `system` | 否 | — | 系统提示词，以 `system:` 行拼入消息文本 |
| `temperature` / `maxTokens` / `topP` / `stop` / `seed` / `responseFormat` / `extraBody` / `toolChoice` | 否 | — | 链式方法已实现，但**当前请求载荷未消费**（网页版接口不暴露这些采样参数） |
| `tools` / `tool` / `addImage` / `addAttachment` / `addAttachmentUrl` / `skill` | 否 | — | 链式方法已实现并收集，但**当前请求载荷未使用**（仅 `smartSearch` 会注入搜索工具） |

### 4.2 协议常量与超时（源码常量）

| 项 | 值 | 说明 |
| --- | --- | --- |
| `KimiProtocol.BASE_URL` | `https://www.kimi.com` | 默认基础地址 |
| `KimiProtocol.CHAT_PATH` | `/apiv2/kimi.gateway.chat.v1.ChatService/Chat` | 对话接口（connect-rpc） |
| `KimiProtocol.REFRESH_PATH` | `/api/auth/token/refresh` | refresh token 换 access token |
| `KimiProtocol.SUBSCRIPTION_PATH` | `/apiv2/kimi.gateway.order.v1.SubscriptionService/GetSubscription` | 订阅查询路径（仅常量，本模块未封装调用方法） |
| `KimiProtocol.SCENARIO` | `SCENARIO_K2D5` | 默认场景标识 |
| `KimiProtocol.FRAME_HEADER_LENGTH` | `5` | 1 字节标志 + 4 字节大端长度 |
| `KimiProtocol.FLAG_TYPE_KEEP` | `0x00` | 非压缩帧标志 |
| 连接超时 | `30000` ms | `KimiSession.CONNECT_TIMEOUT_MS` |
| 读取超时 | `120000` ms | `KimiSession.READ_TIMEOUT_MS` |
| 令牌刷新提前量 | `300` 秒 | `KimiSession.REFRESH_BUFFER_SECONDS` |

### 4.3 系统属性 / 环境变量 / Spring 配置

- 系统属性与环境变量：**无**（四个源文件均未调用 `System.getProperty` / `System.getenv`）。
- Spring 配置项：**无**（本模块是纯 SPI 工具模块，`src/main/resources` 下仅有 SPI 扩展文件与 native-image 配置，无自动配置类）。

---

## 5. SPI 扩展点

**本模块向 common-starter 的 `ChatClient` SPI 注册实现**，扩展文件：

```text
src/main/resources/META-INF/extensions/com.chua.common.support.ai.chat.ChatClient
kimi-proxy=com.chua.kimi.support.KimiChatClient
```

- 注解：`@Spi("kimi-proxy")` + `@ConditionalOnClass("java.net.http.HttpClient")`（JDK HttpClient 存在才加载）。
- 获取方式：`ServiceProvider.of(ChatClient.class).getNewExtension("kimi-proxy", setting)`；日常推荐直接 `ChatClient.create(setting)`。
- 本模块**不定义新的 SPI 接口**，也没有可插拔的子扩展点；协议常量与编解码工具集中在 `KimiProtocol`。
- GraalVM native-image：`META-INF/native-image/com.chua/utils-support-kimi-starter/` 下的 `reflect-config.json` 对 `KimiChatClient` 做全量反射注册（构造器/方法/字段），另有 `resource-config.json`。

---

## 6. 构建与依赖

```bash
# 在 utils-support-parent-starter 仓库根目录执行
mvn -pl utils-support-network-parent/utils-support-kimi-starter -am clean package
```

| 依赖 | 用途 |
| --- | --- |
| `com.chua:utils-support-common-starter`（同版本） | `ChatClient` 契约与 `ChatClientSetting`、SPI（`ServiceProvider`/`@Spi`）、`HttpClientFactory`、JSON（`JsonObject`/`JsonArray`）、`Wraps`、`StringUtils` |
| `lombok` | 编译期 `@Slf4j`（provided） |

运行期无其它第三方强依赖；HTTP 底层实现由 common-starter 的 `HttpClientExecutor` SPI 决定（classpath 里有 okhttp/httpclient5 则优先，否则回退 JDK）。编译要求 JDK 25（`maven.compiler.release=25`），源码 UTF-8 无 BOM。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| `ChatClient` 接口、`ChatClientSetting`、OpenAI 兼容兜底 | 在 `utils-support-common-starter` |
| 其它厂商协议（openai / claude / zhipu 等） | 在 `utils-support-cloud-parent` 各模块 |
| 官方图片/视频生成 API | 本模块 `generateImage` / `generateVideo` 是「对话 + 从回答文本提取链接」，非官方生成接口 |
| 订阅/套餐查询 | `KimiProtocol.SUBSCRIPTION_PATH` 仅有常量，未封装调用方法 |
| 登录与 token 获取 | 不在本模块；token 需自行从网页版获取后作为 `appKey` 传入 |
| Spring 自动配置与配置属性 | 无；纯 SPI 工具模块 |
| 浏览器自动化 | 不依赖浏览器；相关能力在 webview / jvppeteer / playwright / cdp 等 starter |

**已知限制**：

- 采样类参数（`temperature`、`maxTokens`、`topP`、`stop`、`seed`、`responseFormat`、`extraBody`、`toolChoice`）与 `tools` / `addImage` / `addAttachment*` / `skill` 当前不进入请求载荷，仅 `system`、`thinking`、`smartSearch`、历史消息与多轮上下文实际生效。
- 响应为一次性读取完整帧流后解析，`STREAMING` 回调仅触发一次（携带完整文本）。
- 上游为网页版逆向接口，协议或风控变化可能导致调用失败；HTTP 非 200 或响应含 `error` 时抛出/回调 `RuntimeException`。

---

## 许可证

Apache License 2.0
