# utils-support-dingding-starter

> 钉钉集成模块：把钉钉的 **AI 助理对话**、**机器人（Stream 长连接 / Webhook）**、**自定义机器人 Webhook 消息推送** 三类能力，接进 utils 体系的三个 SPI 契约（`ChatClient` / `BotClient` / `MessagePush`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 pom `java.version=25`）｜包名：`com.chua.dingding.support`（含 `.message`、`.bot`）

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
    <artifactId>utils-support-dingding-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| AI 对话 | `DingdingChatClient`：钉钉 AI 对话接口（`https://api.dingtalk.com/v1.0/ai/conversations`）的 `ChatClient` 实现 | 不是通用大模型网关，也不实现其它厂商协议 |
| 机器人 | `DingTalkBotClient`：收消息走 Stream 长连接（无需公网回调）或 Webhook 回调，发消息按「被动回复 → 开放平台主动消息 → 自定义机器人 Webhook」择优 | 不含通讯录、审批、日程等其它钉钉开放平台接口 |
| 消息推送 | `DingdingMessagePush`：自定义机器人 Webhook 发送 text / markdown 消息 | 不负责模板渲染的持久化管理（模板表仅在内存，需自行 `registerTemplate`） |
| 角色 | utils 体系 SPI 的**实现方** | 不定义 SPI 契约；契约在 `utils-support-common-starter` |

### 1.3 类一览（源码 4 个顶层公开类型，另含公开嵌套异常类 `DingTalkBotClient.DingTalkApiException`）

| 类 | SPI 名 | 实现接口 | 职责 |
| --- | --- | --- | --- |
| `com.chua.dingding.support.DingdingChatClient` | `dingding` | `com.chua.common.support.ai.chat.ChatClient` | 钉钉 AI 大模型对话 |
| `com.chua.dingding.support.bot.DingTalkBotClient` | —（由工厂创建） | `com.chua.common.support.ai.bot.BotClient` | 机器人收发消息 |
| `com.chua.dingding.support.bot.DingTalkBotClientFactory` | `dingtalk` | `com.chua.common.support.ai.bot.BotClient.Factory` | 机器人客户端 SPI 工厂与构建器 |
| `com.chua.dingding.support.message.DingdingMessagePush` | `dingding` | `com.chua.common.support.task.message.MessagePush` | 自定义机器人 Webhook 推送 |

---

## 2. 核心 API 与速查表

最常查的入口：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 机器人收消息（免公网） | `new DingTalkBotClient()` → `configure(appKey, appSecret, null)` → `addMessageListener(...)` → `start()` | Stream 长连接模式，自动重连与空闲检测 |
| 机器人发文本 | `bot.sendText(toUser, content)` / `bot.sendToGroup(groupId, content)` / `bot.sendToGroupMention(groupId, content, userIds)` | 优先被动回复，其次开放平台主动消息，最后自定义机器人 Webhook |
| 机器人发图片 / 文件 | `bot.sendImage(toUser, pathOrUrl)` / `bot.sendFile(toUser, path)` | https 图片直发；本地文件先走 `/media/upload` 换 `media_id`（需凭证） |
| Webhook 回调接入 | `bot.handleCallback(jsonBody)`、`bot.verifyChallenge(challengeToken)` | 无 Stream 凭证且 `webhookVerifyToken` 非空时以 Webhook 接收模式启动 |
| 运行诊断 | `bot.getConfig()` / `isStreamMode()` / `isWebhookMode()` / `isWebSocketConnected()` / `getGatewayEndpoint()` | 配置快照敏感字段已脱敏 |
| AI 对话 | `ChatClient.create("dingding", appKey).chat(prompt, consumer)` | 完整响应体在 `STOP` 帧的 `getFullContent()` |
| 群里 @ 人 | `bot.sendToGroupMention(groupId, content, mentionedUserIds)` | 正文自动拼 `@userId` 占位，`at.atUserIds` 携带名单 |
| Webhook 消息推送 | `new DingdingMessagePush(new MessageEnvironment().set("dingding.webhookUrl", ...).set("dingding.secret", ...))` → `send(MessageRequest)` | 支持加签；HTTP 200 且响应含 `"errcode":0` 判定成功 |

`DingTalkBotClient` 发送侧方法全景（均为源码已验证）：

| 方法 | 行为 |
| --- | --- |
| `sendText(toUser, content)` | 文本；被动回复窗口有效时直接回 `sessionWebhook` |
| `sendImage(toUser, mediaPath)` | https 地址走 `picURL`；本地文件上传后走 `photoURL=media_id` |
| `sendFile(toUser, mediaPath)` | 先传素材，再以 `sampleFile` 模板下发（需 `clientId/clientSecret`） |
| `sendVoice` / `sendVideo` | 直接返回失败：钉钉模板要求音频时长 / 封面素材，客户端无法推导 |
| `send(BotOutboundMessage)` | 按类型分发；`TEXT + toGroup` 自动走群发（含 @） |
| `sendTextAsync` / `sendImageAsync` / `sendAsync` / `sendToGroupAsync` / `sendToGroupMentionAsync` | 同名异步版，返回 `CompletableFuture<BotSendResult>` |

入站消息 `BotInboundMessage` 映射（钉钉字段 → 统一模型）：`text/richtext→TEXT`、`picture→IMAGE`、`audio→VOICE`、`video→VIDEO`、`file→FILE`、其它→`UNKNOWN`；`senderStaffId/senderId→fromUser`、`senderNick→fromUserName`、`robotCode→toUser`、`conversationId→chatId`、`conversationType=="2"→fromGroup`、`isInAtList→mentionedBot`、`atUsers[].dingtalkId→mentionedList`、`content.downloadCode→mediaId`（并换取临时 `mediaUrl`）。

---

## 3. 快速开始

### 3.1 机器人收发消息（Stream 模式，最短示例）

```java
DingTalkBotClient bot = new DingTalkBotClient();
bot.configure(clientId, clientSecret, null);            // 应用的 AppKey / AppSecret
bot.addMessageListener(msg -> bot.sendText(msg.getFromUser(), "已收到"));
bot.start();                                            // Stream 长连接，自动重连
// ... 运行期间持续收发
bot.stop();
```

只有凭证（`clientId/clientSecret`）时走 Stream；无凭证但 `webhookVerifyToken` 非空时进入 Webhook 接收模式（回调体用 `bot.handleCallback(json)` 派发）；仅有 `webhookUrl` 时为仅发送模式（`getConfig()` 的 `mode=send-only`，发送走自定义机器人 Webhook）；两者皆无时 `start()` 抛 `IllegalStateException`。

也可走 SPI 构建器（平台名 `dingtalk`）：

```java
BotClient bot = BotClient.builder("dingtalk")
        .token(clientId)
        .secret(clientSecret)
        .build();
```

> 订阅主题（`topics(...)`）、机器人编码（`robotCode(...)`）、Webhook 三件套（`webhookUrl/webhookSignSecret/webhookVerifyToken`）是 `DingTalkBotClient` 的专有方法，需拿到具体类型后设置。

### 3.2 钉钉 AI 对话

```java
ChatClient client = ChatClient.create("dingding", "你的AccessToken");   // appKey 即 Bearer 令牌
client.model("dingtalk-ai").system("你是一个简洁的助手");

client.chat("介绍一下你自己", resp -> {
    if (resp.getState() == ChatResponse.State.STOP) {
        System.out.println(resp.getFullContent());      // 钉钉返回的完整响应体
    }
});
```

> 注意：本实现的完整内容放在 `STOP` 帧而不是 `STREAMING` 分片；`chatSync(prompt)` 只收集 `STREAMING` 片段，因此对本实现会得到空串，请用 `chat(prompt, consumer)` 读 `STOP` 帧。

### 3.3 自定义机器人 Webhook 推送

```java
DingdingMessagePush push = new DingdingMessagePush(new MessageEnvironment()
        .set("dingding.webhookUrl", "https://oapi.dingtalk.com/robot/send?access_token=xxx")
        .set("dingding.secret", "SECxxxxxxxx"));        // 可选：加签密钥

push.send(MessageRequest.builder()
        .content("部署完成")
        .build());

// Markdown：contentType 传 markdown，subject 作为标题
push.send(MessageRequest.builder()
        .contentType("markdown")
        .subject("发布通知")
        .content("**v1.2.0** 已发布")
        .build());
```

> `MessagePush.create("dingding")` 走无参构造，`MessageEnvironment` 为空，发送时会因缺少 `dingding.webhookUrl` 抛 `IllegalArgumentException`；需要注入环境时请直接 `new DingdingMessagePush(environment)`。

---

## 4. 配置项

### 4.1 DingdingMessagePush（`MessageEnvironment` 键）

| 键 | 必填 | 说明 |
| --- | --- | --- |
| `dingding.webhookUrl` | 是 | 机器人 Webhook 地址；缺失时 `send()` 抛 `IllegalArgumentException("dingding.webhookUrl 配置项必填")` |
| `dingding.secret` | 否 | 加签密钥；配置后按 `HmacSHA256(timestamp + "\n" + secret)` 计算签名，以 `timestamp`、`sign` 查询参数追加 |

### 4.2 DingdingChatClient（读取 `ChatClientSetting` 的字段）

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `appKey` | — | 作为 `Authorization: Bearer` 令牌 |
| `baseUrl` | `https://api.dingtalk.com/v1.0/ai/conversations` | 自动去掉尾斜杠 |
| `model` | `dingtalk-ai` | 请求体 `model` 字段 |
| `temperature` | `0.3` | 请求体 `temperature` 字段 |
| `maxTokens` | `2048` | 请求体 `max_tokens` 字段 |
| `system` | — | 系统提示词；配置 `SkillManager` 时经 `SkillPrompt.inject` 注入 |
| `proxy` | — | `host:port`（可带 `scheme://` 前缀），设置后走代理 |

请求附加开关：`thinking(true)` 追加 `"thinking":true`；`smartSearch(true)` 追加 `"enable_search":true`。超时：连接 30 秒、读取 90 秒。

### 4.3 DingTalkBotClient（无外部配置键，全部经 setter / builder）

| 参数 | 缺省 | 说明 |
| --- | --- | --- |
| `clientId` / `clientSecret` | — | AppKey / AppSecret；两者齐备即 Stream 模式 |
| `robotCode` | 取 `clientId` | 开放平台主动消息的机器人编码 |
| `apiBaseUrl` | `https://api.dingtalk.com` | 开放平台 API 域名 |
| `legacyBaseUrl` | `https://oapi.dingtalk.com` | 自定义机器人 Webhook 与素材上传域名 |
| `webhookUrl` / `webhookAccessToken` | — | 自定义机器人 Webhook（后者按 `legacyBaseUrl + /robot/send?access_token=` 拼装） |
| `webhookSignSecret` | — | Webhook 加签密钥（`SEC` 开头） |
| `webhookVerifyToken` | — | 非空即以 Webhook 接收模式启动；配合 `verifyChallenge()` |
| `topics(List<String>)` | `/v1.0/im/bot/messages/get` | Stream 订阅主题，须在 `start()` 前设置 |
| `connectTimeoutMillis` / `readTimeoutMillis` | `10000` / `30000` | HTTP 与建连超时 |
| `idleTimeoutMillis` | `300000`（每 30 秒检测） | 超时未收到任何下行帧即主动重连 |
| 重连退避 | `1s` 起，指数递增，上限 `30s` | 长连接中断后 |
| access_token | 提前 `60s` 刷新；响应缺 `expireIn` 时按 `7200s` 兜底 | `POST /v1.0/oauth2/accessToken` |

`getConfig()` 返回快照键：`mode`（`stream`/`webhook`/`send-only`）、`clientId`（脱敏）、`apiBaseUrl`、`legacyBaseUrl`、`webhookUrl`（掩码）、`subscriptions`、`running`、`streamConnected`、`gatewayEndpoint`、`observedGroups`、`observedSessions`、`tokenCached`。

---

## 5. SPI 扩展点

本模块是 SPI **实现方**，注册文件位于 `src/main/resources/META-INF/extensions/`：

| 接口文件 | 注册名 | 实现 |
| --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `dingding` | `DingdingChatClient` |
| `com.chua.common.support.task.message.MessagePush` | `dingding` | `DingdingMessagePush`（`@SpiDescribe` 声明可选参数 `dingding.webhookUrl`、`dingding.secret`） |
| `com.chua.common.support.ai.bot.BotClient$Factory` | `dingtalk` | `DingTalkBotClientFactory` |

使用方式：

```java
ChatClient  chat = ChatClient.create("dingding", appKey);          // 内部走 SPI
MessagePush push = MessagePush.create("dingding");                 // 无参构造，环境为空（见 3.3 注意）
BotClient   bot  = BotClient.auto("dingtalk");                     // 或 BotClient.builder("dingtalk")...
```

扩展方向：新增钉钉能力时应优先扩展 `DingTalkBotClient` / `DingdingChatClient` 本身；与其它平台共用的抽象应下沉到 `utils-support-common-starter` 的 SPI 契约，而不是在本模块另起一套接口。

---

## 6. 构建与依赖

- 父链：`utils-support-parent-starter`（`4.0.0.51`，`java.version=25`）→ `utils-support-cloud-parent` → `utils-support-dingding-starter`。
- 编译：`mvn -o -q compile -DskipTests`，`EXIT=0` 为通过。
- 依赖（`pom.xml`）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}` | SPI 契约、HTTP 客户端、JSON、集合工具 |
| `com.aliyun:dingtalk` | `2.2.65` | 钉钉官方 SDK |
| `com.alibaba.fastjson2:fastjson2` | `2.0.65` | JSON 处理 |
| `org.slf4j:slf4j-api` | 父 pom 管理 | 日志门面 |
| `org.projectlombok:lombok` | provided | 编译期代码生成 |

- 已随包提供 GraalVM `native-image` 元数据：`META-INF/native-image/com.chua/utils-support-dingding-starter/reflect-config.json` 与 `resource-config.json`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 / 说明 |
| --- | --- |
| `ChatClient` / `BotClient` / `MessagePush` 等契约与统一模型 | `utils-support-common-starter`；本模块只是钉钉实现 |
| 语音、视频消息发送 | `sendVoice` / `sendVideo` 直接返回失败：钉钉模板要求音频时长 / 封面素材，客户端不推导、不生成 |
| AI 对话的文件附件 | `addAttachment` / `addAttachmentUrl` 抛 `UnsupportedOperationException` |
| `addImage(imageUrl)` | 仅在客户端内记录，当前请求体不携带图片 |
| `chatSync(prompt)` | 只收集 `STREAMING` 片段，本实现内容在 `STOP` 帧，故返回空串；请用 `chat(...)` |
| 群列表 | 钉钉未提供群列表接口；`listGroups()` 只返回长连接期间观测到的群 |
| 模板持久化 | `DingdingMessagePush` 的模板表在内存中，需自行 `registerTemplate`；`sendTemplate` 仅组装请求后走 `send` |
| 通讯录、审批、日程等其它钉钉开放平台接口 | 未封装；需要时按同一模式扩展 |
| Spring Boot 自动装配 / 管理端点 | 本模块是纯工具库，不含自动配置 |
