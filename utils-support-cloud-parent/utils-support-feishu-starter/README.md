# utils-support-feishu-starter

> 飞书开放平台 **Bot 客户端集成模块**：实现 `utils-support-common-starter` 的 `BotClient` SPI 契约（SPI 名 `feishu`），基于官方 `oapi-sdk`，支持**长连接（WebSocket）**与 **Webhook** 两种事件接收模式，覆盖文本/图片发送、群发、@提及、群列表与凭据校验。
>
> 版本：`4.0.0.51`（继承父 pom `utils-support-cloud-parent`）｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.feishu.support.bot`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 模块构成](#13-模块构成)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 FeishuBotClient 方法一览](#22-feishubotclient-方法一览)
- [3. 快速开始](#3-快速开始)
  - [3.1 长连接模式（默认，无需公网地址）](#31-长连接模式默认无需公网地址)
  - [3.2 Builder 方式创建](#32-builder-方式创建)
  - [3.3 Webhook 模式](#33-webhook-模式)
  - [3.4 群发与 @ 提及](#34-群发与--提及)
  - [3.5 凭据校验（不建立长连接）](#35-凭据校验不建立长连接)
- [4. 配置项](#4-配置项)
- [5. 接收行为细节](#5-接收行为细节)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-feishu-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 飞书 Bot 的 `BotClient` SPI 实现（`@Spi("feishu")`），发消息 + 收事件的统一入口 | 不是渠道管理、AI 对话编排等业务模块 |
| 接收 | 长连接（默认，`com.lark.oapi.ws.Client` WebSocket 订阅）与 Webhook（回调体交给 `handleCallback`）双模式 | 不自带 HTTP 服务端/Controller，Webhook 需调用方自行暴露端点 |
| 发送 | 单聊文本/图片、群文本、群 @ 提及（`interactive` 卡片）、群列表 | 不支持语音/视频/文件发送（直接返回失败结果） |
| 形态 | 纯 Java 库，链式 API + SPI 装配 | 不是 Spring Boot starter：无自动配置类、无 `application.yml` 配置键 |

**判断口径**：凡与飞书开放平台协议相关的能力放本模块；跨平台统一的 `BotClient` 契约、消息模型（`BotInboundMessage` / `BotOutboundMessage` / `BotSendResult`）、监听器与用户存储接口都定义在 `utils-support-common-starter`。

### 1.3 模块构成

仅 3 个源文件，包名 `com.chua.feishu.support.bot`：

| 类 | 职责 |
| --- | --- |
| `FeishuBotClient` | 飞书 Bot 客户端，实现 `com.chua.common.support.ai.bot.BotClient`；含长连接/Webhook 接收、消息收发、群操作、凭据校验静态方法 |
| `FeishuBotClientFactory` | `BotClient.Factory` 的 SPI 实现（`@Spi("feishu")`），内含 `FeishuBuilder` 构建器 |
| `FeishuCallbackResult` | Webhook 回调应答：`statusCode` / `contentType`（默认 `application/json; charset=utf-8`）/ `body`，`isSuccess()` 判定 2xx |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建飞书客户端 | `BotClient.auto("feishu")` | 按平台名走 SPI；找不到工厂抛 `IllegalStateException` |
| 链式构建 | `BotClient.builder("feishu").token(...).secret(...).build()` | 返回 `BotClient.Builder` |
| 一句话配置 | `configure(appId, appSecret, encryptKey)` | 三参分别对应 App ID / App Secret / Encrypt Key |
| 收消息（长连接） | `addMessageListener(...)` + `start()` | 默认模式，无需公网地址，自动重连 |
| 发文本 | `sendText(openId, content)` / `sendTextAsync` | 返回 `BotSendResult`（`isSuccess()` / `getMsgId()`） |
| 发图片 | `sendImage(openId, localPath)` / `sendImageAsync` | 先传图拿 `image_key` 再发消息 |
| 群发 / 群 @ | `sendToGroup(chatId, text)`、`sendToGroupMention(chatId, text, userIds)` | @ 消息以 `interactive` 类型发送 |
| 群列表 | `listGroups()` | 调飞书 im chat list，异常/未启动返回空列表 |
| Webhook 接入 | `handleCallback(body, headers)` | 返回 `FeishuCallbackResult`，原样写回 HTTP 响应即可 |
| 凭据校验 | `FeishuBotClient.fetchTenantAccessToken(appId, appSecret)` | 静态方法，仅走轻量 HTTP，不建立长连接 |

### 2.2 FeishuBotClient 方法一览

| 分类 | 方法 |
| --- | --- |
| 配置 | `configure(token, secret, encodingAesKey)`、`token`、`secret`、`encodingAesKey`、`baseUrl`、`connectTimeoutMillis`、`readTimeoutMillis`、`configSaveOrLoader`（本模块空实现）、`webhookVerifyToken`、`webhookEncryptKey`、`userStore` |
| 生命周期 | `start()`、`stop()`、`isRunning()`、`isUseWebhookMode()` |
| 发送（同步） | `sendText`、`sendImage`、`sendVoice`、`sendVideo`、`sendFile`、`send(BotOutboundMessage)`、`sendToGroup`、`sendToGroupMention` |
| 发送（异步） | `sendTextAsync`、`sendImageAsync`、`sendAsync`、`sendToGroupAsync`、`sendToGroupMentionAsync` |
| 群与用户 | `listGroups()`、`listUsers()` |
| 监听器 | `addMessageListener`、`removeMessageListener`、`addErrorListener` |
| Webhook | `handleCallback(body, headers)`、`verifyChallenge(challengeToken)` |
| 状态 | `getConfig()`（返回 `appId` / `baseUrl` / `running` / `useWebhookMode`） |
| 静态工具 | `fetchTenantAccessToken(appId, appSecret)` |

---

## 3. 快速开始

### 3.1 长连接模式（默认，无需公网地址）

```java
import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotSendResult;

BotClient bot = BotClient.auto("feishu");           // SPI 名固定为 "feishu"
bot.configure("cli_xxx", "your-app-secret", null);  // App ID / App Secret / Encrypt Key(可空)

bot.addMessageListener(msg -> {
    // 单聊直接回；群聊仅在被 @ 机器人时回
    if (!msg.isFromGroup() || Boolean.TRUE.equals(msg.getMentionedBot())) {
        BotSendResult r = bot.sendText(msg.getFromUser(), "收到：" + msg.getContent());
        System.out.println(r.isSuccess() ? "msgId=" + r.getMsgId() : r.getErrorMessage());
    }
});

bot.start();   // 校验 appId/appSecret（缺失抛 IllegalStateException），建立 WebSocket 长连接
```

### 3.2 Builder 方式创建

```java
BotClient bot = BotClient.builder("feishu")
        .token("cli_xxx")
        .secret("your-app-secret")
        .baseUrl("https://open.feishu.cn/open-apis")   // 可省略，此为默认值
        .connectTimeoutMillis(10_000)
        .readTimeoutMillis(30_000)
        .build();
```

### 3.3 Webhook 模式

配置了 `webhookVerifyToken` 或 `webhookEncryptKey` 任意一个，`start()` 即进入 Webhook 模式：**不建立长连接**，由调用方把飞书事件订阅的 HTTP 回调转发进来。

```java
import com.chua.feishu.support.bot.FeishuBotClient;
import com.chua.feishu.support.bot.FeishuCallbackResult;

FeishuBotClient bot = (FeishuBotClient) BotClient.auto("feishu");
bot.configure("cli_xxx", "your-app-secret", null);
bot.webhookVerifyToken("verification-token");   // 飞书后台「Verification Token」
bot.webhookEncryptKey("encrypt-key");           // 可选；配置后回调体为 {"encrypt":"..."} 并按 X-Lark-Signature 验签
bot.addMessageListener(msg -> { /* 同上 */ });
bot.start();

// 在你自己的 HTTP 端点里（Servlet / Spring Controller 等）：
FeishuCallbackResult resp = bot.handleCallback(requestBody, headersMap);
// 把 resp.getStatusCode()、resp.getContentType()、resp.getBody() 原样写回 HTTP 响应；
// URL 验证握手的 challenge 回显也由 handleCallback 自动完成
```

`handleCallback` 的应答约定：

| 场景 | 返回 |
| --- | --- |
| 请求体为空 | `400` + `{"msg":"empty body"}` |
| 非 JSON | `400` + `{"msg":"invalid json"}` |
| 明文体 token 比对失败 | `401` + `{"msg":"invalid token"}` |
| `url_verification` 握手 | `200` + 回显 `challenge` |
| SDK 处理异常 | `500` + `{"msg":"internal error"}` |

### 3.4 群发与 @ 提及

```java
bot.sendToGroup("oc_xxx", "大家好");                                  // oc_ 前缀 = 群 chat_id
bot.sendToGroupMention("oc_xxx", "请相关同学关注", List.of("ou_xxx")); // ou_ 前缀 = 用户 open_id
```

接收方 ID 类型按前缀自动推断：`oc_` → `chat_id`，`on_` → `union_id`，其余 → `open_id`。

### 3.5 凭据校验（不建立长连接）

```java
// 仅校验 appId/appSecret 有效性（获取 tenant_access_token），不建立 WebSocket 连接；
// 凭据无效抛 IllegalArgumentException("飞书凭据校验失败：...")
String tenantToken = FeishuBotClient.fetchTenantAccessToken("cli_xxx", "your-app-secret");
```

---

## 4. 配置项

本模块**不是 Spring Boot starter**：源码未提供任何 `application.properties` / `application.yml` 配置键或 `@ConfigurationProperties` 类，全部配置通过链式 API 在代码中给出。

| 配置 | 链式方法 | 默认值 | 说明 |
| --- | --- | --- | --- |
| App ID | `token(...)` / `configure(...)` 第 1 参 | 无 | 必填；`start()` 时缺失抛 `IllegalStateException("appId is required")` |
| App Secret | `secret(...)` / `configure(...)` 第 2 参 | 无 | 必填；`start()` 时缺失抛 `IllegalStateException("appSecret is required")` |
| Encrypt Key | `encodingAesKey(...)` / `webhookEncryptKey(...)` / `configure(...)` 第 3 参 | 无 | 飞书事件订阅「Encrypt Key」；与 `webhookVerifyToken` 任一非空即启用 Webhook 模式 |
| Webhook Verification Token | `webhookVerifyToken(...)` | 无 | 飞书后台「Verification Token」，用于 URL 验证握手与明文体 token 比对 |
| API 基础地址 | `baseUrl(...)` | `https://open.feishu.cn/open-apis` | 以 `/open-apis` 结尾时 `start()` 会为 SDK 剥离该后缀（SDK 期望域名根） |
| 连接超时 | `connectTimeoutMillis(...)` | `10_000` 毫秒 | |
| 读取超时 | `readTimeoutMillis(...)` | `30_000` 毫秒 | 同时作为 SDK `requestTimeout` |
| 用户存储 | `userStore(...)` | `InMemoryBotUserStore` | 收到消息时 upsert 发送者 `BotUserInfo`；`listUsers()` 读取 |
| 配置加载器 | `configSaveOrLoader(...)` | — | 本模块空实现，仅为 `BotClient` 契约保留链式兼容 |

---

## 5. 接收行为细节

- 入站事件固定映射为 `BotInboundMessage`，`eventType` 为 `"im.message.receive_v1"`；消息类型映射：`text`→`TEXT`、`image`→`IMAGE`、`audio`→`VOICE`、`file`→`FILE`、`video`→`VIDEO`，其余→`UNKNOWN`。
- 正文中的 @ 占位符（`@_user_N`）会按 `message.mentions` 还原为「@真实名字」，取不到名字时移除占位符，避免把占位符原样发给下游。
- `mentionedBot` 通过 `start()` 时调 `{baseUrl}/bot/v3/info` 解析出的机器人 `open_id` 判定；解析失败仅记日志（此时群聊消息无法判定是否被 @）。
- 群 @ 提及消息以 `msgType=interactive` 发送，`at` 列表为 `open_id` + `key` 结构。
- 图片发送流程：先 `im().image().create`（`imageType=message`）上传取 `image_key`，再发 `image` 消息。
- 任意监听器/发送异常会回调 `BotErrorListener.onError`，不会中断其他监听器。
- `stop()` 通过反射调用 SDK `ws.Client#disconnect` 并关闭其内部线程池（SDK 未暴露公开的关闭方法）。

---

## 6. SPI 扩展点

本模块向 `BotClient.Factory` SPI 注册**飞书平台实现**，注册文件：

```text
src/main/resources/META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory
feishu=com.chua.feishu.support.bot.FeishuBotClientFactory
```

- 工厂类 `FeishuBotClientFactory` 标注 `@Spi("feishu")`，实现 `create()`（直接 new `FeishuBotClient`）与 `builder()`（返回内部类 `FeishuBuilder`）。
- 使用方无需直接依赖本模块的类：通过 `BotClient.auto("feishu")` / `BotClient.builder("feishu")`（底层 `ServiceProvider`）即可装配。
- 内置 GraalVM `native-image` 配置（`META-INF/native-image/com.chua/utils-support-feishu-starter/`）：`resource-config.json` 包含 `META-INF/extensions/.*`，`reflect-config.json` 注册 SPI 工厂类 `FeishuBotClientFactory`，保证原生镜像下 SPI 仍可加载。

---

## 7. 构建与依赖

- 父模块：`com.chua:utils-support-cloud-parent:4.0.0.51`（云服务集成父模块），根父 `com.chua:utils-support-parent-starter:4.0.0.51`。
- 编译目标 JDK 25（根 pom `maven.compiler.source/target=25`，测试 JVM 需 `--enable-preview`）。
- 依赖树：

```text
com.chua:utils-support-feishu-starter:4.0.0.51
├── com.larksuite.oapi:oapi-sdk:2.8.5            （飞书开放平台官方 SDK，含 WebSocket 长连接）
└── com.chua:utils-support-common-starter:4.0.0.51 （BotClient 契约、消息模型、SPI 框架）
```

- 本地编译验证：

```bash
mvn -o -q compile -DskipTests
```

---

## 8. 边界：这些能力不在本模块

| 能力 | 状态 |
| --- | --- |
| 语音消息 `sendVoice` | **不支持**：直接返回 `BotSendResult.fail(-1, "Feishu bot does not support voice messages")` |
| 视频 `sendVideo` / 文件 `sendFile` | **不支持**：返回 `fail(-1, "...does not support ... messages via webhook")` |
| HTTP 回调端点 | 不提供 Controller/Servlet；Webhook 模式需调用方自行暴露 HTTP 接口，把请求体与请求头交给 `handleCallback(body, headers)` |
| `BotClient` 契约与消息模型 | 定义在 `utils-support-common-starter`（`BotClient`、`BotInboundMessage`、`BotOutboundMessage`、`BotSendResult`、`BotMessageListener`、`BotErrorListener`、`BotUserStore`、`InMemoryBotUserStore`、`BotUserInfo`、`BotGroupInfo`） |
| 其他 IM 平台 | 钉钉在 `utils-support-dingding-starter`、QQ 在 `utils-support-qq-starter` 等同仓模块，本模块只做飞书 |
| Spring 自动配置 / 配置文件键 | 无（纯 Java 库 + SPI，配置全走链式 API） |
| 数据库、持久化、渠道管理、AI 对话编排 | 均不属于本模块；本模块是最底层工具库中的飞书 Bot 适配层 |
| 自定义富文本/卡片消息 | 未封装；仅群 @ 场景内部使用 `interactive` 类型，外部发送入口只有文本与图片 |
