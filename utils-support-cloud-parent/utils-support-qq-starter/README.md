# utils-support-qq-starter

> QQ 开放平台机器人（Bot）客户端集成：实现 `utils-support-common-starter` 的 `BotClient` 统一契约，通过 SPI 以平台名 `qq` 注册；对接 QQ 机器人 API v2 的 REST 与 WebSocket 长连接。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.qq.support.bot`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 运行机制要点](#4-运行机制要点)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-qq-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | QQ 开放平台机器人 API v2 的客户端实现（REST + WebSocket 长连接） | 不是 QQ 客户端、不是 QQ 互联/OAuth 登录 SDK |
| 抽象 | `BotClient` 契约在 `utils-support-common-starter`，本模块是它的 `qq` 平台实现 | 不定义新的 Bot 抽象 |
| 形态 | 纯 Java 工具模块，编程式链式配置 | 不是 Spring Boot starter：无自动配置类、无 `application.yml` 配置键绑定 |
| 依赖 | 只依赖 `utils-support-common-starter`（HTTP、JSON、SPI、摘要工具均复用底层） | 不依赖 Spring、不依赖数据库驱动、不依赖 QQ 官方 SDK |

### 1.3 规模与组成

| 内容 | 位置 |
| --- | --- |
| `QqBotClient` | `com.chua.qq.support.bot`，`BotClient` 实现，`@since 4.0.0.42` |
| `QqBotClientFactory`（含内部 `QqBuilder`） | `com.chua.qq.support.bot`，`BotClient.Factory` SPI 实现，`@since 4.0.0.42` |
| SPI 注册文件 | `META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory`，内容：`qq=com.chua.qq.support.bot.QqBotClientFactory` |
| GraalVM native-image 配置 | `META-INF/native-image/com.chua/utils-support-qq-starter/`（`reflect-config.json`、`resource-config.json`） |

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `BotClient.auto("qq")` | 按 SPI 平台名自动装配 `QqBotClientFactory.create()` |
| Builder 构建 | `BotClient.builder("qq")` | `QqBuilder` 链式配置后 `build()` |
| 配置凭据 | `configure(appId, appSecret, botToken)` | 第 3 参为预取的 `access_token`，传 `null` 则用前两参自动换取 |
| 收消息 | `addMessageListener(...)` | 群聊/单聊/频道消息统一回调 `BotMessageListener` |
| 发文本 | `sendText` / `sendToGroup` | 单聊 openid 或群 openid |
| 发富媒体 | `sendImage` / `sendVoice` / `sendVideo` / `sendFile` | 自动先上传换 `file_info`，再以 `msg_type=7` 下发 |
| 被动回复 | 监听器内直接回复 | 窗口内自动携带 `msg_id`；窗口外自动转主动消息 |
| 生命周期 | `start()` / `stop()` / `isRunning()` | Webhook 模式与 WebSocket 模式二选一 |
| 观测群/用户 | `listGroups()` / `listUsers()` | 群列表来自长连接期间观测到的会话 |
| 运行状态 | `getConfig()` | 返回 appId、baseUrl、intents、sessionId、lastSeq 等 |
| Webhook 校验 | `webhookVerifyToken(...)` + `verifyChallenge(...)` | 匹配时回显 challenge |

---

## 2. 核心 API 与速查表

### 2.1 `QqBotClient`（实现 `BotClient`）

配置类（均返回 `this` 链式调用）：

| 方法 | 说明 |
| --- | --- |
| `configure(String token, String secret, String encodingAesKey)` | token → appId；secret → appSecret；encodingAesKey → 预取 access_token（非空时跳过令牌换取） |
| `token(String)` / `secret(String)` / `encodingAesKey(String)` | 单独设置三项凭据 |
| `baseUrl(String)` | API 基础地址，默认 `https://api.bot.qq.com`；自动去尾斜杠 |
| `tokenUrl(String)` | 令牌颁发地址覆盖值，沙箱或代理环境使用；令牌仅由 `https://bots.qq.com/app/getAppAccessToken` 签发 |
| `connectTimeoutMillis(long)` / `readTimeoutMillis(long)` | 默认 10_000 / 30_000 毫秒 |
| `intents(int...)` | 订阅意图位掩码，按位或合并；不传恢复默认（仅群聊与单聊 `1 << 25`） |
| `shard(int shardId, int shardCount)` | 分片信息，随 identify 帧上报 |
| `webhookVerifyToken(String)` | 非空时 `start()` 以 Webhook 模式启动 |
| `configSaveOrLoader(ConfigSaveOrLoader)` | 配置持久化器 |
| `userStore(BotUserStore)` | 用户存储，默认 `InMemoryBotUserStore` |

生命周期与状态：

| 方法 | 说明 |
| --- | --- |
| `start()` | 缺 appId 抛 `IllegalStateException`；`webhookVerifyToken` 非空走 Webhook 模式，否则启动 WebSocket 长连接 |
| `stop()` | 关闭连接、停心跳、清空会话与被动回复窗口 |
| `isRunning()` / `isUseWebhookMode()` | 运行标记 / 是否 Webhook 模式 |
| `verifyChallenge(String challengeToken)` | Webhook 回调校验，匹配返回 challenge，否则 `null` |
| `getConfig()` | 返回 `appId`、`baseUrl`、`tokenUrl`、`intents`、`connectTimeoutMillis`、`readTimeoutMillis`、`running`、`webhookMode`、`websocketConnected`、`sessionId`、`lastSeq`、`observedGroups` |

发送（同步返回 `BotSendResult`）：

| 方法 | 说明 |
| --- | --- |
| `sendText(String toUser, String content)` | 单聊文本 |
| `sendToGroup(String groupId, String content)` | 群聊文本 |
| `sendImage` / `sendVoice` / `sendFile` | 富媒体（参数为资源地址或本地路径） |
| `sendVideo(String toUser, String mediaPath, String title, String desc)` | 富媒体视频；`title`/`desc` 参数保留，当前未参与上传与请求体 |
| `send(BotOutboundMessage message)` | 通用出站消息，按 `getType()` 分派；`extension("msg_id")` 可显式指定被动回复消息 ID |
| `sendToGroupMention(String groupId, String content, List<String> mentionedUserIds)` | `mentionedUserIds` 为空时等同普通群文本；非空时返回失败：QQ 机器人消息接口无 @ 提及参数，无法定向提醒成员 |
| `sendTextAsync` / `sendImageAsync` / `sendAsync` / `sendToGroupAsync` / `sendToGroupMentionAsync` | 上述发送的 `CompletableFuture` 异步版本 |

会话与监听：

| 方法 | 说明 |
| --- | --- |
| `listGroups()` | 返回长连接期间观测到的群会话（平台未提供群列表接口） |
| `listUsers()` | 来自 `BotUserStore.findAll()` |
| `addMessageListener(BotMessageListener)` / `removeMessageListener(...)` | 消息监听器（不去重添加，`contains` 判重） |
| `addErrorListener(BotErrorListener)` | 错误监听器 |

### 2.2 `QqBotClientFactory` 与 `QqBuilder`

```java
BotClient.Factory factory = new QqBotClientFactory();
BotClient client  = factory.create();                 // new QqBotClient()
BotClient.Builder builder = factory.builder();        // QqBuilder
```

`QqBuilder` 可用方法：`token`、`secret`、`encodingAesKey`、`baseUrl`、`tokenUrl`、`intents(int...)`、`webhookVerifyToken`、`connectTimeoutMillis`、`readTimeoutMillis`、`configSaveOrLoader`，最后 `build()`。

---

## 3. 快速开始

### 3.1 最短可运行示例（来自 `QqBotClient` 类注释）

```java
BotClient client = BotClient.auto("qq");
client.configure(appId, appSecret, null);
client.addMessageListener(msg -> client.sendToGroup(msg.getChatId(), "已收到"));
client.start();
```

### 3.2 Builder 写法

```java
BotClient client = BotClient.builder("qq")
        .token(appId)                    // QQ BotAppID
        .secret(appSecret)               // QQ BotSecret
        .connectTimeoutMillis(10_000)
        .readTimeoutMillis(30_000)
        .build();

client.addMessageListener(msg -> {
    // 窗口内回复自动携带 msg_id（被动回复）；超出窗口自动转为主动消息
    client.sendToGroup(msg.getChatId(), "收到: " + msg.getContent());
});
client.start();
// ...
client.stop();
```

### 3.3 发送富媒体

```java
client.sendImage(openid, "D:/pictures/a.png");   // 本地文件走分片上传
client.sendImage(openid, "https://example.com/a.png"); // 远程 URL 走 /files 直传
```

### 3.4 Webhook 模式

```java
QqBotClient client = (QqBotClient) BotClient.auto("qq");
client.configure(appId, appSecret, null);
client.webhookVerifyToken(webhookToken);   // 非空时 start() 走 Webhook 模式
client.start();

// 平台回调校验：匹配返回 challenge，否则 null
String challenge = client.verifyChallenge(challengeToken);
```

---

## 4. 运行机制要点

以下均来自源码常量与实现：

| 主题 | 事实 |
| --- | --- |
| REST 鉴权 | `Authorization: QQBot {access_token}`；调用 `/gateway`、`/v2/users/{openid}/*`、`/v2/groups/{openid}/*` |
| 令牌换取 | `POST https://bots.qq.com/app/getAppAccessToken`（参数 `appId`、`clientSecret`）；剩余有效期低于 600 秒才换取；失败兜底有效期 7200 秒；最短保留 60 秒 |
| 长连接握手 | op10 Hello → op2 Identify（无会话）或 op6 Resume（有会话）→ op0 Dispatch；按 Hello 下发间隔以 op1 心跳上报最新 seq |
| 心跳间隔 | 服务端下发，缺省 45_000 毫秒，最小 1_000 毫秒 |
| 重连退避 | 起始 1_000 毫秒，倍数 2.0，上限 30_000 毫秒 |
| 关闭码 4914 | 机器人被封禁，停止重连 |
| 关闭码 4001/4002/4004/4005/4007/4009 | 会话失效，丢弃会话重新 identify；其余关闭码允许 resume |
| 默认订阅意图 | 群聊与单聊 `1 << 25`；频道公域消息为 `1 << 30`，仅应用已申请该权限时才可上报，否则长连接会被平台以关闭码 4002 拒绝 |
| 消息类型 | `msg_type=0` 文本，`msg_type=7` 富媒体；平台文件类型：1 图片、2 视频、3 语音、4 文件 |
| 富媒体上传 | URL：`POST /files` 直传；本地文件：`upload_prepare` → PUT 预签名分片 → `upload_part_finish` → `/files` 合并；携带 `md5`、`sha1`、`md5_10m`（前 10MB 窗口） |
| 被动回复窗口 | 群聊 5 分钟、单聊 60 分钟；窗口内自动携带 `msg_id` 与递增 `msg_seq`；窗口外以 `srv_send_msg=true` 按主动消息发送；`msg_seq` 随机起始、单调递增，避免平台去重 |
| 消息事件 | `C2C_MESSAGE_CREATE`、`GROUP_AT_MESSAGE_CREATE`、`AT_MESSAGE_CREATE`、`MESSAGE_CREATE`、`DIRECT_MESSAGE_CREATE` |
| 静默事件（不转发监听器） | `READY_OPENID`、`RESUMED`、`READ_C2C_MSG`、`READ_GROUP_MSG`、`C2C_MSG_SEND_STATE`、`GROUP_MSG_SEND_STATE` |
| REST 错误 | HTTP 非 2xx 或响应体 `code`/`ret` 非 0 时抛出（携带平台错误码），发送侧转为 `BotSendResult.fail` |
| 线程模型 | 长连接与心跳均为守护线程（`qq-bot-ws`、`qq-bot-heartbeat`） |

---

## 5. 配置项

**无配置文件键。** 依据：本模块不是 Spring Boot starter，源码中没有 `@ConfigurationProperties`、`application.yml` 或任何环境变量/系统属性读取；所有配置都通过 `QqBotClient` 链式方法或 `QqBuilder` 在代码中显式给出。可配置项与默认值：

| 项 | 默认值 | 设置入口 |
| --- | --- | --- |
| appId | 无（`start()` 时缺失抛 `IllegalStateException`） | `token(...)` / `configure(...)` / `QqBuilder.token(...)` |
| appSecret | 无（未提供 botToken 时换取令牌必须配置） | `secret(...)` / `configure(...)` / `QqBuilder.secret(...)` |
| botToken（预取 access_token） | 无（为空则自动换取） | `encodingAesKey(...)` / `configure(...)` 第 3 参 |
| baseUrl | `https://api.bot.qq.com` | `baseUrl(...)` |
| tokenUrl | 无覆盖（正式环境用官方令牌地址） | `tokenUrl(...)` |
| intents | `1 << 25`（群聊与单聊） | `intents(int...)` |
| shard | `0` / `1`（单分片） | `shard(shardId, shardCount)` |
| webhookVerifyToken | 无（为空走 WebSocket 长连接） | `webhookVerifyToken(...)` |
| connectTimeoutMillis | 10_000 | `connectTimeoutMillis(...)` |
| readTimeoutMillis | 30_000 | `readTimeoutMillis(...)` |
| userStore | `InMemoryBotUserStore` | `userStore(...)` |
| configSaveOrLoader | 无 | `configSaveOrLoader(...)` |

---

## 6. SPI 扩展点

- 本模块是 SPI 的**实现提供方**：在 `META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory` 中以键 `qq` 注册 `QqBotClientFactory`，供 `BotClient.auto("qq")` / `BotClient.builder("qq")` 发现。
- 本模块自身**没有**对外定义新的 SPI 接口或扩展点（`META-INF/extensions` 下仅上述一个注册文件）。
- 想替换/扩展底层能力（如 HTTP 执行器、JSON），使用 `utils-support-common-starter` 的对应 SPI（如 `HttpClientExecutor`），本模块经由 `HttpClientFactory`、`Json`、`DigestUtils` 等底层门面间接受益。

---

## 7. 构建与依赖

依赖树（`pom.xml`，父模块 `utils-support-cloud-parent`）：

```text
utils-support-qq-starter (com.chua:4.0.0.51)
└── utils-support-common-starter (com.chua:${project.version})
```

编译验证：

```bash
mvn -o -q compile -DskipTests
```

GraalVM：`META-INF/native-image/com.chua/utils-support-qq-starter/` 内置 `reflect-config.json` 与 `resource-config.json`。

---

## 8. 边界：这些能力不在本模块

| 能力 | 位置 / 说明 |
| --- | --- |
| `BotClient` 契约与消息模型（`BotInboundMessage`、`BotOutboundMessage`、`BotSendResult`、`BotGroupInfo`、`BotUserInfo`、`BotUserStore`、`InMemoryBotUserStore`、`BotMessageListener`、`BotErrorListener`） | `utils-support-common-starter`（`com.chua.common.support.ai.bot`） |
| 其它平台 Bot（钉钉、飞书、企业微信、WhatsApp、Bilibili 等） | 各自的 `utils-support-*-starter` |
| HTTP 底层执行器（okhttp / httpclient5） | `utils-support-okhttp-starter` / `utils-support-httpclient-starter`；未引入时回退 JDK 执行器 |
| Spring 自动配置、配置文件绑定 | 本模块无；如需托管请在上层自行装配 |
| 群列表查询接口 | QQ 开放平台未提供；`listGroups()` 只返回长连接期间观测到的群会话 |
| 群内定向 @ 提醒 | 平台消息接口无 @ 提及参数；`sendToGroupMention` 传入非空列表时返回失败 |
| 频道公域消息（`1 << 30`）的默认订阅 | 默认意图不含该权限；需应用已申请权限后通过 `intents(...)` 显式开启 |
