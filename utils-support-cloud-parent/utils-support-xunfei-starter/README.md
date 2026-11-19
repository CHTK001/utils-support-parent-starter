# utils-support-xunfei-starter

> 讯飞星火大模型对话集成模块：把星火（SparkDesk）对话接入 utils 体系的 `ChatClient` SPI，提供同步、流式、多轮对话与技能注入能力。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.xunfei.support`

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
    <artifactId>utils-support-xunfei-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `com.chua.common.support.ai.chat.ChatClient` 的讯飞星火实现，SPI 名称 `xunfei`（注解上另声明别名 `spark`） | 不是 Spring Boot starter：无自动配置类、无配置属性绑定 |
| 协议 | 基于 `com.unfbx:SparkDesk-Java` SDK，走星火 WebSocket 对话接口，按模型自动映射 V1.1 / V2.1 / V3.1 / V4.0 | 不含讯飞语音识别、语音合成、OCR、知识库等其它产品线能力 |
| 能力 | 同步对话、流式对话、多轮上下文、系统提示词、技能注入、HTTP/SOCKS5 代理 | 不支持文件附件；不含图像/视频生成、模型列表与指标端点 |

### 1.3 模块构成

- 源码仅一个类：`com.chua.xunfei.support.XunfeiChatClient`，类上标注 `@Spi({"xunfei", "spark"})`，`@since 4.0.0.42`。
- SPI 登记：`src/main/resources/META-INF/extensions/com.chua.common.support.ai.chat.ChatClient`，内容为 `xunfei=com.chua.xunfei.support.XunfeiChatClient`。
- 内置 GraalVM `native-image` 配置：`reflect-config.json` 注册 `XunfeiChatClient` 的全部构造器/方法/字段；`resource-config.json` 包含 `META-INF/extensions/.*`。

---

## 2. 核心 API 与速查表

`XunfeiChatClient` 实现 `ChatClient`，先链式配置、后调用。别人最常查的入口：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `ChatClient.create(ChatClientSetting)` | `provider` 传 `xunfei`（SPI 清单登记名） |
| 同步问答 | `chatSync(prompt)` | 阻塞至完成并返回完整文本；内部等待上限 90 秒（`DEFAULT_TIMEOUT_SECONDS`） |
| 流式输出 | `chat(prompt, consumer)` | 按事件回调 `START` / `STREAMING` / `STOP` / `ERROR` |
| 流式 + 完成/错误回调 | `chat(prompt, consumer, onComplete, onError)` | 出错经 `onError` 传递；两参重载则直接抛 `RuntimeException` |
| 多轮上下文 | `addUserHistory` / `addAssistantHistory` / `history(List<ChatMessage>)` | `newChat()` 清空内部历史与图片状态 |
| 系统提示词 / 技能注入 | `system(String)` / `skill(SkillManager)` | 技能经 `SkillPrompt.inject(system, skillManager)` 拼入系统提示词 |
| 选模型版本 | `model(String)` | 决定 API 地址与 `domain` 参数，见 4.2 |
| 获取 Token 用量 | 流式 `STOP` 事件的 `ChatResponse.getUsage()` | `AiUsage`：输入/输出/总 Token、起止与耗时；`provider` 固定为 `xunfei` |
| 释放会话 | `newChat()` | 清空历史与图片列表，可复用同一实例 |

> 注意：`chatSync` 只返回文本。用量信息要走流式 `chat(...)`：`STOP` 事件携带的 `ChatResponse.getUsage()` 才包含 Token 计量。

---

## 3. 快速开始

### 3.1 同步对话（最短可运行示例）

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

ChatClient client = ChatClient.create(ChatClientSetting.builder()
        .provider("xunfei")
        .appKey("你的APPID或APIKey")     // 当前实现中 appid 与 apiKey 共用此值
        .appSecret("你的APISecret")
        .model("spark-3.5")              // 缺省模型；地址映射见 4.2
        .build());

String answer = client
        .system("你是一名简洁的助手")
        .chatSync("用一句话介绍讯飞星火");
System.out.println(answer);
```

### 3.2 流式对话

```java
import com.chua.common.support.ai.chat.ChatResponse;

client.chat("讲一个 100 字以内的故事",
        resp -> {
            switch (resp.getState()) {
                case START -> System.out.println("== 开始 ==");
                case STREAMING -> System.out.print(resp.getContent());
                case STOP -> System.out.println("\n== 完成，usage=" + resp.getUsage());
                case ERROR -> System.err.println("出错: " + resp.getErrorMessage());
            }
        },
        () -> { /* 全部完成 */ },
        err -> err.printStackTrace());
```

### 3.3 多轮对话

```java
client.newChat();
client.addUserHistory("我的项目代号是蓝鲸");
client.addAssistantHistory("好的，已记住。");
String reply = client.chatSync("我的项目代号是什么？");
```

---

## 4. 配置项

### 4.1 本模块读取的 `ChatClientSetting` 字段

本模块**不引入新的 Spring 配置项**：模块内没有 `application.yml` 前缀、没有 `@ConfigurationProperties` 或自动配置类，全部配置通过 `com.chua.common.support.ai.chat.ChatClientSetting`（Builder 构造）传入。本模块实际读取的字段：

| 字段 | 说明 | 本模块行为 |
| --- | --- | --- |
| `provider` | SPI 名称 | 传 `xunfei`（清单登记名；注解另声明别名 `spark`） |
| `appKey` | API 密钥 | 同时作为星火的 `appid` 与 `apiKey` 传入 `SparkDeskClient` |
| `appSecret` | API 密钥（备用） | 作为星火的 `apiSecret` |
| `baseUrl` | 自定义 API 地址 | 非空时**覆盖**按模型推导的全部地址 |
| `model` | 模型名 | 决定 API 地址与 `domain`；为空时按 `spark-3.5` 处理 |
| `temperature` | 温度参数 | 为空时默认 `0.3` |
| `maxTokens` | 最大输出 Token | 为空时默认 `2048` |
| `system` | 系统提示词 | 非空时作为 `system` 消息发送 |
| `proxy` | 代理地址 | 支持 `http://`、`https://`、`socks5://`（或 `socks://`）、裸 `host:port`（按 HTTP 代理处理，缺省端口 80） |

其余 `ChatClientSetting` 字段（如 `topP`、`tools`、`timeoutMillis`、`extraHeaders`、`authScheme` 等）本模块未读取。补充固定值：请求等待上限 90 秒（不读取 `timeoutMillis`）；请求参数 `topK` 固定为 `4`；会话 `uid` 取 `session(...)` 的设置值，未设置时为 `default`。

### 4.2 模型与 API 地址映射

未配置 `baseUrl` 时，按 `model` 字符串的包含关系自动选择：

| `model` 包含 | API 地址 | 请求 `domain` |
| --- | --- | --- |
| `4.0` / `v4` | `https://spark-api.xf-yun.com/v4.0/chat` | `4.0Ultra` |
| `2.0` / `v2` | `https://spark-api.xf-yun.com/v2.1/chat` | `generalv2` |
| `1.5` / `v1` | `https://spark-api.xf-yun.com/v1.1/chat` | `general` |
| 其它（含缺省 `spark-3.5`） | `https://spark-api.xf-yun.com/v3.1/chat` | `generalv3.5` |

---

## 5. SPI 扩展点

本模块是 `ChatClient` SPI 的**实现方**，不对外暴露新的 SPI 接口。

- 接口：`com.chua.common.support.ai.chat.ChatClient`，定义于 `utils-support-common-starter`。
- 实现：`com.chua.xunfei.support.XunfeiChatClient`，类上标注 `@Spi({"xunfei", "spark"})`。
- 登记：`src/main/resources/META-INF/extensions/com.chua.common.support.ai.chat.ChatClient` 文件中登记 `xunfei=com.chua.xunfei.support.XunfeiChatClient`。
- 获取实例：

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.spi.ServiceProvider;

ChatClient client = ServiceProvider.of(ChatClient.class)
        .getNewExtension("xunfei", setting);
```

- 新增或替换其它大模型服务商：请在各自模块实现 `ChatClient`，并在自己模块的 `META-INF/extensions/` 清单中登记；不要把实现写进本模块。

---

## 6. 构建与依赖

| 依赖 | 说明 |
| --- | --- |
| `com.chua:utils-support-common-starter` | `ChatClient`、`ChatClientSetting`、`ChatResponse`、`SkillPrompt`、`ServiceProvider` 等契约与 SPI 设施，随本模块传递引入 |
| `com.unfbx:SparkDesk-Java` | 星火对话 SDK（含 OkHttp）；本模块 pom 未指定版本，由父 POM 统一管理 |
| `org.slf4j:slf4j-api` | 日志门面 |
| `org.projectlombok:lombok` | `provided`，仅编译期 |

构建：JDK 25（`maven.compiler.release=25`）。在本仓库根目录执行 `mvn -o -q compile -DskipTests` 即可编译本模块。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| 文件附件 | `addAttachment(...)` / `addAttachmentUrl(...)` 直接抛 `UnsupportedOperationException`（"该服务商不支持文件附件" / "该服务商不支持远程文件附件"） |
| 图片输入 | `addImage(String)` 仅把 URL 存入内部列表，当前请求构建未使用该列表 |
| 深度思考 / 智能搜索 | `thinking(...)`、`thinkingEffort(...)`、`smartSearch(...)` 仅保存状态，未映射进星火请求参数 |
| 模型列表 / 定价 / 指标 | 未覆写 `models()` / `modelPricing()` / `indicator()`，走接口默认实现（空列表 / 默认指标路由） |
| 图像 / 视频生成、真伪探测 | 未覆写 `generateImage(...)` / `generateVideo(...)` / `probe()`，走接口默认实现（抛 `UnsupportedOperationException`） |
| Spring 自动配置 | 无自动配置类、无配置属性前缀；实例化只经 SPI，或直接 `new XunfeiChatClient(setting)` |
| 讯飞其它产品线 | 语音识别、语音合成、OCR、机器翻译、知识库等均不在本模块，源码未提供 |
