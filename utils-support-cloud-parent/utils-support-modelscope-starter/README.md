# utils-support-modelscope-starter

> [ModelScope（魔搭）](https://www.modelscope.cn) AI 服务集成模块：通过 SPI `provider="modelscope"` 提供 OpenAI 兼容的**文生图**（`ImageClient`）、**对话**（`ChatClient`）实现，并提供 **Hub 模型仓库下载/上传**客户端（`ModelscopeHubClient`）。
>
> 版本：`4.0.0.51`（随父 POM `utils-support-cloud-parent`）｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.modelscope.support`（核心类自 `4.0.0.42` 引入）

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
  - [3.1 文生图（同步）](#31-文生图同步)
  - [3.2 文生图（异步任务）](#32-文生图异步任务)
  - [3.3 对话（多轮 / 多模态 / 完整响应）](#33-对话多轮--多模态--完整响应)
  - [3.4 Hub 模型下载与上传](#34-hub-模型下载与上传)
- [4. 配置项](#4-配置项)
  - [4.1 客户端创建与 Setting 字段](#41-客户端创建与-setting-字段)
  - [4.2 端点与内置常量](#42-端点与内置常量)
  - [4.3 鉴权与超时](#43-鉴权与超时)
  - [4.4 Spring 配置文件](#44-spring-配置文件)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

ModelScope 没有官方 Java SDK，本模块通过 HTTP / Git 直接对接其 **API-Inference**（OpenAI 兼容端点）与 **Hub**（模型/数据集托管，Git LFS）：

| 能力 | 入口 | 对端 |
|---|---|---|
| 文生图 | `ModelscopeImageClient`（SPI `ImageClient`） | `POST {baseUrl}/v1/images/generations`、异步任务 `/v1/models/{model}/async-infer`、`/v1/models/{model}/tasks/{taskId}` |
| 多模态对话 | `ModelscopeChatClient`（SPI `ChatClient`） | `POST {baseUrl}/v1/chat/completions` |
| 模型下载/上传 | `ModelscopeHubClient`（直接 new，非 SPI） | Hub `https://www.modelscope.cn`：git clone/push（LFS）、单文件下载、文件清单 REST API |

`ImageClient` / `ChatClient` 接口与 `ImageClientSetting` / `ChatClientSetting` 等类型定义在 `utils-support-common-starter`，本模块只提供 `modelscope` 这一个 provider 的实现。

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-modelscope-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> 版本由根 POM 的 `utils.version` 统一管理（当前 `4.0.0.51`）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | ModelScope API-Inference + Hub 的 SPI 实现模块 | 不定义 `ChatClient`/`ImageClient` 抽象（抽象在 `utils-support-common-starter`） |
| 形态 | 普通 jar，向 SPI 注册 `modelscope` 实现 | 不是 Spring Boot starter：无 Spring 依赖、无自动装配、无 `application.yml` |
| 对话 | 同步对话（`chatSync` / `chatSyncWithResponse`）、多轮历史、图片/附件多模态 | 不支持流式对话（请求体固定 `stream=false`，未实现流式方法） |
| 图像 | 文生图同步 `generate`、异步 `createTask`/`queryTask`、参考图透传 | 不支持 ControlNet（`controlType` 抛 `UnsupportedOperationException`）；`imageStrength`/`quality`/`style` 被忽略并打 warn 日志 |
| Hub | git clone/push（含 LFS）、单文件 HTTP 下载、文件清单 | 不做模型训练、不做本地推理、不做渠道/账户管理业务 |

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 文生图（同步） | `ImageClient.create("modelscope", token).model(...).size(w,h).generate(prompt)` | 返回 `BufferedImage`；响应取 `data[0].b64_json`，缺失时回退 `url` 下载 |
| 文生图（异步） | `createTask(prompt)` → `queryTask(taskId)` | 返回任务 ID（响应的 `task_id`，缺失时取 `request_id`）；`queryTask` 返回 `ImageResponse` |
| 文本对话 | `ChatClient.create("modelscope", token).model(...).chatSync(prompt)` | 返回回复文本（`choices[0].message.content`） |
| 带 用量的完整响应 | `chatSyncWithResponse(prompt)` | 返回 `ChatSyncResponse`：`getText()` + `getUsage()`（prompt/completion/total tokens → `AiUsage`） |
| 图文多模态 | `.addImage(url)` / `.addAttachment(name, data, mime)` / `.addAttachmentUrl(...)` | 图片走 `image_url` 内容分片；本地路径自动转 `file:` URI，字节数组转 data URI |
| 多轮历史 | `.system(...)`、`.addUserHistory(...)`、`.addAssistantHistory(...)`、`.history(list)`、`.newChat()` | 历史与图片/附件在客户端内累积，`newChat()` 清空 |
| 模型清单 | `models()` | 返回内置节选 `List<ModelDefinition>`（图像 7 个 / 对话 8 个，见下文），不发起网络请求 |
| Hub 快照下载/上传 | `new ModelscopeHubClient(token)` → `downloadSnapshot` / `uploadSnapshot` | 走 `git clone` / `git push`（LFS），需本机 `git` + `git-lfs` |
| Hub 单文件/清单 | `downloadFile(repoId, path, target)` / `listFiles(repoId)` | 纯 HTTP，不依赖 git |

类型补充：

- `ModelscopeImageClient.guidanceScale(double)`：设置 CFG 引导系数（请求字段 `guidance_scale`）。**该方法只在实现类上，`ImageClient` 接口未声明**，链式使用需以实现类类型承接。
- 两个 Client 均实现 `close()`（清空历史/参考图）；`ImageClient` 亦支持对象池语义（见接口 `PooledObjectClient`）。
- `ModelscopeChatClient.getSetting()` 返回构造时传入的 `ChatClientSetting`。

---

## 3. 快速开始

### 3.1 文生图（同步）

```java
import com.chua.common.support.ai.image.ImageClient;
import java.awt.image.BufferedImage;

BufferedImage image = ImageClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen-Image")          // 缺省即 Qwen/Qwen-Image；也可 SD/FLUX 等
        .size(1024, 1024)
        .negativePrompt("低质量、模糊")
        .steps(30)
        .seed(42L)
        .generate("一只柴犬在樱花树下，电影感光线");
```

请求体字段：`model`、`prompt`、`n=1`、`response_format=b64_json`、`size`（`宽x高`）、可选 `negative_prompt` / `seed` / `steps` / `guidance_scale` / `image`（参考图 Base64 列表）。

### 3.2 文生图（异步任务）

```java
ImageClient client = ImageClient.create("modelscope", "ms-xxx").model("damo/text-to-image-large");

// 提交异步任务（适合大模型/长耗时），返回 task_id
String taskId = client.createTask("夜景城市");

// 轮询任务状态
ImageResponse resp = client.queryTask(taskId);
if (resp.getStatus() == ImageResponse.Status.SUCCESS) {
    byte[] bytes = resp.getImageBytes();
}
```

`queryTask` 的状态归一化：`SUCCESS/SUCCEEDED/DONE/FINISHED` → `SUCCESS`；`FAILED/ERROR/CANCELED` → `FAILED`；其余 → `PENDING`。结果图片从 `output_images` / `output` / `data` 列表的首项取 `url` 或 `b64_json`。

### 3.3 对话（多轮 / 多模态 / 完整响应）

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatSyncResponse;

// 纯文本多轮
String answer = ChatClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen2.5-7B-Instruct")   // 缺省即该模型
        .temperature(0.7)
        .maxTokens(2048)
        .system("你是助手")
        .addUserHistory("我叫小明")
        .chatSync("你还记得我叫什么吗？");

// 图文多模态（多模态模型，如 Qwen/Qwen2-VL-7B-Instruct）
String reply = ChatClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen2-VL-7B-Instruct")
        .addImage("https://example.com/cat.jpg")   // 也支持本地路径（转 file: URI）、data: URI
        .chatSync("描述这张图");

// 完整响应（带 token 用量）
ChatSyncResponse resp = ChatClient.create("modelscope", "ms-xxx")
        .model("Qwen/Qwen2.5-7B-Instruct")
        .chatSyncWithResponse("hi");
System.out.println(resp.getText());
System.out.println(resp.getUsage());
```

### 3.4 Hub 模型下载与上传

需本机安装 `git` 与 `git-lfs`（ModelScope 大权重走 Git LFS）。

```java
import com.chua.modelscope.support.ModelscopeHubClient;
import java.nio.file.Path;
import java.util.List;

ModelscopeHubClient hub = new ModelscopeHubClient("ms-xxx");

// 完整克隆（含 LFS 权重）到 /data/cache/<repo-name>，返回仓库根目录
Path repo = hub.downloadSnapshot("microsoft/Mage-Flow-Turbo", Path.of("/data/cache"));

// 单文件下载（纯 HTTP，不依赖 git）
hub.downloadFile("microsoft/Mage-Flow-Turbo", "config.json", Path.of("./config.json"));

// 列出仓库文件清单（公开仓库免鉴权）
List<String> files = hub.listFiles("microsoft/Mage-Flow-Turbo");

// 上传：本地必须是 git 仓库（含 .git），并配置好 user.name/email + git-lfs
hub.uploadSnapshot("my-org/my-model", Path.of("./local-repo"));
```

`downloadSnapshot` 克隆到 `localDir/<repoName>`（`repoName` 取 `repoId` 第一个 `/` 之后的部分）；`uploadSnapshot` 执行 `git remote set-url origin <鉴权URL>` 后 `git push origin HEAD:master`。git 进程输出以 `[modelscope-hub]` 前缀写入 slf4j 日志，等待超时为读超时（300 秒），超时后 `destroyForcibly`。

---

## 4. 配置项

### 4.1 客户端创建与 Setting 字段

本模块**没有**键值型配置文件；全部配置通过工厂方法或 Setting 对象传入：

```java
// 三种等价创建方式（ChatClient 同理：ChatClient.create(...)）
ImageClient a = ImageClient.create("modelscope", "ms-xxx");                    // provider + token
ImageClient b = ImageClient.create("modelscope", "ms-xxx", "https://proxy");   // + 自定义 baseUrl
ImageClient c = ImageClient.create(ImageClientSetting.builder()                // 完整配置
        .provider("modelscope").appKey("ms-xxx").baseUrl("https://proxy")
        .model("Qwen/Qwen-Image").width(1024).height(1024).build());
```

`ModelscopeImageClient` 实际读取的 `ImageClientSetting` 字段：`model`、`width`、`height`、`prompt`、`negativePrompt`、`seed`、`steps`、`referenceImage`、`appKey`、`baseUrl`。
`ModelscopeChatClient` 实际读取的 `ChatClientSetting` 字段：`model`、`system`、`appKey`、`baseUrl`。

指标能力：`ModelscopeChatClient.indicator()` 走 `ChatClient` 接口默认实现——优先使用用户配置的 `ChatClientSetting.getMetricsUrl()`（`HttpChatClientIndicator`）；未配置时回退到免 Key 的 Models.dev 聚合端点（`ModelsDevChatClientIndicator`），按 `modelscope` 厂商键过滤。

自定义 `baseUrl`（代理/自部署）会先做归一化：剥离末尾 `/` 与 `/v1` 后缀（路径由客户端按常量拼接），为空时使用默认公网地址。

### 4.2 端点与内置常量

全部常量定义于 `ModelscopeConstants`：

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `DEFAULT_INFERENCE_BASE_URL` | `https://api-inference.modelscope.cn` | API-Inference 默认基地址 |
| `DEFAULT_HUB_BASE_URL` | `https://www.modelscope.cn` | Hub 默认基地址 |
| `PATH_CHAT_COMPLETIONS` | `/v1/chat/completions` | 对话（OpenAI 兼容） |
| `PATH_IMAGES_GENERATIONS` | `/v1/images/generations` | 文生图（OpenAI 兼容） |
| `PATH_MODELS` | `/v1/models` | 模型清单路径（常量已定义；当前 `models()` 返回内置静态清单，未发起该请求） |
| `PATH_MODEL_INFER` | `/v1/models/%s/infer` | 通用推理路径模板（常量已定义，当前源码未使用） |
| `PATH_MODEL_INFER_ASYNC` | `/v1/models/%s/async-infer` | 异步任务提交 |
| `PATH_MODEL_TASK` | `/v1/models/%s/tasks/%s` | 异步任务查询 |

Hub 侧实际拼接的 URL：文件清单 `GET {hub}/api/v1/models/{repoId}/repo/files`（取 `data[].Path`）；单文件 `GET {hub}/{repoId}/resolve/master/{pathInRepo}`。

默认模型：图像 `Qwen/Qwen-Image`；对话 `Qwen/Qwen2.5-7B-Instruct`（未调用 `model(...)` 时生效）。

内置模型清单（`models()` 返回的 `ModelDefinition`，`provider` 均为 `modelscope`）：

- 图像：`Qwen/Qwen-Image`、`Qwen/Qwen-Image-Edit`、`AI-ModelScope/stable-diffusion-v1-5`、`AI-ModelScope/sdxl-turbo`、`AI-ModelScope/FLUX.1-schnell`、`AI-ModelScope/FLUX.1-dev`、`damo/text-to-image-large`
- 对话：`Qwen/Qwen2.5-7B-Instruct`、`Qwen/Qwen2.5-14B-Instruct`、`Qwen/Qwen2.5-72B-Instruct`、`Qwen/Qwen2-VL-7B-Instruct`、`Qwen/Qwen2-VL-72B-Instruct`、`LLM-Research/Llama-3.2-3B-Instruct`、`deepseek-ai/DeepSeek-V2-Chat`、`iic/nlp_gte_sentence-embedding_chinese-large`

### 4.3 鉴权与超时

- API-Inference 全部请求带 `Authorization: Bearer <appKey>`；`appKey` 为空时抛 `IllegalStateException`（提示到魔搭个人中心 → 访问令牌获取）。
- Hub git 地址：有 token 时为 `https://www.modelscope.cn/oauth2:<token>@<repoId>.git`；无 token 时为匿名 `https://www.modelscope.cn/<repoId>.git`（仅公开仓库可用）。HTTP 请求有 token 时带 `Bearer` 头，无 token 时不带头。
- `ModelscopeHubClient` 允许空 token 构造（`new ModelscopeHubClient()`），用于公开仓库下载。
- 超时常量：连接 `CONNECT_TIMEOUT_MILLIS = 15_000`，读取 `READ_TIMEOUT_MILLIS = 300_000`（长上下文/长音频/大图像推理与 git 操作共用）。

### 4.4 Spring 配置文件

无。依据：`src/main/resources` 下仅有两个 `META-INF/extensions/` SPI 注册文件，pom 无任何 Spring 依赖；本模块是 utils 体系的最底层实现模块，不做自动装配，配置一律由调用方以 Setting/链式参数显式传入。

---

## 5. SPI 扩展点

本模块通过 `META-INF/extensions/` 向 common 层注册两个 provider，名称均为 `modelscope`：

| SPI 接口 | 注册文件 | 实现 |
| --- | --- | --- |
| `com.chua.common.support.ai.image.ImageClient` | `META-INF/extensions/com.chua.common.support.ai.image.ImageClient` | `modelscope=com.chua.modelscope.support.ModelscopeImageClient` |
| `com.chua.common.support.ai.chat.ChatClient` | `META-INF/extensions/com.chua.common.support.ai.chat.ChatClient` | `modelscope=com.chua.modelscope.support.ModelscopeChatClient` |

两个实现类均标注 `@Spi("modelscope")`，由 `ServiceProvider` 按名称实例化（`ImageClient.create("modelscope", ...)` 内部即 `ServiceProvider.of(ImageClient.class).getNewExtension(...)`）。

本模块不提供其它 SPI。`ModelscopeHubClient` 不是 SPI，直接 `new` 使用。若要接入新的服务商，请在自己的模块实现 `ImageClient` / `ChatClient` 并以独立 provider 名注册，不要复用 `modelscope` 名称。

---

## 6. 构建与依赖

- 父 POM：`com.chua:utils-support-cloud-parent:4.0.0.51`（根 `utils-support-parent-starter:4.0.0.51`）；编译目标 JDK 25，源码编码 UTF-8，`maven.test.skip=true`。
- 依赖（见模块 `pom.xml`）：

| 依赖 | 说明 |
| --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`，提供 SPI 接口、`HttpClientFactory`、`Json`/`JsonObject`、`Wraps`、`StringUtils` 等 |
| `com.openai:openai-java` | `4.76.0`（根 POM `dependencyManagement` 统一托管），用于统一 OpenAI 风格客户端抽象；本模块的 chat 实际经 `HttpClientFactory` 直拼请求体（ModelScope 图像端点不在 OpenAI SDK 抽象内） |
| `org.slf4j:slf4j-api` | 日志门面 |
| `org.projectlombok:lombok` | `provided` |

构建命令（在本模块目录）：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 / 说明 |
| --- | --- |
| `ChatClient` / `ImageClient` 接口、`ChatClientSetting` / `ImageClientSetting`、`ImageResponse`、`ChatSyncResponse`、`ModelDefinition`、`AiUsage`、`Attachment`、`ChatMessage` 等类型 | 定义在 `utils-support-common-starter`，本模块只是实现方 |
| 流式对话（SSE） | 未实现：`ModelscopeChatClient` 请求体固定 `stream=false`，仅提供同步 `chatSync` / `chatSyncWithResponse` |
| ControlNet | `controlType(...)` 直接抛 `UnsupportedOperationException` |
| `imageStrength` / `quality` / `style` 参数 | 调用后被忽略并打 warn 日志（ModelScope 文生图端点不支持） |
| 微软 Mage 文生图/编辑/多模态 | 原 `utils-support-deeplearning-mage-starter` 已退役，本模块不提供该能力 |
| 工具调用（function calling）、JSON 模式、路由、Agent、RAG、Embedding 客户端 | 属于 common 层或其它 starter 的能力；本模块对话实现未覆盖 tools/工具相关 Setting 字段 |
| 渠道管理、账户、凭据等业务 | 属于上层业务 starter（如 `spring-support-account-starter`）；本模块只做对 ModelScope 的技术对接 |
| git / git-lfs 可执行文件 | 运行环境需自行安装；缺失或失败时 `downloadSnapshot`/`uploadSnapshot` 抛 `RuntimeException` |

## License

Java 客户端遵循仓库根 LICENSE。ModelScope 平台与模型权重许可参见 https://www.modelscope.cn。
