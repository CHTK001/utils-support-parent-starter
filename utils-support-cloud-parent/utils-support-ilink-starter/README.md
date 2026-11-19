# utils-support-ilink-starter

> 微信 iLink Bot 协议的 `BotClient` SPI 实现：扫码登录、长轮询收消息、文本回复。取自 `pom.xml` 的官方描述：「微信 iLink Bot 协议实现：扫码登录、消息收发、AI 回复」。
>
> 版本：`4.0.0.51`｜JDK：`25`（仓库统一 `maven.compiler.source/target=25`）｜包名：`com.chua.ilink.support.bot`（组件自 `4.0.0.42` 起提供，见源码 `@since`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 它是什么 / 不是什么](#2-它是什么--不是什么)
- [3. 核心 API 与速查表](#3-核心-api-与速查表)
- [4. 快速开始](#4-快速开始)
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
    <artifactId>utils-support-ilink-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 一句话定位

基于 `https://ilinkai.weixin.qq.com` 的微信 iLink Bot API，实现 `utils-support-common-starter` 中的 `com.chua.common.support.ai.bot.BotClient` 统一抽象：扫码登录换取机器人令牌 → 长轮询接收用户消息 → 以文本形式回复。名字里的 "starter" 指**以 SPI 形式挂接**（SPI 名 `ilink`），并非 Spring Boot starter——本模块**不依赖 Spring**。

### 1.3 源码构成

模块很小，只有 3 个 Java 类型 + 2 个 SPI 注册文件：

| 类型 | 职责 |
| --- | --- |
| `ILinkBotClient` | 核心客户端，实现 `BotClient` 的全部接口方法 |
| `ILinkBotClientFactory` | SPI 工厂（`@Spi("ilink")`），内含静态嵌套 `Builder` |
| `ContextTokenLoader` | 本模块定义的函数式接口：重启后回查持久化的上下文令牌 |

---

## 2. 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 协议实现 | 微信 iLink Bot API 的 HTTP 客户端：`/ilink/bot/get_bot_qrcode`、`/ilink/bot/get_qrcode_status`、`/ilink/bot/getupdates`、`/ilink/bot/sendmessage` | 不是微信网页版 / 公众号 / 企业微信协议 |
| Bot 抽象 | `BotClient` SPI 的一种实现，SPI 名 `ilink`，经 `BotClient.auto("ilink")` 加载 | 不定义新的 Bot 抽象（抽象与公共模型在 `utils-support-common-starter`） |
| 能力范围 | 扫码登录、长轮询收**文本**消息、发**文本**消息、群文本发送 | 不支持媒体收发、群/用户列表、@提及（见第 8 节） |
| 依赖 | 仅依赖 `utils-support-common-starter`（HTTP 用其 `HttpClientFactory`，JSON 用其 `Json`） | 不依赖 Spring、不依赖数据库、不内嵌 AI 模型 |

---

## 3. 核心 API 与速查表

### 3.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `new ILinkBotClient()` 或 `BotClient.auto("ilink")` | 也可 `BotClient.builder("ilink")...build()`（Builder 仅覆盖基础配置项） |
| 扫码登录 | `loginWithQR()` | 返回 botId，失败返回 `null`；配合 `qrcodeListener(...)` 展示二维码 |
| 启动 / 停止 | `start()` / `stop()` / `isRunning()` | `start()` 无令牌时先扫码登录，再进入阻塞轮询；建议放独立线程 |
| 收消息 | `addMessageListener(BotMessageListener)` | 每条入站消息回调 `onMessage(BotInboundMessage)` |
| 回复 / 发文本 | `sendText(toUser, content)` | 同步返回 `BotSendResult`；异步用 `sendTextAsync` |
| 群文本发送 | `sendToGroup(groupId, content)` | 当前实现等价于按用户发送 |
| 已有令牌启动 | `token(t).accountId(id).start()` | 跳过扫码，直接用已保存令牌轮询 |
| 重启后令牌回查 | `contextTokenLoader(ContextTokenLoader)` | 内存令牌缺失时回调 `load(botId, userId)` |

### 3.2 ILinkBotClient 公开方法全表

配置与生命周期：

| 方法 | 说明 |
| --- | --- |
| `configure(String token, String secret, String encodingAesKey)` | 一次设置三件凭证 |
| `token(String)` / `secret(String)` / `encodingAesKey(String)` | 链式单项设置；`secret`、`encodingAesKey` 仅保存，本模块协议逻辑未使用 |
| `baseUrl(String)` | 覆盖 API 基础地址（默认 `https://ilinkai.weixin.qq.com`） |
| `connectTimeoutMillis(long)` / `readTimeoutMillis(long)` | 默认 10 秒 / 40 秒 |
| `accountId(String)` | 显式设置机器人标识；从已保存令牌启动时必须调用 |
| `qrcodeListener(QrcodeListener)` | 二维码生命周期回调 |
| `contextTokenLoader(ContextTokenLoader)` | 上下文令牌回查器 |
| `configSaveOrLoader(ConfigSaveOrLoader)` | 接口要求的方法；本实现为空实现，传入不生效 |
| `loginWithQR()` | 扫码登录，返回 botId |
| `start()` / `startPolling()` | 启动（无令牌先扫码）；`startPolling()` 为阻塞式轮询循环 |
| `stop()` / `shutdown()` / `isRunning()` | 停止轮询 / 查询运行状态 |

消息收发：

| 方法 | 说明 |
| --- | --- |
| `sendText(String toUser, String content)` | 发文本，返回 `BotSendResult` |
| `sendTextAsync(String, String)` | 异步版，返回 `CompletableFuture<BotSendResult>` |
| `sendImage` / `sendVoice` / `sendVideo` / `sendFile` | 媒体发送：当前一律返回 `BotSendResult.fail(-1, "媒体发送暂不支持")` |
| `send(BotOutboundMessage)` / `sendAsync(BotOutboundMessage)` | 通用出站消息，当前取其 `toUser` 与 `content` 走文本发送 |
| `sendToGroup(String, String)` / `sendToGroupAsync(String, String)` | 群文本发送（等价于按用户发送） |
| `sendToGroupMention(String, String, List<String>)` 及异步版 | 忽略被提及用户列表，退化为 `sendToGroup` |
| `listGroups()` / `listUsers()` | 返回空列表 `List.of()`，未对接接口 |
| `addMessageListener(BotMessageListener)` / `removeMessageListener(BotMessageListener)` | 消息监听器注册 / 移除（真实生效） |
| `addErrorListener(BotErrorListener)` / `userStore(BotUserStore)` | 空实现，传入不生效 |
| `getConfig()` | 返回 `Map.of("baseUrl", baseUrl)` |

### 3.3 协议要点（登录与收发流程）

- **扫码登录**：`GET /ilink/bot/get_bot_qrcode?bot_type=3` 取得 `qrcode` 与 `qrcode_img_content`；随后长轮询 `GET /ilink/bot/get_qrcode_status?qrcode=xxx`（单次最长约 35s，最多 6 次、合计约 3 分钟），状态机 `wait` → `scaned` → `confirmed` / `expired`（服务端拼写为 `scaned`）。`confirmed` 响应直接携带凭证字段 `bot_token` / `ilink_bot_id` / `ilink_user_id`（兼容驼峰 `botToken`、`bot_id` / `botId`、`user_id` / `userId`），无需再调登录接口。
- **收消息**：`POST /ilink/bot/getupdates`，请求体含 `base_info`（`channel_version=2.4.6`、`bot_agent=OpenClaw`）与游标 `get_updates_buf`；响应从 `msgs`（兼容 `messages`）取消息列表，并用响应中的 `get_updates_buf` 推进游标。空轮询间隔 2 秒，异常间隔 5 秒。
- **发消息**：`POST /ilink/bot/sendmessage`，请求体为 `{"msg": {...}, "base_info": {...}}` 包裹结构；`msg` 含 `from_user_id`、`to_user_id`、`client_id`（`openclaw-weixin-` + 纳秒时间戳）、`message_type=2`、`message_state=2`、`context_token`（如有）、`item_list=[{"type":1,"text_item":{"text":...}}]`。成功判定 = 响应含 `message_id`（与 `getupdates` 的 `ret=0` 判定规则不同）。
- **鉴权头**：所有请求带 `iLink-App-Id: bot` 与 `iLink-App-ClientVersion: 132102`；业务 POST 另带 `AuthorizationType: ilink_bot_token`、`Authorization: Bearer <token>`、`X-WECHAT-UIN`（随机 uint32 的十进制字符串再 Base64）；GET 仅附加 `Authorization: Bearer <token>`。
- **会话失效**：`getupdates` 返回 `ret=-14` 或 `errcode=-14` 判为会话过期、`ret=-1` 判为令牌无效；两者都会停止轮询，并经 `QrcodeListener.error` 提示「会话过期，请重新扫码绑定」/「Bot 凭证已失效，请重新扫码绑定」。
- **回复窗口**：`sendmessage` 返回 `ret=-2`（原始文案 prepare failed）时，转写为友好提示：「回复窗口不可用：用户超过 24 小时未主动发消息，或 24 小时内 10 条主动消息配额已用完；需等用户再次发消息后才能回复。」

### 3.4 context_token 机制

iLink 入站消息携带 `context_token`，回复 / 主动发送时需原样回传。本实现按「用户 id → 令牌」缓存在内存 `ConcurrentHashMap` 中；发送时若内存缺失（典型为进程重启后）且配置了 `ContextTokenLoader`，则回调 `load(botId, userId)` 回查最近一条持久化令牌并回填缓存。收到新消息时会用消息中的 `context_token` 刷新缓存。

---

## 4. 快速开始

最小可运行示例（扫码登录 + 收到文本即回复）：

```java
import com.chua.common.support.ai.bot.QrcodeListener;
import com.chua.ilink.support.bot.ILinkBotClient;

public class ILinkQuickStart {

    public static void main(String[] args) {
        ILinkBotClient client = new ILinkBotClient();

        // 1. 二维码生命周期回调（QrcodeListener 的 5 个方法均为 default，按需覆写）
        client.qrcodeListener(new QrcodeListener() {
            @Override
            public void newQrcode(String url, String key) {
                System.out.println("请用微信扫码登录: " + url);
            }

            @Override
            public void confirmed(String token, String botId, String userId) {
                System.out.println("登录成功 botId=" + botId);
                // 建议在此持久化 token / botId，供下次直接 client.token(token).accountId(botId) 启动
            }
        });

        // 2. 收到消息 → 回复文本
        client.addMessageListener(msg ->
                client.sendText(msg.getFromUser(), "收到: " + msg.getContent()));

        // 3. 启动：无令牌时先扫码登录，随后 startPolling() 阻塞轮询，建议放独立线程
        new Thread(client::start, "ilink-bot").start();
    }
}
```

若已有保存的机器人令牌，可跳过扫码：

```java
ILinkBotClient client = new ILinkBotClient()
        .token(savedToken)
        .accountId(savedBotId);      // 从令牌启动时必须显式补 botId
new Thread(client::start, "ilink-bot").start();
```

也可经 SPI 工厂创建（`Builder` 只覆盖 token / secret / encodingAesKey / baseUrl / 两个超时 / configSaveOrLoader 这些基础项）：

```java
import com.chua.common.support.ai.bot.BotClient;

BotClient client = BotClient.auto("ilink");
// 或
BotClient client2 = BotClient.builder("ilink")
        .token(savedToken)
        .connectTimeoutMillis(10_000)
        .readTimeoutMillis(40_000)
        .build();
```

发送结果判定（`BotSendResult` 为 Lombok `@Data`）：

```java
BotSendResult result = client.sendText(toUser, "hello");
if (!result.isSuccess()) {
    System.out.println(result.getErrorCode() + ": " + result.getErrorMessage());
}
```

---

## 5. 配置项

本模块**没有**任何配置文件与外部配置键：模块内不存在 `application.yml` / `application.properties` 等资源，也没有 `@ConfigurationProperties` 之类的配置绑定。全部配置通过代码链式设置：

| 配置项 | 设置方法 | 默认值 | 说明 |
| --- | --- | --- | --- |
| API 基础地址 | `baseUrl(String)` | `https://ilinkai.weixin.qq.com` | 官方地址，一般无需修改 |
| 机器人令牌 | `token(String)` / `configure(...)` | 无 | 扫码登录后获得，或从持久化导入 |
| 密钥 | `secret(String)` / `configure(...)` | 无 | 仅保存；本模块协议逻辑未使用 |
| 加密 AES 键 | `encodingAesKey(String)` / `configure(...)` | 无 | 仅保存；本模块协议逻辑未使用 |
| 连接超时 | `connectTimeoutMillis(long)` | `10000` 毫秒 | |
| 读取超时 | `readTimeoutMillis(long)` | `40000` 毫秒 | 长轮询依赖较长的读取超时 |
| 机器人标识 | `accountId(String)` | 登录成功后自动获得 | 从已保存令牌启动时必须显式设置 |
| 上下文令牌回查 | `contextTokenLoader(ContextTokenLoader)` | 无 | 重启后回查持久化令牌 |
| 配置持久化加载器 | `configSaveOrLoader(ConfigSaveOrLoader)` | 无 | 接口要求的方法；本实现为空实现，传入不生效 |

固定协议常量（不可配置，源码硬编码）：`iLink-App-Id=bot`、`iLink-App-ClientVersion=132102`（对应渠道版本 `2.4.6`）、`bot_agent=OpenClaw`。

---

## 6. SPI 扩展点

本模块**本身是** utils Bot 抽象的一个 SPI 实现，不新增需要他人实现的 SPI 接口。

模块携带的 SPI 注册文件：

| 注册文件 | 内容 |
| --- | --- |
| `META-INF/extensions/com.chua.common.support.ai.bot.BotClient$Factory` | `ilink=com.chua.ilink.support.bot.ILinkBotClientFactory` |
| `META-INF/services/com.chua.common.support.ai.bot.BotClient` | `com.chua.ilink.support.bot.ILinkBotClient`、`com.chua.ilink.support.bot.ILinkBotClientFactory` |

- 使用方按名称加载：`BotClient.auto("ilink")`，内部经 `ServiceProvider.of(BotClient.Factory.class).getExtension("ilink")` 命中 `ILinkBotClientFactory`。
- 本模块对使用方暴露的回调扩展点：
  - `ContextTokenLoader`（本模块定义的 `@FunctionalInterface`）：`String load(String botId, String userId)`，返回 `null` 表示无可用令牌。
  - `QrcodeListener`、`BotMessageListener`、`BotErrorListener`、`BotUserStore` 等回调接口均定义在 `utils-support-common-starter` 的 `com.chua.common.support.ai.bot` 包中，本模块只消费。

---

## 7. 构建与依赖

```xml
<parent>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-cloud-parent</artifactId>
    <version>4.0.0.51</version>
    <relativePath>../pom.xml</relativePath>
</parent>
<artifactId>utils-support-ilink-starter</artifactId>
```

- **唯一依赖**：`com.chua:utils-support-common-starter`（`${project.version}`）。HTTP 走其 `HttpClientFactory.getClient()`，JSON 走其 `Json`，集合走其 `Wraps`。
- **所属聚合**：`utils-support-cloud-parent`（云服务集成父模块）→ `utils-support-parent-starter`，仓库版本 `4.0.0.51`。
- **JDK**：25（仓库统一 `maven.compiler.source/target=25`）。
- **编译验证**：`mvn -o -q compile -DskipTests`，`EXIT=0` 为通过。

---

## 8. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| 媒体消息发送（`sendImage` / `sendVoice` / `sendVideo` / `sendFile`） | 未实现，统一返回 `BotSendResult.fail(-1, "媒体发送暂不支持")` |
| 群列表 / 用户列表（`listGroups` / `listUsers`） | 返回空列表 `List.of()`，未对接任何接口 |
| 群 @提及（`sendToGroupMention` / `sendToGroupMentionAsync`） | 忽略被提及用户列表，退化为普通群文本发送 |
| 错误监听器（`addErrorListener`） | 空实现，传入不生效；错误经 `QrcodeListener.error` 回调与日志暴露 |
| 用户存储（`userStore(BotUserStore)`） | 空实现，传入不生效 |
| AI 回复 | 不在本模块：本模块只负责 iLink 协议收发；收到消息后如何生成回复，由使用方在 `BotMessageListener` 中自行实现 |
| Spring 自动装配 / 配置中心接入 | 无；本模块不依赖 Spring，也不读取任何配置文件 |
| 非文本入站消息 | 仅解析 `item_list[].text_item.text`，入站 `BotInboundMessage` 的 `type` 恒为 `Type.TEXT` |

---

> 本文所有类名、方法名、端点、请求/响应字段、常量与默认值均取自本模块源码：`pom.xml`、`ILinkBotClient.java`、`ILinkBotClientFactory.java`、`ContextTokenLoader.java`、`META-INF/extensions/...`、`META-INF/services/...`，以及 common-starter 中的 `BotClient` 契约接口。
