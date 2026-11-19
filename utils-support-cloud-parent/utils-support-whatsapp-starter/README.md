# utils-support-whatsapp-starter

> WhatsApp Cloud API（Meta Graph）Bot 客户端集成：发送走 Graph API `/{phone_number_id}/messages`，接收走 Meta Webhook，作为 `BotClient` SPI 的 `whatsapp` 平台实现接入 utils 体系。
>
> 版本：`4.0.0.51`（随 `utils-support-cloud-parent`；本模块类自 `4.0.0.42` 引入）｜JDK：`25`｜包名：`com.chua.whatsapp.support.bot`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心概念与凭证语义](#2-核心概念与凭证语义)
- [3. 核心 API 与速查表](#3-核心-api-与速查表)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. 消息收发详解](#6-消息收发详解)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-whatsapp-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父模块：`com.chua:utils-support-cloud-parent`（云服务集成父模块）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 协议 | WhatsApp Cloud API（Meta Graph `v20.0`）Bot 客户端：发送走 `https://graph.facebook.com/v20.0/{phone_number_id}/messages`，接收走 Meta Webhook | 不是 WhatsApp 官方 SDK 的完整封装；不提供媒体上传、模板管理等其余 Graph API 能力 |
| 体系 | `com.chua.common.support.ai.bot.BotClient` SPI 的 `whatsapp` 平台实现，可与钉钉、飞书等其它 Bot 平台同一套门面使用 | 不含 Webhook 回调 HTTP 端点，也不做 Spring Boot 自动装配 |
| 依赖 | 仅依赖 `utils-support-common-starter` | 不依赖 Spring、数据库驱动或其它云 SDK |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `BotClient.auto("whatsapp")` 或 `BotClient.builder("whatsapp").build()` | SPI 按平台名 `whatsapp` 装配 |
| 配置凭证 | `configure(phoneNumberId, accessToken, appSecret, verifyToken)` | 四参重载；也可走 `token` / `secret` / `encodingAesKey` / `verifyToken` 链式设置 |
| 凭证校验 | `validate()` | GET 商务号码信息（`fields=display_phone_number,verified_name`），失败抛 `IllegalArgumentException` |
| 启动 / 停止 | `start()` / `stop()` / `isRunning()` | `start()` 校验 `phone_number_id` 与 `access_token` 必填 |
| 发文本 | `sendText(toUser, content)` / `sendTextAsync(...)` | 超过 4096 字符自动分段（尽量在换行处断开） |
| 发媒体 | `sendImage` / `sendVoice` / `sendVideo` / `sendFile` | 仅接受公网 `http(s)` URL（Cloud API `link` 方式） |
| 收消息 | `addMessageListener(...)` + `handleCallback(body, headers)` | 验签后解析 Webhook 载荷并分发 `BotInboundMessage`；GET 握手用 `verifyWebhook(mode, token, challenge)` |
| 下载入站媒体 | `downloadMedia(mediaId)` | 先取临时下载地址，再带 Bearer 取字节 |

---

## 2. 核心概念与凭证语义

本模块只有两个公开类型（均在 `com.chua.whatsapp.support.bot` 包）：

| 类型 | 说明 |
| --- | --- |
| `WhatsAppBotClient` | 实现 `BotClient` 的客户端本体；承担凭证配置、发送、Webhook 验签与消息分发、媒体下载 |
| `WhatsAppBotClientFactory` | `@Spi("whatsapp")` 工厂，实现 `BotClient.Factory`；内含静态内部类 `WhatsAppBuilder`（实现 `BotClient.Builder`） |

另有内部 record `WhatsAppBotClient.WhatsAppCallbackResult(int statusCode, String body)`，作为 `handleCallback` 的返回值。

### 凭证语义（与 `BotClient` 通用三参的对应关系）

| `BotClient` 通用参数 | WhatsApp 实际含义 | 说明 |
| --- | --- | --- |
| `token` | `phone_number_id` | 商务电话号码 ID，必填 |
| `secret` | `access_token` | Meta access_token，必填 |
| `encodingAesKey` | Meta App Secret | 用于 Webhook 签名校验，可空 |
| （无通用对应） | `verifyToken` | Webhook 校验令牌，需单独通过 `verifyToken(String)` 设置，可空 |

> 发送地址默认 `https://graph.facebook.com/v20.0`，可用 `baseUrl(...)` 覆盖。

---

## 3. 核心 API 与速查表

### 3.1 配置与生命周期

```java
WhatsAppBotClient client = new WhatsAppBotClient();

// 通用三参（BotClient 接口约定）：token=phone_number_id, secret=access_token, encodingAesKey=appSecret
client.configure(token, secret, encodingAesKey);

// WhatsApp 语义四参重载（含 Webhook 校验令牌）
client.configure(phoneNumberId, accessToken, appSecret, verifyToken);

client.baseUrl("https://graph.facebook.com/v20.0");  // 可选
client.connectTimeoutMillis(10_000);                 // 可选，默认 10s
client.readTimeoutMillis(30_000);                    // 可选，默认 30s
client.httpProxy("127.0.0.1:7890");                  // 可选，出站代理 host:port

client.validate();   // 真实调用 Graph API 校验凭证，失败抛 IllegalArgumentException
client.start();      // 校验必填项后置为运行态（等待 Webhook 入站）
client.isRunning();
client.stop();
```

### 3.2 发送

| 方法 | 说明 |
| --- | --- |
| `sendText(toUser, content)` | 文本消息；超过 4096 字符（Cloud API 单条上限）自动按换行处分段发送，任一段失败即返回该段失败结果（此前已发出的不回滚） |
| `sendImage(toUser, link)` | 图片，`link` 必须是公网 `http(s)` URL |
| `sendVoice(toUser, link)` | 语音（Cloud API `audio` 类型） |
| `sendVideo(toUser, link, title, desc)` | 视频，说明文字优先取 `desc`，为空取 `title` |
| `sendFile(toUser, link)` | 文件（`document`），`filename` 从 URL 末段推断，取不到时为 `file` |
| `send(BotOutboundMessage)` | 通用出站：按 `TEXT`/`IMAGE`/`VOICE`/`VIDEO`/`FILE` 分发；群消息与其它类型返回失败 |
| `sendTextAsync` / `sendImageAsync` / `sendAsync` | 异步版本，返回 `CompletableFuture<BotSendResult>` |

发送成功时 `BotSendResult` 携带 Graph API 返回的 `messages[0].id` 与原始响应体（`rawResponse`）。

### 3.3 接收（Webhook）

| 方法 | 说明 |
| --- | --- |
| `verifyWebhook(mode, token, challenge)` | GET 握手：`hub.mode=subscribe` 且 `hub.verify_token` 与 `verifyToken` 一致时返回 `challenge`，否则返回 `null` |
| `handleCallback(body, headers)` | POST 回调：验签（`x-hub-signature-256`）→ 解析 → 分发给消息监听器；返回 `WhatsAppCallbackResult` |
| `addMessageListener(listener)` / `removeMessageListener(listener)` | 消息监听器 |
| `addErrorListener(listener)` | 错误监听器（发送失败、验签失败、解析异常均会回调） |
| `downloadMedia(mediaId)` | 下载入站媒体字节；调用方需自行限制大小并负责落盘/转存 |

`handleCallback` 的返回约定：

| 情形 | 状态码 | 响应体 |
| --- | --- | --- |
| 请求体为空 | 400 | `{"msg":"empty body"}` |
| 验签失败 | 401 | `{"msg":"invalid signature"}` |
| 处理成功 | 200 | `{"status":"ok"}` |
| 解析异常 | 500 | `{"msg":"internal error"}` |

### 3.4 用户与配置

| 方法 | 说明 |
| --- | --- |
| `userStore(BotUserStore)` | 注入用户存储；默认 `InMemoryBotUserStore`（内存） |
| `listUsers()` | 委托 `userStore.findAll()` |
| `getConfig()` | 返回 `{phoneNumberId, baseUrl, running}` |
| `listGroups()` / `sendToGroup(...)` / `sendToGroupMention(...)` | 群能力：`listGroups()` 恒返回空列表，群发送恒返回失败（Cloud API 不支持群消息） |

---

## 4. 快速开始

最小可运行示例（凭证换成你在 Meta 后台拿到的真实值）：

```java
import com.chua.common.support.ai.bot.BotClient;
import com.chua.whatsapp.support.bot.WhatsAppBotClient;

public class WhatsAppQuickStart {

    public static void main(String[] args) {
        // 1) SPI 按平台名创建（也可 new WhatsAppBotClient() 或 BotClient.builder("whatsapp").build()）
        WhatsAppBotClient client = (WhatsAppBotClient) BotClient.auto("whatsapp");

        // 2) 配置凭证：phone_number_id / access_token / App Secret / Webhook 校验令牌
        client.configure("phone-number-id", "access-token", "app-secret", "verify-token");

        // 3) 注册消息监听：收到文本即回执
        client.addMessageListener(msg ->
                client.sendText(msg.getFromUser(), "已收到：" + msg.getContent()));

        // 4) 启动（校验必填项后进入运行态）
        client.start();

        // 5) Meta Webhook GET 握手（hub.mode / hub.verify_token / hub.challenge）：
        //    校验通过返回 challenge，直接把 challenge 写回响应即可
        String challenge = client.verifyWebhook("subscribe", "verify-token", "hub-challenge");

        // 6) Meta Webhook POST 回调：由 Web 层拿到原始请求体与请求头后交给本方法，
        //    按返回的 statusCode / body 应答 Meta：
        // WhatsAppBotClient.WhatsAppCallbackResult result = client.handleCallback(body, headers);
    }
}
```

配套依赖（Maven）：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-whatsapp-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

---

## 5. 配置项

本模块无 Spring 配置文件、无 `application.yml` 配置键，全部配置通过链式方法 / Builder 传入；仅出站代理额外支持系统属性与环境变量。

### 5.1 链式 / Builder 配置

| 项 | 传入方式 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `phone_number_id` | `configure(...)` / `token(...)` | 是 | — | 商务电话号码 ID |
| `access_token` | `configure(...)` / `secret(...)` | 是 | — | Meta access_token |
| App Secret | `configure(...)` / `encodingAesKey(...)` | 否 | 空 | Webhook 签名校验密钥；未配置时**跳过验签**（仅用于联调） |
| `verifyToken` | `verifyToken(...)` | 否 | 空 | Webhook GET 握手校验令牌 |
| `baseUrl` | `baseUrl(...)` | 否 | `https://graph.facebook.com/v20.0` | Graph API 基础地址 |
| `connectTimeoutMillis` | `connectTimeoutMillis(...)` | 否 | `10000` | 连接超时（毫秒） |
| `readTimeoutMillis` | `readTimeoutMillis(...)` | 否 | `30000` | 读取超时（毫秒） |
| `httpProxy` | `httpProxy(...)` | 否 | 空 | 出站代理，格式 `host:port`；空白表示直连 |

### 5.2 出站代理的解析顺序

Meta Graph API 在部分网络（如中国大陆）被目的地封锁，需经可达的中转/代理出站。代理按以下顺序取第一个非空值：

1. 显式调用 `httpProxy("host:port")`；
2. 系统属性 `whatsapp.http.proxy`；
3. 环境变量 `WHATSAPP_HTTP_PROXY`；
4. 环境变量 `HTTPS_PROXY`。

值支持带 `scheme://` 前缀（解析时剥离），最终按最后一个 `:` 拆分 `host:port`；格式非法时记警告并直连。

### 5.3 固定常量

| 常量 | 值 | 说明 |
| --- | --- | --- |
| 单条文本上限 | 4096 字符 | Cloud API 限制，超出自动分段 |
| 签名请求头 | `x-hub-signature-256` | 大小写不敏感匹配 |
| 事件类型 | `whatsapp.message` | 入站 `BotInboundMessage.eventType` 固定值 |

---

## 6. 消息收发详解

### 6.1 出站报文

所有发送共用同一报文骨架，POST 到 `{baseUrl}/{phone_number_id}/messages`，`Authorization: Bearer {access_token}`：

```json
{
  "messaging_product": "whatsapp",
  "recipient_type": "individual",
  "to": "<toUser>",
  "type": "text | image | audio | video | document"
}
```

文本附加 `text: {body, preview_url:false}`；媒体附加对应类型对象（`link`、可选 `caption`、`document` 附 `filename`）。

### 6.2 入站解析（`entry → changes → value → messages`）

`handleCallback` 按.Meta Webhook 标准结构逐层取 `messages`，为每条消息构建 `BotInboundMessage`：

| 字段 | 取值 |
| --- | --- |
| `msgId` | `messages[].id` |
| `fromUser` | `messages[].from` |
| `fromUserName` | `contacts[0].profile.name`（取不到为 `null`） |
| `fromGroup` | 恒为 `false`（单聊） |
| `eventType` | `whatsapp.message` |
| `createTime` | `messages[].timestamp`，≤10 位按秒 ×1000 转毫秒 |
| `rawField("message")` | 原始消息 Map |

类型映射与内容提取：

| Cloud API `type` | `BotInboundMessage.Type` | `content` |
| --- | --- | --- |
| `text` | `TEXT` | `text.body` |
| `image` | `IMAGE` | `caption`，为空则 `[image]` |
| `audio` / `voice` | `VOICE` | `caption`，为空则 `[audio]` |
| `video` | `VIDEO` | `caption`，为空则 `[video]` |
| `document` | `FILE` | `caption`，为空则 `[document]` |
| `location` | `LOCATION` | `纬度,经度`（`latitude,longitude`） |
| 其它 / 未知 | `UNKNOWN` | — |

媒体消息额外写入：`mediaId`（后续传给 `downloadMedia`）、`rawField("mimeType")`。

### 6.3 验签

配置了 App Secret 时，按 `HmacSHA256(appSecret, body)` 计算摘要，与请求头 `x-hub-signature-256` 的 `sha256=<hex>` 做常量时间比较；未配置 App Secret 时跳过验签（仅限联调环境）。

---

## 7. SPI 扩展点

### 7.1 本模块注册的扩展

扩展文件：`src/main/resources/META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory`

```text
whatsapp=com.chua.whatsapp.support.bot.WhatsAppBotClientFactory
```

`WhatsAppBotClientFactory` 标注 `@Spi("whatsapp")`，提供两个入口：

- `create()`：直接 `new WhatsAppBotClient()`；
- `builder()`：返回 `WhatsAppBuilder`，支持 `token` / `secret` / `encodingAesKey` / `baseUrl` / `connectTimeoutMillis` / `readTimeoutMillis` / `configSaveOrLoader`，并额外提供 `verifyToken(...)` 与 `httpProxy(...)`，`build()` 时统一装配到客户端。

### 7.2 使用方式

```java
// 方式一：静态门面
BotClient client = BotClient.auto("whatsapp");
BotClient.Builder builder = BotClient.builder("whatsapp");

// 方式二：SPI 直接取工厂
BotClient.Factory factory = ServiceProvider
        .of(BotClient.Factory.class)
        .getExtension("whatsapp");
```

### 7.3 如何扩展

新增其它 Bot 平台时照同样三步：实现 `BotClient.Factory`（实现类可加 `@Spi("平台名")`）→ 在本模块 `META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory` 追加一行 `平台名=实现类全限定名` → 调用方 `BotClient.auto("平台名")` 即可使用。本模块本身不再提供其它 SPI 扩展点。

---

## 8. 构建与依赖

### 8.1 编译要求

- JDK 25（父 pom `maven.compiler.source/target=25`，`java.version=25`）。
- 源码 UTF-8、无 BOM。
- 代码使用 Lombok `@Slf4j` 与 SLF4J 日志，版本由父 pom 统一管理。

```bash
# 在 utils-support-parent-starter 仓库根目录
mvn -pl utils-support-cloud-parent/utils-support-whatsapp-starter -am clean package
```

### 8.2 依赖

| 依赖 | 用途 |
| --- | --- |
| `com.chua:utils-support-common-starter`（同版本） | 唯一声明依赖：提供 `BotClient` SPI 与消息模型（`BotInboundMessage` / `BotOutboundMessage` / `BotSendResult` 等）、`HttpClientFactory`、`Json`、`StringUtils`、SPI 基础设施 |

pom 中除父模块外仅此一个依赖；HTTP 执行器、JSON 等运行期能力由 common-starter 的 SPI 体系决定。

---

## 9. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| Webhook 回调 HTTP 端点 | 不在本模块。按 Javadoc 约定由 ai-starter 暴露回调端点，把原始请求体与请求头交给 `handleCallback(String, Map)` |
| 群消息 | WhatsApp Cloud API 不支持：`listGroups()` 恒返回空列表，`sendToGroup` / `sendToGroupMention`（含异步）恒返回失败结果 |
| 本地文件直发 | `sendImage` / `sendVoice` / `sendVideo` / `sendFile` 只接受公网 `http(s)` URL（`link`）；本地文件需先上传到云存储或使用 Cloud API 媒体上传接口，本模块未封装媒体上传 |
| 模板消息 / 互动按钮等高级类型 | `send(BotOutboundMessage)` 仅分发 `TEXT` / `IMAGE` / `VOICE` / `VIDEO` / `FILE`，其余类型返回失败（`WhatsApp 暂不支持的消息类型`） |
| 配置持久化 | `configSaveOrLoader(...)` 为空实现，未接入任何配置存储 |
| 用户持久化 | 默认仅内存 `InMemoryBotUserStore`；需要持久化时自行实现 `BotUserStore` 并通过 `userStore(...)` 注入 |
| Spring Boot 自动装配 | 无。本模块是纯工具库模块，`src/main/resources` 下只有 SPI 扩展文件，无自动配置类与配置键 |

---

## 许可证

Apache License 2.0
