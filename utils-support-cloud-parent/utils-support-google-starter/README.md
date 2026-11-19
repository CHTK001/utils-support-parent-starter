# utils-support-google-starter

> 谷歌云集成模块：**Gemini 大模型对话、Imagen 图片生成、Cloud Storage 对象存储、Colab Enterprise 笔记本执行**。本模块不定义门面，而是把 Google 的能力实现成 `utils-support-common-starter` 里 `ChatClient` / `ImageClient` / `FileStorage` 的 SPI 扩展，按名字装配即可使用。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target` 与 `java.version` 均为 25）｜包名：`com.chua.google.support`（子包 `image`、`storage`、`colab`）

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
    <artifactId>utils-support-google-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Google 云能力的 **SPI 实现集**：Gemini 对话（`ChatClient`）、Imagen 生图（`ImageClient`）、GCS 对象存储（`FileStorage`）、Colab Enterprise（独立客户端） | 不是统一门面；`ChatClient` / `ImageClient` / `FileStorage` 等接口与门面都在 `utils-support-common-starter` |
| 接入方式 | 按名字装配：`ChatClient` / `ImageClient` 走 `META-INF/extensions`，`FileStorage` 走 `META-INF/services` | 不是 Spring Boot 自动配置 starter：无自动配置类、无属性前缀，引入即用、零配置 |
| 协议 | Gemini 走 `generateContent` **原生协议**（`type()` 返回 `ChatClientType.PROVIDER_NATIVE`），基于 Google Gen AI SDK（`google-genai`） | 不提供 OpenAI 兼容协议转换，不代理其它云厂商 |
| 存储 | 基于 Google Cloud Storage SDK，实现上传 / 下载 / 删除 / 存在性 / 列表五类对象操作 | 不实现 GCS 原生分片上传协议（分片走父类 `AbstractFileStorage` 的本地临时目录默认实现） |

### 1.3 类一览

| 类 | 实现 / 继承 | SPI 名 | 说明 |
| --- | --- | --- | --- |
| `GoogleChatClient` | `com.chua.common.support.ai.chat.ChatClient` | `google`、`gemini` | Gemini 对话：同步 / 流式 / 异步回调、深度思考、联网搜索、技能注入、用量统计 |
| `GoogleImageClient` | `com.chua.common.support.ai.image.ImageClient` | `google`、`gemini` | Imagen 文生图：HTTP 调用 `models/{model}:predict`，返回 `BufferedImage` |
| `GoogleCloudFileStorage` | `com.chua.common.support.storage.AbstractFileStorage` | `gcs`、`google` | GCS 对象存储：put / get / delete / exist / list |
| `GoogleColabClient` | `AutoCloseable`（非 SPI，直接 `new`） | — | Colab Enterprise：Notebook 运行时管理 + `.ipynb` 执行作业提交与跟踪 |

四个类的 `@since` 均为 `4.0.0.42`。模块还内置 GraalVM `native-image` 的 `reflect-config.json` / `proxy-config.json` / `resource-config.json`（位于 `src/main/resources/META-INF/native-image/com.chua/utils-support-google-starter/`）。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| Gemini 对话（同步） | `ChatClient.create("google", apiKey).chatSync(prompt)` | 返回文本；有思考内容时为 `reasoning\n---\ncontent` |
| Gemini 对话（流式） | `client.chat(prompt, consumer)` | 回调收到 `ChatResponse.State`：`START` / `STREAMING` / `STOP` / `ERROR` |
| 深度思考 / 联网搜索 | `.thinking(true).thinkingEffort(...)`、`.smartSearch(true)` | effort 取 `low` / `medium` 分别对应思考预算 1 / 2，其余值按 3；`smartSearch` 挂 Google Search 工具 |
| Imagen 文生图 | `ImageClient.create("google", apiKey).generate(prompt)` | 同步返回 `BufferedImage`；缺省模型 `imagen-3.0-generate-001` |
| GCS 上传 / 下载 | `FileStorage.createStorage("gcs", bucketSetting)` | `putObject` / `getObject` / `deleteObject` / `existObject` / `listObject` |
| Colab 运行时管理 | `new GoogleColabClient(projectId, location, saJson)` | `listRuntimes` / `getRuntime` / `startRuntime` / `stopRuntime` / `deleteRuntime` / `listRuntimeTemplates` |
| Colab 笔记本执行 | `client.executeNotebook(...)` + `waitExecution(id, timeout)` | 提交 GCS 上的 `.ipynb`，每 5 秒轮询直至终态 |
| 模型指标 / 价格 | `client.indicator()` | 优先用 `ChatClientSetting.metricsUrl`；未配置回退 Models.dev 免 Key 聚合端点，按 `google` 厂商过滤 |
| 网络代理 | `ChatClientSetting.proxy` | 支持 `http://`、`https://`、`socks5://`，写入对应系统属性 |

### 2.2 ChatClient 链式方法（GoogleChatClient 已实现）

`model` / `temperature` / `maxTokens` / `system` / `thinking` / `thinkingEffort` / `smartSearch` / `skill(SkillManager)` / `addImage(String)` / `addUserHistory` / `addAssistantHistory` / `history(List<ChatMessage>)` / `session(String)` / `newChat` / `chatSync` / `chat` / `indicator` / `type` / `getSetting`。

> `addAttachment` / `addAttachmentUrl` 抛 `UnsupportedOperationException`（该服务商不支持文件附件）。`history(...)` 设置的外部历史优先于内部累积历史；`newChat()` 清空历史与图片附件。每次 `create` 返回新实例，对话状态保存在实例内。

### 2.3 ImageClient 链式方法（GoogleImageClient 已实现）

`model` / `size(width, height)` / `prompt` / `negativePrompt` / `quality` / `style` / `seed` / `steps` / `generate` / `close`。

> `referenceImage` / `imageStrength` / `controlType` / `createTask` / `queryTask` / `models` 均抛 `UnsupportedOperationException`。`quality` / `style` / `seed` / `steps` 会被保存，但当前 `generate` 请求体未使用它们；请求体只含 `prompt`、`sampleCount`、可选 `negativePrompt` 与 `imageSize`（宽高同时设置才写入）。

### 2.4 GoogleColabClient 方法

`listRuntimes()` / `getRuntime(id)` / `startRuntime(id)` / `stopRuntime(id)` / `deleteRuntime(id)` / `listRuntimeTemplates()` / `executeNotebook(notebookUri, outputUri, templateId, displayName)` / `getExecution(id)` / `listExecutions()` / `waitExecution(id, timeoutMillis)` / `deleteExecution(id)` / `close()`。

返回模型：`RuntimeInfo`（name / id / displayName / state / proxyUri）、`TemplateInfo`（name / id / displayName）、`ExecutionInfo`（name / id / displayName / state / message / gcsOutputUri / createTime）。`executeNotebook` 生成作业 ID `exec-<UUID>`；`waitExecution` 每 5 秒轮询一次，遇 `SUCCEEDED` / `FAILED` / `CANCELLED` / `EXPIRED` / `PARTIALLY_SUCCEEDED` 等终态返回，超时返回当前状态。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-google-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 Gemini 对话

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;

ChatClient client = ChatClient.create("google", "YOUR_API_KEY");

// 同步
String answer = client
        .model("gemini-1.5-pro")          // 不设置时缺省 gemini-1.5-pro
        .system("你是一名严谨的工程师")
        .temperature(0.7)
        .maxTokens(2048)
        .chatSync("解释一下 WAL");

// 流式
client.chat("讲个故事", resp -> {
    switch (resp.getState()) {
        case START -> System.out.println("[start]");
        case STREAMING -> System.out.print(resp.getContent());
        case STOP -> System.out.println("\n[done]");
        case ERROR -> System.err.println(resp.getErrorMessage());
    }
});

client.close();
```

### 3.3 Imagen 图片生成

```java
import com.chua.common.support.ai.image.ImageClient;
import java.awt.image.BufferedImage;

ImageClient imagen = ImageClient.create("google", "YOUR_API_KEY");
BufferedImage image = imagen
        .model("imagen-3.0-generate-001")   // 不设置时缺省 imagen-3.0-generate-001
        .size(1024, 768)                    // 可选；宽高同时设置才写入请求
        .prompt("一只在草庐前打盹的橘猫")     // generate(...) 入参为空时使用该值
        .generate(null);
imagen.close();
```

### 3.4 Cloud Storage 对象存储

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.*;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;
import java.io.File;

BucketSetting setting = BucketSetting.builder()
        .bucket("my-project")                 // 同时作为 GCS 项目 ID 与桶名
        .accessKeySecret(serviceAccountJson)  // 服务账号 JSON；为空或解析失败时走 ADC 凭据链
        .build();

try (FileStorage storage = FileStorage.createStorage("gcs", setting)) {
    storage.putObject(PutObjectRequest.of(new File("a.png"), "dir"));   // 第二参数是远程目录，对象键为 dir/a.png
    GetObjectResult got = storage.getObject("dir/a.png");
    boolean exists = storage.existObject(
            ExistObjectRequest.builder().fileName("a.png").filePath("dir").build()).isExists();
    ListObjectResult list = storage.listObject(
            ListObjectRequest.builder().filePath("dir").limit(100).build());
    storage.deleteObject("dir/a.png");
}
```

### 3.5 Colab Enterprise 笔记本执行

> 普通版 Colab（colab.research.google.com）不提供公开 API，本客户端仅支持 **Colab Enterprise**，需要 GCP 项目与服务账号凭据，并启用 Vertex AI API（`aiplatform.googleapis.com`）。

```java
import com.chua.google.support.colab.GoogleColabClient;
import java.util.List;

// projectId + 区域 + 服务账号 JSON（为空时走 ADC 凭据链）；区域缺省 us-central1
try (GoogleColabClient client = new GoogleColabClient("my-project", "us-central1", serviceAccountJson)) {
    List<GoogleColabClient.RuntimeInfo> runtimes = client.listRuntimes();

    // 提交 GCS 上的 .ipynb 执行作业，立即返回
    GoogleColabClient.ExecutionInfo exec = client.executeNotebook(
            "gs://bucket/notebook.ipynb",   // 笔记本地址
            "gs://bucket/output/",          // 输出目录
            "my-runtime-template",          // 运行时模板 ID 或完整资源名
            "demo-execution");              // 作业显示名称

    // 等待执行到达终态（每 5 秒轮询，30 分钟超时）
    exec = client.waitExecution(exec.getId(), 30 * 60 * 1000);
    System.out.println(exec.getState() + " -> " + exec.getGcsOutputUri());
}
```

---

## 4. 配置项

本模块为**零配置模块**：没有 Spring 自动配置类，也没有 `application.properties` / `application.yml` 属性前缀；所有配置通过 SPI 的 Setting 对象或构造参数传入。下表只列**本模块源码实际读取**的字段：

| 配置来源 | 字段 / 参数 | 本模块用途 | 缺省行为 |
| --- | --- | --- | --- |
| `ChatClientSetting` | `appKey` | Gemini API Key，构建 Google Gen AI `Client` | 必填 |
| `ChatClientSetting` | `model` | 默认模型 | 未链式指定时 `gemini-1.5-pro` |
| `ChatClientSetting` | `temperature` | 默认温度 | 未链式指定时 `0.3` |
| `ChatClientSetting` | `maxTokens` | 默认最大输出 Token | 未链式指定时 `2048` |
| `ChatClientSetting` | `system` | 默认系统提示词（会与 `SkillManager` 注入合并） | 无 |
| `ChatClientSetting` | `proxy` | HTTP/HTTPS/SOCKS5 代理，写入 `http.proxyHost` 等系统属性；SOCKS 缺省端口 1080，HTTP 缺省 80 | 不设置则直连 |
| `ChatClientSetting` | `metricsUrl` | `indicator()` 的模型指标端点 | 未配置回退 Models.dev 免 Key 端点 |
| `ImageClientSetting` | `appKey` | `predict` 接口 `?key=` 参数 | 必填 |
| `ImageClientSetting` | `baseUrl` | API 基础地址（末尾 `/` 会被去掉） | `https://generativelanguage.googleapis.com/v1beta` |
| `ImageClientSetting` | `model` / `width` / `height` | 默认模型与尺寸 | 模型 `imagen-3.0-generate-001`；尺寸不写入请求 |
| `BucketSetting` | `bucket` | **同时**作为 GCS 项目 ID（`setProjectId`）与对象操作桶名 | 必填 |
| `BucketSetting` | `accessKeySecret` | 服务账号 JSON 密钥内容（以 `{` 开头时按 `ServiceAccountCredentials` 解析）；失败降级 ADC | ADC：`GOOGLE_APPLICATION_CREDENTIALS` 环境变量 → `gcloud auth application-default login` → GCE/GKE 元数据；均不可用时报 `IllegalStateException` |
| `GoogleColabClient` 构造 | `projectId` / `location` / `serviceAccountJson` | GCP 项目、区域（端点 `{location}-aiplatform.googleapis.com:443`）、服务账号 JSON | `location` 缺省 `us-central1`；密钥解析策略同上 |

> 注意：`GoogleChatClient` 当前只读取上表所列字段，`ChatClientSetting.baseUrl` 未被使用（端点由 Google Gen AI SDK 决定）；`GoogleCloudFileStorage` 只使用 `bucket` 与 `accessKeySecret`，`BucketSetting` 的 `accessKeyId` / `region` / `endpoint` 未参与构造。

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，不是定义方。接口与装配逻辑（`ServiceProvider`、`@Spi` / `@SpiOrder`、`META-INF/extensions` 解析）都在 `utils-support-common-starter`。本模块登记了 3 份 SPI 文件：

| SPI 文件 | 内容 | 可用名字 |
| --- | --- | --- |
| `META-INF/extensions/com.chua.common.support.ai.chat.ChatClient` | `google=com.chua.google.support.GoogleChatClient` | `google`、`gemini`（来自类上 `@Spi({"google", "gemini"})`） |
| `META-INF/extensions/com.chua.common.support.ai.image.ImageClient` | `google=...GoogleImageClient`、`gemini=...GoogleImageClient` | `google`、`gemini` |
| `META-INF/services/com.chua.common.support.storage.FileStorage` | `com.chua.google.support.storage.GoogleCloudFileStorage` | `gcs`、`google`（来自类上 `@Spi({"gcs", "google"})`） |

调用方式（名字即上表 SPI 名）：

```java
ChatClient chat   = ChatClient.create("google", apiKey);          // 内部走 ServiceProvider
ImageClient image = ImageClient.create("gemini", apiKey);
FileStorage gcs   = FileStorage.createStorage("gcs", bucketSetting);
```

`GoogleColabClient` 不是 SPI，直接 `new GoogleColabClient(projectId, location, serviceAccountJson)`。若要接入新的 Google 能力（如 Cloud Vision），应在本模块新增实现类并登记 SPI 文件，而不是修改门面。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（父 pom `utils-support-parent-starter` 的 `maven.compiler.source/target` 与 `java.version` 均为 25）。
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译（`provided`）。

```bash
mvn -pl utils-support-cloud-parent/utils-support-google-starter -am clean package
```

### 6.2 依赖清单（来自 pom.xml）

| 依赖 | 用途 |
| --- | --- |
| `com.chua:utils-support-common-starter` | SPI 接口、Setting、门面与工具（同版本 `${project.version}`） |
| `com.google.cloud:google-cloud-storage` | GCS 对象存储 SDK |
| `com.google.cloud:google-cloud-vision` | Cloud Vision SDK（见第 7 节：源码未提供 Vision 封装） |
| `com.google.cloud:google-cloud-aiplatform` | Vertex AI SDK（Colab Enterprise 的 `NotebookServiceClient`） |
| `com.google.genai:google-genai:1.73.0` | Gemini 对话（`Client` / `generateContentStream`） |
| `org.slf4j:slf4j-api` | 日志 |
| `org.projectlombok:lombok` | 编译期（`provided`） |

父模块为 `utils-support-cloud-parent`（云服务集成父模块），其上级为 `utils-support-parent-starter`（版本 `4.0.0.51`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| `ChatClient` / `ImageClient` / `FileStorage` 等 SPI 接口与统一门面 | 在 `utils-support-common-starter`，本模块只提供 Google 实现 |
| 其它云厂商 / 大模型（OpenAI、Claude、智谱、腾讯、阿里等） | 在 `utils-support-cloud-parent` 的其它 `*-starter` |
| Cloud Vision（图片识别 / 标注检测） | pom 声明了 `google-cloud-vision` 依赖，但**源码未提供**任何 Vision 封装类 |
| 普通版 Colab（colab.research.google.com） | 无公开 API，不支持；仅支持 Colab Enterprise |
| Gemini 文件附件（`addAttachment` / `addAttachmentUrl`） | 抛 `UnsupportedOperationException` |
| Imagen 参考图 / 图生图、ControlNet、异步任务、模型列表查询 | 抛 `UnsupportedOperationException`；`quality` / `style` / `seed` / `steps` 当前请求体未使用 |
| GCS 原生分片上传协议 | 未对接；`createMultipartStorage()` 为父类基于本地临时目录的默认实现（合并后走 `putObject`） |
| 对话自定义端点（`ChatClientSetting.baseUrl`） | 当前源码未读取该字段，端点由 Google Gen AI SDK 决定 |

---

## 许可证

Apache License 2.0
