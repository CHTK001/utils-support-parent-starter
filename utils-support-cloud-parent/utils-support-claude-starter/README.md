# utils-support-claude-starter

> utils 体系 AI 子系统中的 **Claude 服务商实现模块**：把 `utils-support-common-starter` 定义的 `ChatClient` SPI 落到 Anthropic Claude Messages API 上，提供同步 / 回调两种对话入口、多轮上下文、深度思考与技能注入。
>
> 版本：`4.0.0.51`（继承父 POM `utils-support-cloud-parent`）｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.claude.support`

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
    <artifactId>utils-support-claude-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 抽象 | `ChatClient` SPI 的 **Claude 实现**（`@Spi({"claude"})`），走 **Anthropic 原生 Messages 协议**（`type()` 返回 `ChatClientType.PROVIDER_NATIVE`） | 不是 OpenAI 兼容 Chat Completions 客户端 |
| 能力 | 同步 / 回调对话、多轮上下文、系统提示词、深度思考（`budget_tokens`）、技能注入、HTTP 代理、Token 用量统计 | 不含文件附件、图像 / 视频生成、真伪探测（probe）、SSE 流式输出 |
| 依赖 | `utils-support-common-starter`（HTTP / JSON / SPI 基建）+ `net.cyclingbits:claude-code-sdk-java:1.2.0`（POM 声明，当前源码未直接引用）+ `slf4j-api` + `lombok`（provided） | 不依赖 Spring、不依赖数据库、不依赖其它业务 starter |

模块内只有一个实现类：`ClaudeChatClient`（`@author CH`，`@since 4.0.0.42`），基于 Anthropic Claude Messages API 通过 HTTP 协议调用 Claude 模型的对话接口，支持 Claude 3 / 3.5 系列模型。

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `ChatClient.create("claude", appKey)` | 也可 `new ClaudeChatClient(setting)`（唯一构造器）；`setting` 可用 `ChatClientSetting.builder()` 全参构建 |
| 同步对话 | `chatSync(prompt)` | 阻塞直到返回 |
| 回调对话 | `chat(prompt, consumer)` | `START → STOP / ERROR` 事件回调 |
| 回调对话（带完成 / 异常钩子） | `chat(prompt, consumer, onComplete, onError)` | 失败时回调 `onError` |
| 多轮上下文 | `addUserHistory` / `addAssistantHistory` / `history(List<ChatMessage>)` / `newChat()` | 追加、整体注入或清空历史 |
| 深度思考 | `thinking(true).thinkingEffort("low" / "medium" / "high")` | 映射为请求体 `thinking.budget_tokens` |
| 技能注入 | `skill(SkillManager)` | 经 `SkillPrompt.inject` 拼入 `system` |
| 模型指标 | `indicator()` | 价格 / 上下文窗口等指标能力入口 |

---

## 2. 核心 API 与速查表

### 2.1 方法总表（均与源码一致）

| 方法 | 说明 |
| --- | --- |
| `ClaudeChatClient(ChatClientSetting setting)` | 构造时读取 `model` / `temperature` / `maxTokens` / `system` 作为默认值 |
| `model(String)` | 覆盖模型名 |
| `temperature(double)` | 覆盖温度参数 |
| `maxTokens(int)` | 覆盖最大输出 Token 数 |
| `system(String)` | 覆盖系统提示词 |
| `thinking(boolean)` | 是否启用深度思考 |
| `thinkingEffort(String effort)` | 思考力度：`low` → 512、`high` → 2048、其它 → 1024（`budget_tokens`） |
| `smartSearch(boolean)` | 智能搜索开关（链式接收；当前请求体未使用该参数） |
| `skill(SkillManager)` | 技能管理器，非空时经 `SkillPrompt.inject(system, skillManager)` 注入系统提示词 |
| `addImage(String imageUrl)` | 记录图片 URL（见 [边界](#7-边界这些能力不在本模块)） |
| `addUserHistory(String)` | 追加一条 `role=user` 历史 |
| `addAssistantHistory(String)` | 追加一条 `role=assistant` 历史 |
| `history(List<ChatMessage>)` | 注入外部完整历史；非空时**替代**内部 `history` 参与请求 |
| `session(String sessionId)` | 记录会话标识 |
| `addAttachment(String, byte[], String)` | 抛 `UnsupportedOperationException("该服务商不支持文件附件")` |
| `addAttachmentUrl(String, String, String)` | 抛 `UnsupportedOperationException("该服务商不支持远程文件附件")` |
| `newChat()` | 清空内部历史与图片列表、置空外部历史 |
| `chatSync(String prompt)` | 同步对话，内部复用 `chat(...)` 并拼接响应内容返回 |
| `chat(String, Consumer<ChatResponse>)` | 回调式对话；异常时包装为 `RuntimeException("对话请求失败: ...")` 抛出 |
| `chat(String, Consumer<ChatResponse>, Runnable onComplete, Consumer<Throwable> onError)` | 完整回调：正常结束执行 `onComplete`，异常执行 `onError` |
| `indicator()` | 委托接口默认实现（`ChatClient.super.indicator()`）：优先用 `ChatClientSetting#getMetricsUrl()`，否则按服务商路由到聚合指标来源 |
| `type()` | 返回 `ChatClientType.PROVIDER_NATIVE`（Anthropic 原生 Messages 协议） |
| `getSetting()` | 返回构造时传入的配置 |

### 2.2 请求行为（源码默认值）

| 项 | 值 |
| --- | --- |
| 端点 | `POST {baseUrl}/messages`，`baseUrl` 缺省 `https://api.anthropic.com/v1`，末尾 `/` 会被去除 |
| 请求头 | `x-api-key: {appKey}`、`anthropic-version: 2023-06-01`，JSON 体 |
| 默认模型 | `claude-3-sonnet-20240229` |
| 默认 `max_tokens` | `2048` |
| 默认 `temperature` | `0.3` |
| 连接超时 | 30 秒（`connectTimeout(30000)`） |
| 读取超时 | 90 秒（`readTimeout(90000)`） |
| 代理 | `setting.getProxy()` 形如 `host:port` 或 `scheme://host:port` 时生效 |
| 请求体字段 | 仅 `model`、`max_tokens`、`temperature`、`messages`、`system`（非空时）、`thinking`（开启时） |

### 2.3 响应与用量

回调收到的事件顺序为 `START → STOP`（成功）或 `START → ERROR`（失败）：

- `STOP` 事件：`content` / `fullContent` 为 Claude Messages API 的**原始 JSON 响应体**；`usage` 携带 `AiUsage`（`provider="claude"`、`model`、`startTime`、`durationMillis`、`inputTokens`、`outputTokens`、`totalTokens`，取自响应 `usage.input_tokens` / `usage.output_tokens`）。
- `ERROR` 事件：非 200 响应时 `errorMessage` 为 `"Claude API 返回错误: {statusCode} - {body}"`；请求异常时为异常消息。

> **实现说明**：当前 `chat(...)` 为非流式一次性请求，不产生 `STREAMING` 事件；`chatSync(...)` 拼接的是 `STREAMING` 事件内容。要拿完整结果，建议直接用 `chat(...)` 回调读取 `STOP` 事件的 `fullContent`。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-claude-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短示例

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;

public class ClaudeDemo {
    public static void main(String[] args) {
        // provider 名固定为 "claude"（@Spi({"claude"})）
        ChatClient client = ChatClient.create("claude", System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-3-sonnet-20240229")
                .system("你是一名简洁的助手");

        client.chat("用一句话介绍 Anthropic Claude", response -> {
            switch (response.getState()) {
                case START -> System.out.println("请求开始");
                case STOP -> System.out.println(response.getFullContent()); // 原始 JSON 响应
                case ERROR -> System.err.println(response.getErrorMessage());
                default -> { }
            }
        });
    }
}
```

### 3.3 Builder 全配置示例

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

ChatClientSetting setting = ChatClientSetting.builder()
        .provider("claude")
        .appKey("sk-ant-你的密钥")
        .baseUrl("https://api.anthropic.com/v1")   // 可省略，默认即此
        .model("claude-3-sonnet-20240229")          // 可省略
        .system("你是一名严谨的助手")
        .proxy("http://127.0.0.1:7890")             // 可选
        .build();

ChatClient client = ChatClient.create(setting);
```

### 3.4 多轮对话示例

```java
client.addUserHistory("我叫张三");
client.addAssistantHistory("你好，张三。");
client.chatSync("我叫什么？");   // 历史会随 messages 一起提交
client.newChat();               // 清空历史，开启新会话
```

---

## 4. 配置项

配置载体为 `com.chua.common.support.ai.chat.ChatClientSetting`（Lombok `@Data` / `@Builder`）。本模块源码实际读取的字段如下：

| 字段 | 类型 | 本模块行为 | 缺省行为 |
| --- | --- | --- | --- |
| `appKey` | `String` | 作为请求头 `x-api-key` 的值 | 无 |
| `baseUrl` | `String` | API 基础地址，末尾 `/` 会被去除 | `https://api.anthropic.com/v1` |
| `model` | `String` | 请求体 `model` 字段 | `claude-3-sonnet-20240229` |
| `temperature` | `Double` | 请求体 `temperature` 字段 | `0.3` |
| `maxTokens` | `Integer` | 请求体 `max_tokens` 字段 | `2048` |
| `system` | `String` | 请求体 `system` 字段（非空时）；配置了 `skill` 时先经 `SkillPrompt.inject` 注入技能说明 | 不发送 `system` |
| `proxy` | `String` | HTTP 代理，支持 `host:port` 与 `scheme://host:port` | 不走代理 |
| `metricsUrl` | `String` | `indicator()` 优先使用的模型指标端点 | 按服务商路由到聚合指标来源 |

`ChatClientSetting` 的其它字段（如 `timeoutMillis`、`tools`、`topP`、`extraHeaders` 等）本模块未读取；连接 / 读取超时固定为 30s / 90s。`provider` 字段用于 SPI 路由，取值 `claude`。

---

## 5. SPI 扩展点

本模块对外提供 **1 个 SPI 实现**，不定义新的 SPI 接口：

| 项 | 值 |
| --- | --- |
| SPI 接口 | `com.chua.common.support.ai.chat.ChatClient` |
| 实现类 | `com.chua.claude.support.ClaudeChatClient` |
| SPI 名称 | `claude`（类上标注 `@Spi({"claude"})`） |
| 注册文件 | `src/main/resources/META-INF/extensions/com.chua.common.support.ai.chat.ChatClient` |
| 注册内容 | `claude=com.chua.claude.support.ClaudeChatClient` |

获取方式（二选一）：

```java
// 方式一：ChatClient 静态工厂，按 setting.getProvider() 路由
ChatClient client = ChatClient.create(setting);

// 方式二：直接走 ServiceProvider（构造参数透传 setting）
ChatClient client2 = ServiceProvider.of(ChatClient.class)
        .getNewExtension("claude", setting);
```

GraalVM native-image 配置随模块内置：

- `META-INF/native-image/com.chua/utils-support-claude-starter/reflect-config.json`：注册 `ClaudeChatClient` 的全部构造器 / 方法 / 字段反射；
- `META-INF/native-image/com.chua/utils-support-claude-starter/resource-config.json`：打包 `META-INF/extensions/.*` 资源，保证 SPI 清单进入原生镜像。

---

## 6. 构建与依赖

```text
utils-support-claude-starter (com.chua, 4.0.0.51)
├── utils-support-common-starter      （同版本；提供 ChatClient SPI、HttpClientFactory、Json、Spi 等）
├── net.cyclingbits:claude-code-sdk-java:1.2.0
├── org.slf4j:slf4j-api
└── org.projectlombok:lombok (provided)
```

- 父 POM：`utils-support-cloud-parent`（`4.0.0.51`），其上为仓库根 `utils-support-parent-starter`。
- 编译：JDK 25，`maven.compiler.release=25`，启用 `--enable-preview`。
- 本地验证命令：`mvn -o -q compile -DskipTests`，`EXIT=0` 为通过。
- 说明：`claude-code-sdk-java` 为 POM 中声明的依赖，当前源码未直接引用（HTTP 调用走 `utils-support-common-starter` 的 `HttpClientFactory`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| 文件附件 | `addAttachment(...)` / `addAttachmentUrl(...)` 直接抛 `UnsupportedOperationException`（「该服务商不支持文件附件 / 远程文件附件」） |
| SSE 流式输出 | 未实现；`chat(...)` 为一次性请求，仅回调 `START / STOP / ERROR` |
| 图像 / 视频生成 | 未覆写 `generateImage` / `generateVideo`，走接口默认行为（抛 `UnsupportedOperationException`） |
| 中转站真伪探测 | 未覆写 `probe()`，调用抛 `UnsupportedOperationException` |
| 模型目录 | 未覆写 `models()`，默认返回空列表 |
| 工具调用 | `tools` / `tool` / `toolChoice` / `topP` / `stop` / `seed` / `responseFormat` 等链式参数不会写入请求体 |
| 图片输入 | `addImage(url)` 仅把 URL 记入内部列表，当前请求体未携带图片内容 |
| 智能搜索 | `smartSearch(true)` 仅保存开关，当前请求体未使用该参数 |
| 协议兼容层 | 本模块是 Anthropic 原生 Messages 协议，不承担 OpenAI 兼容转换；OpenAI 兼容兜底由 `ChatClient.create` 的 `openai` 扩展负责 |
