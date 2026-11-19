# utils-support-deeplearning-ollama-starter

> Ollama 本地大模型推理服务集成：基于 [ollama4j](https://github.com/ollama4j/ollama4j) 原生 API（`/api/chat`、`/api/embed`、`/api/generate`、`/api/tags`）提供本地 LLM 对话、文本向量化、特征提取、图片生成、语音转写与服务/模型运维，以 SPI `provider="ollama"` 接入 utils AI 门面。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.ollama.support`

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
    <artifactId>utils-support-deeplearning-ollama-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Ollama 本地推理服务的 Java 客户端集成，把对话/向量/特征/图片/语音/运维统一挂到 utils 的 `ChatClient` / `EmbeddingClient` / `FeatureClient` / `ImageClient` / `VirtualClient` SPI 门面下 | 不是 Ollama 服务端，也不负责安装、托管或加速 Ollama 进程 |
| 实现 | 5 个 SPI 客户端 + 1 个独立运维门面 `OllamaManager` + 2 个支撑工具类，全部基于 ollama4j `1.1.7` | 不含 OpenAI 兼容 HTTP 直连实现（`/v1/*` 路径常量仅为预留，当前源码未使用） |
| 依赖 | 仅依赖 `utils-support-common-starter`、`ollama4j`、`slf4j-api`、`lombok` | 不依赖 Spring、不依赖云端 AI SDK、无需 API Key（apiKey 传空串即可） |
| 模型 | 模型列表动态取自服务端 `GET /api/tags`；离线兜底清单见 `OllamaConstants.COMMON_MODELS` | 不内置任何模型权重；模型需先由 `ollama pull` 拉取到本机服务 |

模块内 8 个源文件（均在 `com.chua.ollama.support` 包）：

| 类 | 职责 |
| --- | --- |
| `OllamaChatClient` | 本地对话（SPI `provider="ollama"`，实现 `ChatClient`） |
| `OllamaEmbeddingClient` | 文本嵌入向量（实现 `EmbeddingClient`） |
| `OllamaFeatureClient` | 文本特征提取（实现 `FeatureClient`） |
| `OllamaImageClient` | 图片生成（实现 `ImageClient`） |
| `OllamaAudioClient` | 语音识别/转写（实现 `VirtualClient`） |
| `OllamaManager` | 服务连通性、模型生命周期运维门面（`@Spi("ollama-manager")`，不走 `ChatClient` SPI 工厂） |
| `OllamaSupport` | 客户端创建、地址归一化、异常包装的静态工具 |
| `OllamaConstants` | 默认地址、API 路径、超时、常见模型清单 |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 本地对话 | `ChatClient.create("ollama", "").model(...).chatSync(prompt)` | ollama4j 原生 `/api/chat`；支持多轮历史、采样参数、`json_object` 响应格式 |
| 对话带用量统计 | `chatSyncWithResponse(prompt)` → `ChatSyncResponse` | `AiUsage` 携带 `inputTokens` / `outputTokens` / `totalTokens`（来自 Ollama 评估计数） |
| 多轮历史 | `addUserHistory` / `addAssistantHistory` / `history(List)` / `newChat()` | 增量历史按时间正序累积；`history(...)` 外部一次性历史覆盖增量历史 |
| 文本向量化 | `EmbeddingClient.create("ollama", "").model("nomic-embed-text").embedding(text)` | 单条 `float[]`；批量 `embeddingBatch(String[])` 返回 `float[][]` |
| 特征提取 | `FeatureClient.create("ollama", "").extract(text)` | 与嵌入共用 `/api/embed`，面向特征检索/去重场景 |
| 图片生成 | `ImageClient.create("ollama", "").model(...).size(w, h).generate(prompt)` | `POST /api/generate` 图像生成实验特性，返回 `BufferedImage`；异步契约由 `createTask` / `queryTask` 本地模拟 |
| 语音转写 | `VirtualClient.create("ollama", "").model("whisper").language("zh").transcribe(path)` | 音频 Base64 嵌入提示词交由 whisper 类模型转写；异步契约同上 |
| 服务/模型运维 | `new OllamaManager(host)` | `ping` / `listModels` / `getVersion` / `ps` / `pullModel` / `getModelDetails` / `createModel` / `deleteModel` / `unloadModel` |
| 查可用模型 | 各客户端 `.models()` | 经 ollama4j `listModels()`（`GET /api/tags`）动态获取，返回 `List<ModelDefinition>`；失败时返回空列表 |

---

## 2. 核心 API 与速查表

### 2.1 OllamaChatClient —— 本地对话

```java
import com.chua.common.support.ai.chat.ChatClient;

// 纯文本对话（需先 ollama pull minicpm5-2b）
String answer = ChatClient.create("ollama", "")
    .model("minicpm5-2b")
    .temperature(1.0)
    .topP(0.95)
    .maxTokens(512)
    .chatSync("你好，介绍一下你自己");

// 多轮历史
String reply = ChatClient.create("ollama", "")
    .model("minicpm5-2b")
    .addUserHistory("我叫小明")
    .chatSync("你还记得我叫什么吗？");
```

| 成员 | 说明 |
| --- | --- |
| `chatSync(String prompt)` | 同步对话，返回合并后的文本 |
| `chatSyncWithResponse(String prompt)` | 同步对话，返回 `ChatSyncResponse`（text + `AiUsage` 用量） |
| `model(String)` / `system(String)` / `temperature(double)` / `maxTokens(int)` / `topP(Double)` | 链式设置模型、系统提示词与采样参数 |
| `responseFormat(String)` | `json_object` 开启 JSON 模式（ollama4j `withGetJsonResponse()`）；`json_schema` 仅记录 debug 日志、不自动构建 schema；其余取值保持普通文本 |
| `addUserHistory` / `addAssistantHistory` | 追加 user / assistant 历史 |
| `history(List<ChatMessage>)` | 传入外部一次性历史，覆盖增量历史 |
| `newChat()` | 清空增量历史与外部历史 |
| `models()` | 已安装模型列表（`capabilities=["chat"]`），异常时 log.warn 并返回空列表 |
| `close()` | 等价 `newChat()`，清空历史 |

行为细节（与源码一致）：

- 未指定 `model` 时默认使用 `llama3.2:3b`。
- 采样参数映射为 ollama4j `Options`：`temperature`、`num_predict`（由 `maxTokens` 映射）、`top_p`。
- 思考型模型（如 MiniCPM5）会把推理过程放入 `thinking` 字段、答案放入 `response` 字段；输出合并两者（以换行拼接），两者均空时返回空字符串而非 `null`。
- 对话异常统一经 `OllamaSupport.wrap("chat", e)` 包装为 `RuntimeException` 抛出。

### 2.2 OllamaEmbeddingClient —— 文本向量化

```java
import com.chua.common.support.ai.embedding.EmbeddingClient;

float[] vector = EmbeddingClient.create("ollama", "")
    .model("nomic-embed-text")
    .embedding("要向量化的文本");

float[][] vectors = EmbeddingClient.create("ollama", "")
    .model("nomic-embed-text")
    .embeddingBatch(new String[]{"文本1", "文本2"});
```

| 成员 | 说明 |
| --- | --- |
| `embedding(String text)` | 单条文本向量（`/api/embed`） |
| `embeddingBatch(String[] texts)` | 批量向量，一次请求 |
| `embeddingWithResponse(String)` / `embeddingBatchWithResponse(String[])` | 返回 `EmbeddingResponse`（向量 + index + dimensions） |
| `model(String)` / `dimensions(int)` | 模型与输出维度；`dimensions` 非空时写入 embed 请求 options |
| `provider(String)` | 空实现（本模块固定 provider 为 `ollama`） |
| `models()` | 模型列表（`capabilities=["text-embedding"]`） |
| `close()` | 无操作（ollama4j 客户端无状态） |

未指定 `model` 时默认使用 `nomic-embed-text`。

### 2.3 OllamaFeatureClient —— 特征提取

```java
import com.chua.common.support.ai.feature.FeatureClient;

float[] vector = FeatureClient.create("ollama", "")
    .model("nomic-embed-text")
    .extract("要提取特征的文本");
```

与 `OllamaEmbeddingClient` 共用底层 `/api/embed` 能力，面向 `FeatureClient` 门面（特征检索 / 去重场景）。`extract` 对空白文本直接返回空数组；`models()` 的 capabilities 为 `["text-feature"]`。

### 2.4 OllamaImageClient —— 图片生成

```java
import com.chua.common.support.ai.image.ImageClient;
import java.awt.image.BufferedImage;

BufferedImage img = ImageClient.create("ollama", "")
    .model("image-model")
    .size(1024, 1024)
    .generate("一只可爱的猫");
```

| 成员 | 说明 |
| --- | --- |
| `generate(String prompt)` | 同步生成；返回 Base64 解码后的 `BufferedImage`；未返回图像数据或解码失败时抛 `RuntimeException` |
| `createTask(String prompt)` | Ollama 无原生异步任务：立即执行生成并缓存结果，返回本地任务号 `ollama-image-<时间戳>` 供 `queryTask` 轮询 |
| `queryTask(String taskId)` | 查询任务结果；未知任务返回 `FAILED` 状态的 `ImageResponse` |
| `size(int width, int height)` | 图片宽高（写入 `OllamaGenerateImageRequest`） |
| `prompt(String)` | 链式占位，当前实现不使用该值 |
| `models()` | 模型列表（`capabilities=["image-generation"]`） |
| `close()` | 清空任务缓存 |

> Ollama 图像生成为实验能力，仅部分支持图像输出的模型可用；模型不支持时由底层异常提示。

### 2.5 OllamaAudioClient —— 语音转写（ASR）

```java
import com.chua.common.support.ai.audio.VirtualClient;
import java.nio.file.Path;

String text = VirtualClient.create("ollama", "")
    .model("whisper")
    .language("zh")
    .transcribe(Path.of("audio.wav"));
```

| 成员 | 说明 |
| --- | --- |
| `transcribe(Path path)` | 读取音频字节，Base64 嵌入提示词 `<audio>...</audio>`，经 `POST /api/generate` 交由 whisper 类模型转写，返回文本 |
| `createTask(Path path)` | 同步转写后缓存结果，返回本地任务号 `ollama-asr-<时间戳>` |
| `queryTask(String taskId)` | 查询任务结果；未知任务返回 `FAILED` 的 `AudioResponse` |
| `language(String)` | 识别语言（写入提示词，如 `zh`） |
| `model(String)` | 语音模型，未指定时默认 `whisper` |
| `models()` | 模型列表（`capabilities=["audio-transcribe"]`） |
| `close()` | 清空任务缓存 |

音频输入优先级：`AudioClientSetting` 中已配置的 `audio` 字节数组优先，其次读取 `path` 文件；两者均为空抛 `IllegalArgumentException`。

### 2.6 OllamaManager —— 服务与模型运维门面

```java
import com.chua.ollama.support.OllamaManager;
import io.github.ollama4j.models.response.Model;

OllamaManager manager = new OllamaManager("http://localhost:11434");
boolean up = manager.ping();
List<Model> models = manager.listModels();
manager.pullModel("minicpm5-2b");
```

| 方法 | 说明 |
| --- | --- |
| `OllamaManager(String host)` / `OllamaManager()` | 构造；host 可为空（回退默认地址） |
| `ping()` | 探测 Ollama 服务是否可达 |
| `ps()` | 查询当前内存中已加载的模型进程（`ModelProcessesResult`） |
| `listModels()` | 列出已安装模型（`List<Model>`） |
| `getVersion()` | 查询 Ollama 服务版本 |
| `pullModel(String)` / `pullModel(String, ModelPullListener)` | 拉取模型，可带进度监听 |
| `getModelDetails(String)` | 查询模型详情（`ModelDetail`） |
| `createModel(CustomModelRequest)` | 创建自定义模型 |
| `deleteModel(String, boolean ignoreIfNotPresent)` | 删除模型；可设置不存在时忽略错误 |
| `unloadModel(String)` | 从内存卸载模型 |

所有方法在底层失败时抛 `io.github.ollama4j.exceptions.OllamaException`。`getOllama()`（lombok `@Getter`）可取到底层 ollama4j `Ollama` 实例。

> `OllamaManager` 虽标注 `@Spi("ollama-manager")`，但作为**独立门面**暴露，不走 `ChatClient` SPI 工厂，供运维 / 初始化场景直接 `new` 使用。

### 2.7 OllamaSupport 与 OllamaConstants —— 支撑工具

```java
import com.chua.ollama.support.OllamaSupport;
import com.chua.ollama.support.OllamaConstants;

Ollama client = OllamaSupport.client("http://localhost:11434/");  // 尾斜杠自动去除
String url    = OllamaSupport.normalize("http://localhost:11434/v1"); // -> http://localhost:11434

OllamaConstants.DEFAULT_BASE_URL;        // http://localhost:11434
OllamaConstants.CONNECT_TIMEOUT_MILLIS;  // 5_000
OllamaConstants.READ_TIMEOUT_MILLIS;     // 300_000（本地推理较慢，放宽到 300 秒）
OllamaConstants.PATH_CHAT_COMPLETIONS;   // /v1/chat/completions（预留，当前源码未使用）
OllamaConstants.PATH_MODELS;             // /v1/models（预留，当前源码未使用）
OllamaConstants.PATH_TAGS;               // /api/tags
OllamaConstants.COMMON_MODELS;           // 常见本地模型清单（离线兜底/文档参考）
```

- `OllamaSupport.client(host)`：创建 ollama4j `Ollama` 实例；host 为空回退 `DEFAULT_BASE_URL`。
- `OllamaSupport.normalize(host)`：trim、去除尾部斜杠；若传入 OpenAI 兼容的 `/v1` 后缀则去掉（Ollama 原生端点挂在根路径）。
- `OllamaSupport.wrap(action, e)`：把底层异常包装为 `RuntimeException`，消息形如 `Ollama <action> 失败: <原因>`。
- `OllamaConstants.COMMON_MODELS`：`llama3.2:3b`、`llama3.2:1b`、`qwen2.5:7b`、`qwen2.5:1.5b`、`gemma3:4b`、`deepseek-r1:7b`、`mistral:7b`、`phi3:mini`、`minicpm5-2b`、`minicpm5-1b`；实际可用模型以服务端 `/api/tags` 返回为准。

---

## 3. 快速开始

前提：本机已安装并启动 Ollama（默认监听 `http://localhost:11434`），并已拉取模型：

```bash
ollama pull minicpm5-2b
ollama pull nomic-embed-text
```

Maven 依赖：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-ollama-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

最短可运行示例（对话 + 向量化 + 运维探测）：

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.ollama.support.OllamaManager;

public class OllamaQuickStart {
    public static void main(String[] args) throws Exception {
        // 1. 本地对话（apiKey 传空串即可，Ollama 无需鉴权）
        String answer = ChatClient.create("ollama", "")
                .model("minicpm5-2b")
                .chatSync("你好，介绍一下你自己");
        System.out.println(answer);

        // 2. 文本向量化
        float[] vector = EmbeddingClient.create("ollama", "")
                .model("nomic-embed-text")
                .embedding("要向量化的文本");
        System.out.println("向量维度: " + vector.length);

        // 3. 服务连通性与模型列表
        OllamaManager manager = new OllamaManager("http://localhost:11434");
        System.out.println("ping = " + manager.ping());
        manager.listModels().forEach(m -> System.out.println(m.getName()));
    }
}
```

服务不在默认地址时，把地址写进 Setting 的 `baseUrl`，或使用三参工厂：

```java
ChatClient.create("ollama", "", "http://127.0.0.1:11434")
          .model("llama3.2:3b")
          .chatSync("hi");
```

---

## 4. 配置项

**本模块没有独立的配置文件与配置键。** `src/main/resources` 下仅有 5 个 SPI 注册文件（见第 5 节），无 `application.properties` / `application.yml`，也未定义任何系统属性或环境变量键。

所有配置通过代码侧构造时的 `*Setting` 对象 + 链式方法传入。源码实际读取的 Setting 字段如下：

| Setting 对象 | 被读取的字段 | 作用 |
| --- | --- | --- |
| `ChatClientSetting` | `baseUrl`、`model`、`system`、`temperature`、`maxTokens`、`topP`、`responseFormat` | 对话服务地址、默认模型、系统提示词、采样参数、响应格式 |
| `EmbeddingClientSetting` | `baseUrl`、`model`、`dimensions` | 嵌入服务地址、默认模型、输出向量维度 |
| `FeatureClientSetting` | `baseUrl`、`model`、`dimensions` | 特征提取服务地址、默认模型、输出向量维度 |
| `ImageClientSetting` | `baseUrl`、`model` | 图片生成服务地址、默认模型 |
| `AudioClientSetting` | `baseUrl`、`model`、`language`、`audio`（`byte[]`） | 语音服务地址、默认模型、识别语言、内置音频字节 |

行为常量（代码内固定值，不可经配置修改）：默认地址 `http://localhost:11434`、连接超时 `5_000` ms、读取超时 `300_000` ms、异步任务缓存容量 `256`（LRU，超出驱逐最老任务）。

---

## 5. SPI 扩展点

本模块是 utils AI SPI 的**实现方**：5 个客户端均以 `provider="ollama"` 注册，使用方通过 `XxxClient.create("ollama", apiKey)` 或 `ServiceProvider` 按名字获取。

SPI 注册文件位于 `src/main/resources/META-INF/services/`（文件名为接口全限定名，每行一个实现类全限定名）：

| 接口（注册文件名） | 注册的实现 | 类注解 |
| --- | --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `com.chua.ollama.support.OllamaChatClient` | `@Spi("ollama")` |
| `com.chua.common.support.ai.embedding.EmbeddingClient` | `com.chua.ollama.support.OllamaEmbeddingClient` | `@Spi("ollama")` |
| `com.chua.common.support.ai.feature.FeatureClient` | `com.chua.ollama.support.OllamaFeatureClient` | `@Spi("ollama")` |
| `com.chua.common.support.ai.image.ImageClient` | `com.chua.ollama.support.OllamaImageClient` | `@Spi("ollama")` |
| `com.chua.common.support.ai.audio.VirtualClient` | `com.chua.ollama.support.OllamaAudioClient` | `@Spi("ollama")` |

`OllamaManager` 标注 `@Spi("ollama-manager")`，但不注册到上述任何接口文件，也不走 `ChatClient` SPI 工厂——它定位为独立运维门面，直接 `new` 使用。

扩展本模块的方式：若要替换/新增 Ollama 侧能力（如自定义协议、代理、重试），实现对应 common-starter 的 AI 接口并按同样方式登记即可；本模块未提供额外的内部扩展接口。

---

## 6. 构建与依赖

模块位于聚合工程 `utils-support-parent-starter` → `utils-support-deeplearning-parent` 下，随父工程一起构建：

```bash
mvn -o -q compile -DskipTests
```

依赖清单（本模块 `pom.xml`）：

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | AI 门面接口、SPI 注解、工具类 |
| `io.github.ollama4j:ollama4j` | `1.1.7`（顶层 BOM 管理） | Ollama 原生 API（`/api/chat`、`/api/embed`、`/api/generate` 等）完整客户端 |
| `org.slf4j:slf4j-api` | 顶层管理 | 日志门面 |
| `org.projectlombok:lombok` | provided | 编译期代码生成 |

编译要求 JDK 25（`maven.compiler.release=25`，开启 `--enable-preview`）。本模块无测试源码目录，无额外构建插件。

---

## 7. 边界：这些能力不在本模块

| 能力 | 说明 |
| --- | --- |
| Ollama 服务端本身 | 本模块只是客户端；安装、启动、进程托管、GPU 调度、模型文件加速下载均不在范围内（`pullModel` 仅调用服务端拉取接口） |
| OpenAI 兼容 HTTP 直连 | `OllamaConstants` 中的 `PATH_CHAT_COMPLETIONS`（`/v1/chat/completions`）、`PATH_MODELS`（`/v1/models`）为预留常量，当前源码未使用；对话与模型列表实际走 ollama4j 原生 `/api/chat`、`/api/tags` |
| 云端模型 / 远程 API | 仅面向本地 Ollama 服务；不涉及任何云端 LLM 提供商 |
| 工具调用、流式对话输出、`json_schema` 自动构建 | `OllamaChatClient` 源码未提供对应实现；`responseFormat=json_schema` 仅记录 debug 日志。可用能力以 common-starter `ChatClient` 接口契约与本模块已实现方法为准 |
| 图片/语音模型保证 | Ollama 图像生成为实验特性，语音识别依赖服务端已加载 whisper 类模型；模型未加载时由底层返回错误，本模块不做模型可用性兜底 |
| 真实异步任务 | Ollama 无原生异步任务端点；`createTask` / `queryTask` 是本地同步执行 + 结果缓存的模拟契约，任务号仅在本客户端实例内有效（LRU 容量 256，超出驱逐最老任务） |
| 其它深度学习框架 | ONNX / PyTorch / Paddle / TensorFlow / LangChain4j 等由 `utils-support-deeplearning-parent` 下同层其它 `*-starter` 提供，与本模块并列、互不依赖 |
