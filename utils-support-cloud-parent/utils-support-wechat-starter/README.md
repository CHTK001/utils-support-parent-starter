# utils-support-wechat-starter

> 微信集成模块：把微信的 **公众号模板消息**、**小程序订阅消息**、**企业微信群机器人 Webhook** 三类推送，以及基于本机 Hook 服务的 **个人微信机器人**（收发消息 + AI 自动回复），接进 utils 体系的两个 SPI 契约（`MessagePush` / `BotClient`）。
>
> 版本：`4.0.0.51`（继承父 pom）｜JDK：`25`（父 pom `java.version=25`）｜包名：`com.chua.wechat.support`（含 `.message`、`.bot`）

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
    <artifactId>utils-support-wechat-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 消息推送 | `WechatMessagePush`：企业微信群机器人 Webhook（text / markdown）、公众号模板消息、小程序订阅消息三合一的 `MessagePush` 实现 | 不封装公众号 / 小程序的菜单、用户管理、素材、支付等其它微信接口 |
| 个人微信机器人 | `WechatPersonalBotClient`：对接**本机 Hook 服务**（如 wxhelper 注入 PC 微信后的 HTTP 服务，默认 `127.0.0.1:19088`）收发消息 | 不直接对接微信官方服务器；个人微信无官方 API，本类不承诺任何官方兼容性 |
| AI 自动回复 | `WechatAutoReplyHandler`（接 `ChatClient` 生成回复）+ `WechatReplyPolicy`（白名单 / 黑名单 / 频控 / 人工接管） | 不含风控计数、会话历史的持久化（均为进程内内存实现） |
| 角色 | utils 体系 SPI 的**实现方** | 不定义 SPI 契约；契约在 `utils-support-common-starter` |

### 1.3 类一览（源码全部 5 个顶层类型）

| 类 | SPI 名 | 实现接口 | 职责 |
| --- | --- | --- | --- |
| `com.chua.wechat.support.message.WechatMessagePush` | `wechat` | `com.chua.common.support.task.message.MessagePush` | Webhook / 公众号模板 / 小程序订阅消息推送 |
| `com.chua.wechat.support.bot.WechatPersonalBotClient` | —（由工厂创建） | `com.chua.common.support.ai.bot.BotClient` | 个人微信机器人收发消息 |
| `com.chua.wechat.support.bot.WechatPersonalBotClientFactory` | `wechat-personal` | `com.chua.common.support.ai.bot.BotClient.Factory` | 机器人客户端 SPI 工厂与构建器 |
| `com.chua.wechat.support.bot.WechatReplyPolicy` | — | —（独立风控类） | 自动回复风控守卫：黑白名单、关键词、频控、人工接管 |
| `com.chua.wechat.support.bot.WechatAutoReplyHandler` | — | `com.chua.common.support.ai.bot.BotMessageListener`、`AutoCloseable` | 调用 `ChatClient` 生成 AI 回复并发送 |

---

## 2. 核心 API 与速查表

最常查的入口：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 企业微信 Webhook 推送 | `new WechatMessagePush(new MessageEnvironment().set("wechat.webhookUrl", ...))` → `send(MessageRequest)` | `contentType` 含 `markdown` 发 Markdown，否则发 text；响应 `errcode==0` 判定成功 |
| 公众号模板消息 | 环境配 `wechat.type=mp` + `wechat.appId` / `wechat.appSecret` / `wechat.templateId`，`request.to` 传 openid | 模板参数 `templateParams` 自动转为微信 `data` 的 `{键:{value:值}}` 结构 |
| 小程序订阅消息 | 环境配 `wechat.type=mini`，其余同上 | `request.subject` 作为小程序页面 `page`；`wechat.miniprogramState` 默认 `formal` |
| SPI 创建推送 | `MessagePush.create("wechat")` | 走无参构造，`MessageEnvironment` 为空；缺配置时 `send()` 返回失败结果（不抛异常） |
| 个人微信机器人 | `BotClient.auto("wechat-personal")` 或 `new WechatPersonalBotClient()` → `callbackUrl(...)` → `start()` → `addMessageListener(...)` | 入站由 Hook 服务回调本机控制器，控制器内调 `client.deliver(body)` 分发 |
| 机器人发消息 | `sendText` / `sendImage` / `sendFile` / `sendToGroup` / `sendToGroupMention` | 群发即向群 wxid（`@chatroom` 结尾）发文本；`@` 人是把昵称拼进文本模拟 |
| AI 自动回复 | `new WechatAutoReplyHandler(botClient, chatClient, policy)` → `botClient.addMessageListener(handler)` | 单线程串行回复，按会话保留有限历史，超长回复自动拆分 |
| 风控判定 | `policy.denyReason(msg)`（返回 `null` 放行）→ 发送后 `policy.markReplied(conversation)` | 拒绝原因见 `WechatReplyPolicy.DenyReason`；`nextDelayMillis()` 给出拟人化等待间隔 |

`WechatPersonalBotClient` 要点（均为源码已验证）：

- `start()` 不启动线程：向 Hook 服务查询自身 wxid（`POST /api/userInfo`）、注册回调地址（`POST /api/http/plus`），随后置运行标志；入站消息一律由外部回调 `deliver(String rawBody)` 注入。
- `deliver()` 支持单个 JSON 对象或数组；按 `msgId` 去重（LRU 容量 512），过滤自发消息（`selfWxid`），返回本次实际分发的 `List<BotInboundMessage>`。
- 消息类型映射：`1→TEXT`、`3→IMAGE`、`34→VOICE`、`43→VIDEO`、`49→FILE`、其它→`UNKNOWN`；房间 wxid 以 `@chatroom` 结尾判定为群聊；入站消息 `eventType` 固定为 `wechat.personal.message`。
- `token(...)` 非空时所有请求附 `Authorization: Bearer` 头。
- 异步变体：`sendTextAsync` / `sendImageAsync` / `sendAsync` / `sendToGroupAsync` / `sendToGroupMentionAsync`，均返回 `CompletableFuture<BotSendResult>`。
- `send(BotOutboundMessage)` 按类型分发：`TEXT→sendText`、`IMAGE→sendImage`、`FILE→sendFile`，其余返回失败。
- `getConfig()` 返回快照键：`baseUrl`、`callbackUrl`、`selfWxid`、`running`。

---

## 3. 快速开始

### 3.1 企业微信 Webhook 推送（最短示例）

```java
MessagePush push = new WechatMessagePush(new MessageEnvironment()
        .set("wechat.webhookUrl", "<企业微信群机器人 Webhook 地址>"));

push.send(MessageRequest.builder()
        .content("部署完成")
        .build());

// Markdown：contentType 传 markdown
push.send(MessageRequest.builder()
        .contentType("markdown")
        .content("**v1.2.0** 已发布")
        .build());
```

成功判定：微信响应 `errcode == 0` 时 `MessageResponse.isSuccess()` 为 true，`messageId` 取返回的 `msgid`；失败时 `errorMessage` 携带 `errcode - errmsg`。

### 3.2 公众号模板消息 / 小程序订阅消息

```java
WechatMessagePush push = new WechatMessagePush(new MessageEnvironment()
        .set("wechat.type", "mp")                     // 小程序改 "mini"
        .set("wechat.appId", "你的AppID")
        .set("wechat.appSecret", "你的AppSecret")
        .set("wechat.templateId", "默认模板ID"));

push.send(MessageRequest.builder()
        .to("用户openid")                             // 接收人 openid，必填
        .templateId("本次使用的模板ID")                // 可省略，回落 wechat.templateId
        .subject("https://你的跳转地址")               // mp 模式作为跳转 url；mini 模式作为 page
        .templateParams(Map.of("thing1", "值", "time2", "值"))
        .build());
```

### 3.3 个人微信机器人收发

```java
WechatPersonalBotClient client = (WechatPersonalBotClient) BotClient.auto("wechat-personal");
client.callbackUrl("http://127.0.0.1:8080/wechat/callback");  // 本机可被 Hook 服务访问的地址
client.start();                                               // 查询自身 wxid 并注册回调
client.addMessageListener(msg -> client.sendText(msg.getFromUser(), "已收到"));
// Hook 服务回调控制器内：client.deliver(requestBody);
```

也可走 SPI 构建器（平台名 `wechat-personal`）：

```java
BotClient bot = BotClient.builder("wechat-personal")
        .baseUrl("http://127.0.0.1:19088")            // Hook 服务地址，默认即此
        .token("Hook服务鉴权令牌")                     // 可选，Bearer 头
        .build();
```

> `callbackUrl(...)` 是 `WechatPersonalBotClient` 与其 Builder 的专有方法；通过 `BotClient` 接口引用时需拿到具体类型后设置。

### 3.4 AI 自动回复（含风控）

```java
WechatReplyPolicy policy = new WechatReplyPolicy()
        .allow("wxid_好友或群")          // 白名单；为空表示不限制对象
        .block("wxid_黑名单")            // 黑名单优先于白名单
        .allowGroups(false)              // 群聊不自动回复
        .maxPerUserPerMinute(5)          // 单会话每分钟上限，默认 5
        .maxPerDay(200);                 // 当日总量上限，默认 200

WechatAutoReplyHandler handler = new WechatAutoReplyHandler(client, chatClient, policy)
        .systemPrompt("你是微信里的助手，回答简短、口语化。");
client.addMessageListener(handler);

// 人工接管 / 交还：policy.pause() / policy.resume()
// 停止自动回复线程：handler.close();
```

---

## 4. 配置项

### 4.1 WechatMessagePush（`MessageEnvironment` 键）

| 键 | 必填 | 说明 |
| --- | --- | --- |
| `wechat.type` | 否，默认 `webhook` | 推送类型 `webhook` / `mp` / `mini`；请求 `contentType` 含 `webhook` / `mini` / `mp`（或 `template`）时优先按其判定 |
| `wechat.webhookUrl` | webhook 模式必填 | 企业微信群机器人 Webhook 地址；缺失时 `send()` 返回失败「wechat.webhookUrl 配置项必填」 |
| `wechat.appId` | mp / mini 必填 | 公众号 / 小程序 AppID；缺失时返回失败结果 |
| `wechat.appSecret` | mp / mini 必填 | 公众号 / 小程序 AppSecret |
| `wechat.templateId` | 否 | 默认模板 ID；请求的 `templateId` 优先，两者皆空返回失败结果 |
| `wechat.miniprogramState` | 否，默认 `formal` | 小程序订阅消息的 `miniprogram_state` |

补充：mp / mini 模式发送前先取 access_token（`https://api.weixin.qq.com/cgi-bin/token`），按 `appId:appSecret` 维度缓存，`expires_in` 缺省按 7200 秒、提前 200 毫秒判过期。

### 4.2 WechatPersonalBotClient（无外部配置键，全部经 setter / builder）

| 参数 | 缺省 | 说明 |
| --- | --- | --- |
| `baseUrl` | `http://127.0.0.1:19088` | 本机 Hook 服务地址 |
| `token` | — | Hook 服务鉴权令牌，非空时以 `Authorization: Bearer` 下发 |
| `callbackUrl` | — | 入站回调地址，`start()` 时注册给 Hook 服务；为空表示由外部自行调用 `deliver()` |
| `selfWxid` | `start()` 时向 Hook 服务查询 | 当前登录账号 wxid，用于过滤自发消息 |
| `connectTimeoutMillis` / `readTimeoutMillis` | `5000` / `15000` | HTTP 建连 / 读取超时 |

Hook 服务端点常量（取自 wxhelper，换 Hook 实现时只需改这些路径与入站键名）：发送文本 `/api/sendTextMsg`、发送图片 `/api/sendImageMsg`、发送文件 `/api/sendFileMsg`、注册回调 `/api/http/plus`、查询登录账号 `/api/userInfo`。

### 4.3 WechatReplyPolicy（缺省值）

| 参数 | 缺省 | 说明 |
| --- | --- | --- |
| 白名单 / 黑名单 | 空 | 白名单为空不限制对象；黑名单优先于白名单 |
| 触发关键词 | 空 | 为空表示任何文本消息都触发；非空时正文须包含其一 |
| `allowGroups` | `false` | 群聊自动回复开关 |
| `maxPerUserPerMinute` | `5` | 单会话每分钟回复上限 |
| `maxPerDay` | `200` | 当日回复总量上限 |
| `minIntervalMillis` | `3000` | 同一对端两次回复的最小间隔 |
| `jitterMillis` | `2000` | 追加在最小间隔上的随机抖动上限 |

### 4.4 WechatAutoReplyHandler（缺省值）

| 参数 | 缺省 | 说明 |
| --- | --- | --- |
| `historySize` | `12` | 每会话喂给模型的历史消息条数（LRU，最多保留 256 个会话） |
| `maxReplyLength` | `1000` | 单条回复长度上限，超出按换行 / 句号 / 叹号 / 问号拆分为多条 |
| `systemPrompt` | `你是微信里的助手，回答简短、口语化，不要用 markdown 标记。` | 人设提示词 |

回复在单个守护线程（线程名 `wechat-auto-reply`）串行执行，队列容量 64，堆积超限直接丢弃本次回复；`close()` 最多等待 5 秒处理在途回复。

---

## 5. SPI 扩展点

本模块是 SPI **实现方**，注册文件位于 `src/main/resources/META-INF/extensions/`：

| 接口文件 | 注册名 | 实现 |
| --- | --- | --- |
| `com.chua.common.support.task.message.MessagePush` | `wechat` | `WechatMessagePush`（`@SpiDescribe` 声明可选参数 `wechat.type`、`wechat.webhookUrl`、`wechat.appId`、`wechat.appSecret`、`wechat.templateId`） |
| `com.chua.common.support.ai.bot.BotClient$Factory` | `wechat-personal` | `WechatPersonalBotClientFactory` |

使用方式：

```java
MessagePush push = MessagePush.create("wechat");        // 内部走 SPI，环境为空（见 4.1）
BotClient   bot  = BotClient.auto("wechat-personal");   // 或 BotClient.builder("wechat-personal")...
```

扩展方向：新增微信能力时应优先扩展 `WechatMessagePush` / `WechatPersonalBotClient` 本身；与其它平台共用的抽象应下沉到 `utils-support-common-starter` 的 SPI 契约，而不是在本模块另起一套接口。

---

## 6. 构建与依赖

- 父链：`utils-support-parent-starter`（`4.0.0.51`，`java.version=25`）→ `utils-support-cloud-parent` → `utils-support-wechat-starter`。
- 编译：`mvn -o -q compile -DskipTests`，`EXIT=0` 为通过。
- 依赖（`pom.xml`）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}` | SPI 契约、HTTP 客户端、JSON、集合工具 |
| `org.slf4j:slf4j-api` | 父 pom 管理 | 日志门面 |
| `org.projectlombok:lombok` | provided | 编译期代码生成 |

- 本模块无 GraalVM native-image 元数据、无 Spring 自动装配（`src/main/resources` 仅含上述两个 SPI 注册文件）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 / 说明 |
| --- | --- |
| `MessagePush` / `BotClient` 等契约与统一消息模型 | `utils-support-common-starter`；本模块只是微信实现 |
| 语音、视频消息发送（个人微信） | `sendVoice` / `sendVideo` 直接返回失败：Hook 服务暂不支持 |
| 群列表 | `listGroups()` 返回空列表：Hook 服务无稳定的会话列表接口，群与联系人由入站消息逐步沉淀到 `userStore`（默认 `InMemoryBotUserStore`，可经 `userStore(...)` 换持久化实现） |
| 模板持久化 | `WechatMessagePush` 的模板表在内存中，需自行 `registerTemplate`；`listTemplates` / `getTemplate` 只读该内存表 |
| 公众号 / 小程序的菜单、用户管理、素材、微信支付、小程序登录等其它微信接口 | 源码未提供，未封装 |
| Hook 服务的安装与 PC 微信注入 | 本模块只对接已运行的 Hook 服务 HTTP 接口，不负责安装、注入与封号风险规避 |
| Spring Boot 自动装配 / 管理端点 | 本模块是纯工具库，不含自动配置 |

> **合规风险**：个人微信无官方 API，`WechatPersonalBotClient` 依赖注入 PC 微信进程的 Hook 服务，该做法违反《微信个人账号使用规范》，可能被封号。只应在小号上使用，并在监听器 / `WechatReplyPolicy` 里自行加入白名单与频控。
