# utils-support-doubao-starter

豆包/火山引擎（火山方舟 Ark）大模型对话集成模块：基于 Ark SDK 提供 `ChatClient` 的 SPI 实现，用于调用豆包系列模型的对话接口。

- **坐标**：`com.chua:utils-support-doubao-starter`，版本 `4.0.0.51`（继承父模块 `utils-support-cloud-parent`）
- **JDK**：25（根 POM `java.version=25`，`maven.compiler.release=25`）
- **包名**：`com.chua.doubao.support`
- **核心类**：`DoubaoChatClient`（`@since 4.0.0.42`）

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界（这些能力不在本模块）](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

| 判断 | 说明 |
|------|------|
| ✅ 是 | 豆包/火山引擎 Ark 大模型的**对话客户端**：同步对话、流式对话、多轮上下文、思考模式、联网搜索、技能注入、Token 用量统计 |
| ✅ 是 | `com.chua.common.support.ai.chat.ChatClient` 的 SPI 实现，SPI 名称 `doubao`（注解同时声明 `volcengine`） |
| ✅ 是 | 零 Spring 依赖的工具模块：无自动装配、无配置文件键，全部配置通过 `ChatClientSetting` 传入 |
| ❌ 不是 | 图像/视频生成客户端：未覆写 `generateImage` / `generateVideo`，调用会抛 `UnsupportedOperationException` |
| ❌ 不是 | 模型目录/定价来源：未覆写 `models()` / `modelPricing()`，默认返回空列表 |
| ❌ 不是 | 渠道管理、路由、会话编排、指标持久化等业务模块（按仓库口径属于上层 starter） |

---

## 核心 API 与速查表

| 入口 | 说明 |
|------|------|
| `ChatClient.create("doubao", apiKey)` | 按 SPI 创建豆包对话客户端（最常用入口） |
| `ChatClient.create(ChatClientSetting)` | 携带完整配置创建；`provider` 填 `doubao` |
| `new DoubaoChatClient(setting)` | 直接实例化（公开构造器，等价于 SPI 创建） |
| `chatSync(prompt)` | 同步对话，返回完整文本 |
| `chat(prompt, consumer)` | 流式对话，按 `ChatResponse.State` 回调 START / STREAMING / STOP / ERROR |
| `chat(prompt, consumer, onComplete, onError)` | 流式对话 + 完成/错误回调 |
| `addUserHistory` / `addAssistantHistory` / `history(list)` / `newChat()` | 多轮对话上下文管理 |
| `thinking(true)` / `smartSearch(true)` / `skill(skillManager)` | 思考模式 / 联网搜索（`web_search` 工具）/ 技能提示注入 |
| `close()` | 释放底层 ArkService 线程资源 |
| `indicator()` | 模型指标能力（价格/上下文窗口等），见下文 |
| `getSetting()` | 取回创建时的 `ChatClientSetting` |

流式回调字段速查：

| `ChatResponse` 字段 | 出现时机 | 内容 |
|---------------------|----------|------|
| `state = START` | 请求发出时 | 无载荷 |
| `state = STREAMING` + `content` | 每个内容分片 | 正文增量文本 |
| `state = STREAMING` + `reasoningContent` | 思考模式下 | 思维链增量文本 |
| `state = STOP` + `usage` | 正常结束 | `AiUsage`：`inputTokens` / `outputTokens` / `totalTokens` / `durationMillis`，`provider=doubao` |
| `state = ERROR` + `errorMessage` | 请求失败 | 异常消息 |

默认值速查：模型 `doubao-1.5-pro-32k`；温度 `0.3`；`maxTokens` `2048`；地址 `https://ark.cn-beijing.volces.com/api/v3`；请求超时 90s、连接超时 30s（构造时固定）。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-doubao-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 最短同步示例

```java
try (ChatClient client = ChatClient.create("doubao", "你的火山方舟 API Key")) {
    String answer = client
            .model("doubao-1.5-pro-32k")
            .system("你是一个简洁的助手")
            .chatSync("你好，用一句话介绍你自己");
    System.out.println(answer);
}
```

### 3. 流式示例

```java
ChatClient client = ChatClient.create("doubao", "你的火山方舟 API Key");
client.chat("讲个 100 字的小故事", response -> {
    switch (response.getState()) {
        case STREAMING -> System.out.print(response.getContent());
        case STOP      -> System.out.println("\n[完成] tokens=" + response.getUsage().getTotalTokens());
        case ERROR     -> System.err.println(response.getErrorMessage());
        default        -> { }
    }
});
client.close();
```

### 4. 完整配置示例

```java
ChatClientSetting setting = ChatClientSetting.builder()
        .provider("doubao")
        .appKey("你的火山方舟 API Key")
        .baseUrl("https://ark.cn-beijing.volces.com/api/v3")   // 不填用默认地址
        .model("doubao-1.5-pro-32k")
        .temperature(0.3)
        .maxTokens(2048)
        .system("你是一个严谨的助手")
        .proxy("socks5://127.0.0.1:1080")                     // 可选
        .build();

ChatClient client = ChatClient.create(setting);
```

---

## 配置项

本模块**没有 Spring 配置文件键**（`pom.xml` 不含 Spring 依赖，也不存在 `application.*` 约定）：所有配置通过 `ChatClientSetting` 构造传入。本实现实际读取的字段：

| `ChatClientSetting` 字段 | 必填 | 说明 |
|--------------------------|------|------|
| `appKey` | 是 | 火山方舟 API Key，透传给 Ark SDK |
| `provider` | 是 | SPI 查找名，填 `doubao` |
| `baseUrl` | 否 | 自定义 Ark API 地址；空/空白时用默认 `https://ark.cn-beijing.volces.com/api/v3` |
| `model` | 否 | 模型名；为空时请求回退为 `doubao-1.5-pro-32k` |
| `temperature` | 否 | 温度；为空时按 `0.3` 发送 |
| `maxTokens` | 否 | 最大输出 Token；为空时按 `2048` 发送 |
| `system` | 否 | 系统提示词；非空时作为 SYSTEM 消息下发 |
| `proxy` | 否 | 代理：`socks5://host:port`、`socks://host:port` 走 SOCKS；`http://`、`https://` 或裸 `host:port` 走 HTTP（缺省端口 80）；解析失败时忽略代理 |
| `metricsUrl` | 否 | 自定义模型指标端点；配置后 `indicator()` 优先走该端点（`HttpChatClientIndicator`） |

本实现**未读取**的常用字段（列出以免误配）：`timeoutMillis`（超时固定为请求 90s / 连接 30s）、`appSecret`、`topP`、`stop`、`seed`、`responseFormat`、`extraHeaders`、`authScheme` / `authHeader` / `authPrefix`、`tools` / `toolChoice`、`routes`。链式方法 `thinkingEffort(...)` 与 `session(...)` 当前仅保存值，未映射到 Ark 请求参数。

---

## SPI 扩展点

- **扩展接口**：`com.chua.common.support.ai.chat.ChatClient`
- **实现类**：`com.chua.doubao.support.DoubaoChatClient`
- **SPI 注解**：`@Spi({"doubao", "volcengine"})`
- **注册文件**：`META-INF/extensions/com.chua.common.support.ai.chat.ChatClient`，内容为 `doubao=com.chua.doubao.support.DoubaoChatClient`（按名称 `doubao` 查找）
- **获取实例**：`ChatClient.create(setting)` 内部经 `ServiceProvider.of(ChatClient.class)` 按 `provider` 名实例化；若 provider 未注册但配置了 `baseUrl`，会按 OpenAI 兼容协议兜底走 `openai` 扩展
- **GraalVM 原生镜像**：自带 `META-INF/native-image/com.chua/utils-support-doubao-starter/reflect-config.json`（注册 `DoubaoChatClient` 全量反射）与 `resource-config.json`（打包 `META-INF/extensions/**`）

---

## 构建与依赖

```text
utils-support-doubao-starter
├── com.chua:utils-support-common-starter          （同版本，提供 ChatClient SPI / ChatClientSetting）
├── com.volcengine:volcengine-java-sdk-ark-runtime （版本由父 POM 统一管理）
├── org.slf4j:slf4j-api
└── org.projectlombok:lombok                       （provided）
```

编译验证（模块目录内）：

```bash
mvn -o -q compile -DskipTests
```

要求 JDK 25（根 POM `java.version=25`，`maven.compiler.release=25`）。

---

## 边界（这些能力不在本模块）

- **图像 / 视频生成**：`generateImage(...)` / `generateVideo(...)` 未覆写，调用抛 `UnsupportedOperationException`
- **模型目录与定价**：`models()` / `listModels()` / `modelPricing()` 未覆写，返回空列表
- **中转站真伪探测**：`probe()` 未覆写，调用抛 `UnsupportedOperationException`
- **Spring 自动装配**：无自动配置类、无配置属性键；实例通过 SPI 手工创建
- **业务能力**：渠道管理、路由选择、会话编排、用量持久化、指标库等属于上层模块；本模块只提供豆包侧的 `ChatClient` 对话实现
- **非流式调用**：底层固定走 Ark 流式接口，`chatSync` 是对流的聚合
