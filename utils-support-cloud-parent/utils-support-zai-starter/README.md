# utils-support-zai-starter

> Z.AI 平台大模型集成模块：基于 Z.AI 官方 SDK 与 OpenAI 兼容 HTTP API，提供 utils 体系 `ChatClient`（对话）与 `ImageClient`（图片生成）两个 SPI 的 `zai` 实现。
>
> 版本：`4.0.0.51`（继承父模块 `utils-support-cloud-parent`）｜JDK：`25`（仓库 `maven.compiler.release=25`）｜包名：`com.chua.zai.support`（对话）、`com.chua.zai.support.image`（图片）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. 行为细节与已知限制](#5-行为细节与已知限制)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zai-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 对话 | `ChatClient` 的 `zai` SPI 实现（`ZaiChatClient`），基于 `ai.z.openapi:zai-sdk` 调用 Z.AI 对话接口 | 不提供多供应商路由编排、会话持久化 |
| 图片 | `ImageClient` 的 `zai` SPI 实现（`ZaiImageClient`），走 Z.AI 的 OpenAI 兼容 `/v1/images/generations` 接口 | 不提供异步生图任务、图生图、ControlNet |
| 定位 | 云服务集成父模块（`utils-support-cloud-parent`）下的厂商适配层 | 不是业务 starter：无 Spring 自动配置、无配置文件、无数据表 |
| 依赖 | 只依赖 `utils-support-common-starter`、`zai-sdk`、`slf4j-api`、`lombok` | 不依赖 Spring、不依赖数据库驱动 |

### 1.3 源码构成

- Java 源文件 **2** 个：
  - `com.chua.zai.support.ZaiChatClient` —— Z.AI 对话客户端（`@Spi({"zai"})`，`@since 4.0.0.42`）
  - `com.chua.zai.support.image.ZaiImageClient` —— ZAI 图片生成客户端（`@Spi({"zai"})`，`@since 4.0.0.42`）
- SPI 扩展声明 **2** 个（`src/main/resources/META-INF/extensions/`）。
- GraalVM `native-image` 配置 2 份：`reflect-config.json`（两个实现类的全量反射注册）与 `resource-config.json`（包含 `META-INF/extensions/.*`）。

---

## 2. 核心 API 与速查表

常见能力入口（SPI 名称均为 `zai`）：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| Z.AI 对话 | `ChatClient.create("zai", appKey)` | 返回 `ZaiChatClient`，链式配置后调用 |
| 对话（回调） | `chat(prompt, consumer)` | `START → STOP / ERROR` 状态回调；当前实现非流式，完整回复在 `STOP` 事件携带 |
| 对话（同步） | `chatSync(prompt)` | `ChatClient` 契约方法；当前实现的实际行为见 [5.3](#53-chatsync-的当前实现) |
| 深度思考 | `.thinking(true)` | 请求携带 `thinking.type = "enabled"` |
| 技能注入 | `.skill(SkillManager)` | 经 `SkillPrompt.inject` 拼入 system 提示词 |
| 多轮历史 | `.addUserHistory(...)` / `.addAssistantHistory(...)` / `.history(List<ChatMessage>)` / `.newChat()` | 内置历史与外部历史二选一（`externalHistory` 优先） |
| 模型指标 | `client.indicator()` | 价格 / 上下文窗口 / 能力标志，见 [4.1](#41-chatchlientsettingzaichatclient-实际读取的字段) |
| 网络代理 | `ChatClientSetting.proxy` | 支持 http / https / socks5 代理，见 [4.1](#41-chatchlientsettingzaichatclient-实际读取的字段) |
| Z.AI 图片生成 | `ImageClient.create("zai", appKey).generate(prompt)` | 同步生成，返回 `BufferedImage`（`n=1`） |

### 2.1 ZaiChatClient

| 方法 | 说明 |
| --- | --- |
| `model(String)` / `temperature(double)` / `maxTokens(int)` / `system(String)` | 覆盖 `ChatClientSetting` 中的同名默认值 |
| `thinking(boolean)` | 开启深度思考（请求体 `thinking.type = "enabled"`） |
| `thinkingEffort(String)` / `smartSearch(boolean)` | 链式方法存在；当前请求构造**未下发**这两个参数 |
| `skill(SkillManager)` | 注入技能管理器，请求前用 `SkillPrompt.inject(system, skillManager)` 改写 system |
| `addImage(String imageUrl)` | URL 被收集，但当前请求构造**未使用**（多模态图片输入未下发） |
| `addUserHistory(String)` / `addAssistantHistory(String)` | 追加 user / assistant 历史 |
| `history(List<ChatMessage>)` | 设置外部完整历史（设置后优先于内置 `history`） |
| `session(String sessionId)` | 保存会话标识；当前请求构造未使用 |
| `newChat()` | 清空内置历史、图片列表与外部历史 |
| `chatSync(String prompt)` | 同步对话，返回拼接后的文本（见 5.3） |
| `chat(String prompt, Consumer<ChatResponse>)` | 回调式对话；异常包装为 `RuntimeException("对话请求失败: ...")` 抛出 |
| `chat(prompt, consumer, onComplete, onError)` | 带完成/异常回调的完整形态；失败时回调收到 `ERROR` 状态并触发 `onError` |
| `addAttachment(...)` / `addAttachmentUrl(...)` | 抛 `UnsupportedOperationException`（该服务商不支持文件附件） |
| `indicator()` | 返回 `ChatClientIndicator`，委托 common-starter 的 `ChatClientIndicators.of(getSetting())` |
| `getSetting()` | 返回构造时传入的 `ChatClientSetting` |

### 2.2 ZaiImageClient

| 方法 | 说明 |
| --- | --- |
| `model(String)` | 覆盖模型名（默认 `dall-e-3`） |
| `size(int width, int height)` | 覆盖尺寸（默认 `1024x1024`，请求体形如 `"1024x1024"`） |
| `prompt(String)` | 默认提示词；`generate(prompt)` 传参优先 |
| `quality(String)` / `style(String)` | 非空时写入请求体（`quality` / `style` 字段） |
| `negativePrompt(String)` / `seed(Long)` / `steps(Integer)` | 链式方法存在；当前请求体**未携带**这些参数 |
| `generate(String prompt)` | 同步生成：POST `/v1/images/generations`，下载 `data[0].url` 并解码为 `BufferedImage` |
| `createTask(...)` / `queryTask(...)` | 抛 `UnsupportedOperationException`（不支持异步任务模式） |
| `models()` | 抛 `UnsupportedOperationException`（不支持模型列表查询） |
| `referenceImage(...)` ×2 / `imageStrength(double)` / `controlType(String)` | 抛 `UnsupportedOperationException`（不支持参考图 / ControlNet） |
| `close()` | 空实现 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zai-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 对话（回调式，推荐）

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;

ChatClient client = ChatClient.create("zai", "你的 API Key");

client.model("zai-1")              // 不传时实现内兜底 zai-1
      .temperature(0.3)
      .maxTokens(2048)
      .system("你是一名简洁的助手")
      .chat("用一句话介绍 Z.AI", response -> {
          switch (response.getState()) {
              case STOP  -> System.out.println(response.getFullContent());
              case ERROR -> System.err.println(response.getErrorMessage());
              default    -> { }
          }
      });
```

### 3.3 图片生成

```java
import com.chua.common.support.ai.image.ImageClient;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

ImageClient client = ImageClient.create("zai", "你的 API Key");

BufferedImage image = client.model("dall-e-3")
        .size(1024, 1024)
        .generate("一只穿宇航服的猫，扁平插画风格");

ImageIO.write(image, "png", new File("out.png"));
```

> 两个工厂均等价于 Builder 写法：`ChatClient.create(ChatClientSetting.builder().provider("zai").appKey(key).build())`、`ImageClient.create(ImageClientSetting.builder().provider("zai").appKey(key).build())`。

---

## 4. 配置项

本模块**没有** `application.yml` / `properties` 配置文件，也没有 Spring 自动配置类；所有配置通过 `ChatClientSetting` / `ImageClientSetting`（Builder）以代码方式传入，由 SPI 工厂 `ChatClient.create(setting)` / `ImageClient.create(setting)` 消费。以下仅列出本模块实现**实际读取**的字段。

### 4.1 ChatClientSetting（ZaiChatClient 实际读取的字段）

| 字段 | 类型 | 说明 | 缺省行为 |
| --- | --- | --- | --- |
| `provider` | String | SPI 名称，固定传 `zai` | — |
| `appKey` | String | API Key，透传给 `ZaiClient.builder().apiKey(...)` | 必填 |
| `baseUrl` | String | API 基础地址；末尾 `/` 会被去除 | `https://api.z.ai/v1` |
| `model` | String | 默认模型 | 链式未传时请求用 `zai-1` |
| `temperature` | Double | 温度参数 | `0.3` |
| `maxTokens` | Integer | 最大输出 Token 数 | `2048` |
| `system` | String | 系统提示词 | 不发送 system 消息 |
| `proxy` | String | 代理地址，见下 | 不设置代理 |
| `metricsUrl` | String | 模型指标端点，仅 `indicator()` 使用 | 回退 Models.dev 免 Key 端点（按 `zai` 厂商过滤，见源码 Javadoc） |

`proxy` 取值格式与行为（源码 `applyProxy`）：

| 格式 | 行为 |
| --- | --- |
| `http://host:port` / `https://host:port` / 裸 `host:port` | 设置系统属性 `http.proxyHost` / `http.proxyPort` / `https.proxyHost` / `https.proxyPort` |
| `socks5://host:port` / `socks://host:port` | 设置系统属性 `socksProxyHost` / `socksProxyPort` |
| 端口缺省时 | 按 `80` 处理 |

> `ChatClientSetting` 的其余通用字段（`appSecret`、`topP`、`tools`、`toolChoice`、`stop`、`seed`、`responseFormat`、`timeoutMillis`、`extraHeaders`、`authScheme`、`authHeader`、`authPrefix`、`routes` 等）定义在 common-starter 的契约类中，`ZaiChatClient` 当前实现未读取。

### 4.2 ImageClientSetting（ZaiImageClient 实际读取的字段）

| 字段 | 类型 | 说明 | 缺省行为 |
| --- | --- | --- | --- |
| `provider` | String | SPI 名称，固定传 `zai` | — |
| `appKey` | String | API Key，请求头 `Authorization: Bearer <appKey>` | 必填 |
| `baseUrl` | String | API 基础地址；末尾 `/` 会被去除 | `https://api.zai.com` |
| `model` | String | 默认模型 | 链式未传时请求用 `dall-e-3` |
| `width` / `height` | Integer | 默认尺寸 | `1024` × `1024` |

> 其余字段（`secretKey`、`prompt`、`negativePrompt`、`quality`、`style`、`seed`、`steps`、`referenceImage`、`imageStrength`、`controlType`）构造时不读取；其中 `quality` / `style` 可经链式方法生效，其余见第 5 节。

---

## 5. 行为细节与已知限制

### 5.1 对话请求的固定行为

- 非流式：`stream(false)`，一次性返回完整回复（`STOP` 事件携带 `content` / `fullContent`）。
- 成功时回填 `AiUsage`：`provider = "zai"`、模型名、耗时及上游 `Usage` 的 `inputTokens` / `outputTokens` / `totalTokens`。
- 上游返回失败：`ERROR` 状态，消息为 `"Z.AI API 返回错误: " + response.getMsg()`。
- `thinking(true)` 时请求体携带 `thinking = { type: "enabled" }`。

### 5.2 图片请求的固定行为

- 请求：`POST {baseUrl}/v1/images/generations`，JSON 体含 `model`、`prompt`、`n=1`、`size`、`response_format="url"`，`quality` / `style` 非空才携带。
- 超时：生成请求连接 30s / 读取 120s；图片下载连接 30s / 读取 60s。
- 非 2xx 抛 `RuntimeException("ZAI 图片生成请求失败: <状态码> - <响应体>")`；`data` 为空或 `url` 为空同样抛错。

### 5.3 chatSync 的当前实现

`chatSync(prompt)` 仅聚合 `ChatResponse.State.STREAMING` 事件中的内容；而 `ZaiChatClient` 当前是非流式实现，回复通过 `STOP` 事件一次性返回。因此**直接调用 `chatSync` 得到的是空串**。需要完整回复时请使用回调形式，并从 `STOP` 事件的 `getFullContent()` 取结果（源码行为如实记录）。

### 5.4 契约方法中未启用 / 不支持的部分

| 方法 | 当前实现 |
| --- | --- |
| `addAttachment(...)` / `addAttachmentUrl(...)` | 抛 `UnsupportedOperationException` |
| `addImage(String)` | URL 被收集但请求构造未使用 |
| `session(String)` / `thinkingEffort(String)` / `smartSearch(boolean)` | 字段被保存但请求构造未使用 |
| 图片侧 `negativePrompt` / `seed` / `steps` | 链式保存但请求体未携带 |
| 图片侧 `createTask` / `queryTask` / `models()` / `referenceImage(...)` / `imageStrength(...)` / `controlType(...)` | 抛 `UnsupportedOperationException` |

---

## 6. SPI 扩展点

本模块**不定义新的 SPI 接口**，它是 common-starter 两个 AI SPI 接口的实现方，按名称 `zai` 注册：

| SPI 接口 | 注册文件（`META-INF/extensions/`） | 内容 |
| --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | 同名文件 | `zai=com.chua.zai.support.ZaiChatClient` |
| `com.chua.common.support.ai.image.ImageClient` | 同名文件 | `zai=com.chua.zai.support.image.ZaiImageClient` |

获取方式：

```java
// 工厂入口（内部走 ServiceProvider）
ChatClient chat = ChatClient.create("zai", appKey);
ImageClient image = ImageClient.create("zai", appKey);

// 或直接使用 ServiceProvider
ChatClient c = ServiceProvider.of(ChatClient.class).getNewExtension("zai", setting);
```

扩展方向：需要接入其它厂商时，在对应 `*-starter` 实现 `ChatClient` / `ImageClient` 并登记同名 extensions 文件即可；本模块只覆盖 `zai` 一个名称。GraalVM native-image 下，两个实现类已注册全量反射，`META-INF/extensions/.*` 资源已包含。

---

## 7. 构建与依赖

依赖（`pom.xml`）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | `ChatClient` / `ImageClient` 契约、SPI、HTTP、工具 |
| `ai.z.openapi:zai-sdk` | 0.3.0 | Z.AI 官方 SDK（对话） |
| `org.slf4j:slf4j-api` | 父 POM 管理 | 日志 |
| `org.projectlombok:lombok` | 父 POM 管理（provided） | 编译期注解 |

构建命令（在本仓库 `utils-support-parent-starter` 下执行）：

```bash
mvn -o -q compile -DskipTests
```

本模块为纯库模块：无 Spring Boot 自动配置、无启动器、无测试目录之外的额外构建步骤；native-image 反射与资源配置随 jar 发布（`META-INF/native-image/com.chua/utils-support-zai-starter/`）。

---

## 8. 边界：这些能力不在本模块

- **其它厂商实现**：openai、zhipu、claude、doubao、hunyuan、xunfei 等分别在各自 `utils-support-*-starter` 中。
- **契约与门面**：`ChatClient` / `ImageClient` 接口、`ChatClientSetting` / `ImageClientSetting`、`ChatResponse` / `ChatMessage` 定义在 `utils-support-common-starter`。
- **路由与指标实现**：多供应商路由（`RouterChatClient`）、模型指标聚合（`ChatClientIndicators` / `HttpChatClientIndicator` / `ModelsDevChatClientIndicator`）在 common-starter，本模块只作为消费方调用。
- **AI 其余能力**：Embedding、RAG、Agent、语音、视频、MCP、探测等不在本模块。
- **治理能力**：重试、限流、熔断、会话持久化、用量入库等不在本模块。
- **平台能力**：无 Spring 自动配置、无配置文件、无数据表、无定时任务；文件附件、异步生图任务、图生图 / ControlNet、模型列表查询均不支持（见 5.4）。
