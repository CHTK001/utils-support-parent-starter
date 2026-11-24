# utils-support-deeplearning-jitllm-starter

[jitLLM](https://github.com/beehive-lab/jitllm)（Java Inference Tornado toolkit）GPU 大模型推理集成：在 JVM 内加载 GGUF 模型，经 TornadoVM 自动加速到 GPU，提供统一的 `ChatClient` SPI 能力（`provider="jitllm"`）。

## 依赖坐标

```xml
<dependency>
    <groupId>io.github.beehive-lab</groupId>
    <artifactId>jitllm</artifactId>
    <version>3.0.0-jdk22plus</version>
</dependency>
```

> `jdk22plus` 变体适配 JDK 22~27（本项目编译目标 Java 25）。`jitllm` 为 shaded fat jar，自带 TornadoVM 依赖；运行期需要可用的 GPU 后端（CUDA/OpenCL）或回退 CPU 后端，并保证 TornadoVM 原生库可加载。

## 用法

```java
// 纯文本对话：model 指向本地 GGUF 文件
String answer = ChatClient.create("jitllm", "")
    .model("Qwen3-0.6B-Q8_0.gguf")          // 绝对/相对路径，或配合 baseUrl 模型目录
    .temperature(0.8)
    .maxTokens(512)
    .chatSync("你好，介绍一下你自己");

// 多轮历史
String reply = ChatClient.create("jitllm", "")
    .model("Qwen3-0.6B-Q8_0.gguf")
    .addUserHistory("我叫小明")
    .chatSync("你还记得我叫什么吗？");

// 流式输出
ChatClient.create("jitllm", "")
    .model("Qwen3-0.6B-Q8_0.gguf")
    .chat("讲个故事", response -> {
        switch (response.getState()) {
            case START     -> System.out.println("开始");
            case STREAMING -> System.out.print(response.getContent());
            case STOP      -> System.out.println("\n完成");
            case ERROR     -> System.err.println(response.getErrorMessage());
        }
    });
```

## 模型路径解析口径

| 条件 | 解析结果 |
|---|---|
| `model` 为存在的绝对路径 | 直接使用 |
| 配置 `baseUrl`（模型目录） | `baseUrl/model` |
| 未配置 `baseUrl` | `./models/model`（相对工作目录） |

## 模型管理

- `models()`：扫描模型目录（`baseUrl` 或模型文件父目录）下全部 `*.gguf`；无法定位目录时仅返回当前配置模型。
- 模型权重按路径做进程级缓存，多客户端实例共享；`JitllmSupport.releaseAll()` 可整体释放（宿主停机钩子场景）。

## 支持的模型族

Llama 3、Mistral、Qwen 2.5 / 3 / 3.8、Phi-3、IBM Granite 3.3 / 4.0、Gemma 4、DeepSeek-R1-Distill 等 GGUF 模型（以 jitLLM 上游支持列表为准）。

## llama.cpp 兼容路径（原 gpu-llama3 模块迁入）

原 `utils-support-deeplearning-gpu-llama3-starter` 的全部能力已并入本模块，包名迁移为 `com.chua.jitllm.support.llama3`，SPI provider 名保持 `gpu-llama3` 不变，`ChatClient.create("gpu-llama3", ...)` 调用方无需改动。

### 进程内推理（de.kherud llama.cpp 绑定）

- 客户端：`GpuLlama3ChatClient`（`@Spi("gpu-llama3")`，基于 `AbstractLocalChatClient` + `ModelRegistry` 调度）
- 翻译器：`Llama3ChatTranslator`（llama.cpp JNI 进程内加载 GGUF，CUDA GPU 加速）
- 内置模型元数据（`GpuLlama3ModelRegistrar`）：`llama-3-8b-it`、`llama-3-70b-it`、`llama-3-2b-it`、`minicpm5-2b-it`，首次使用自动从 hf-mirror 下载 GGUF 到 `../llama3/` 目录

运行参数（`configure(Map)` / `DetectionConfiguration.systemOption` 注入）：`useGpu`、`gpuLayers`（-1 全部层 / 0 CPU / N 层）、`ctxSize`（默认 4096）、`topK`（默认 40）、`temperature`（默认 0.7）、`nPredict`（默认 512）、`threads`（默认 CPU 核数）、`device`（auto/cpu/gpu/cuda）。

### llama-server sidecar 模式（OpenAI 兼容转发）

LFM3-1.7B（LiquidAI，tri-hybrid 架构）需要 llama.cpp ≥ v0.8.0/b11960 才能加载，进程内 de.kherud 绑定（b10126）过旧无法支持，走外部 llama-server sidecar：

1. 准备 GGUF：放到 `../llama3/`，或 `ModelRegistry.ensureDownloaded("lfm3-1.7b")` 自动下载（约 1.07GB，hf-mirror 失败回落 huggingface.org）。
2. 启动 sidecar（外部脚本管理，Java 侧不自动拉起）：

   ```bash
   llama-server -m ../llama3/LFM3-1.7B-Q4_K_M.gguf --host 127.0.0.1 --port 9931 --ctx-size 8192 --threads 8
   ```

3. Java 调用：

   ```java
   // 多轮对话 + 系统提示词（对话历史需显式维护）
   ChatClient client = ChatClient.create("gpu-llama3", "EMPTY", "EMPTY").model("lfm3-1.7b");
   client.system("你是一个简洁的中文助手");
   client.addUserHistory("我叫张三");
   client.addAssistantHistory("你好，张三！");
   String reply = client.chatSync("我叫什么名字？");
   ```

- 翻译器：`LlamaServerChatTranslator`（`POST /v1/chat/completions`，多轮消息透传，chat template 由 llama-server 按 GGUF 元数据自动应用）
- 模型元数据：`LlamaServerModelRegistrar`（`lfm3-1.7b`）
- 运行参数：`baseUrl`（默认 `http://127.0.0.1:9931`，系统属性 `llama.server.url` 可覆盖）、`temperature`（LFM3 推荐 0.1）、`topK`（推荐 50）、`topP`、`nPredict`、`timeoutMillis`（默认 300000）。`device / gpuLayers / ctxSize / threads` 属 sidecar 启动参数，需在启动 llama-server 时指定。输出中的 qwen3 风格思考段（成对 `&lt;think&gt;...&lt;/think&gt;` 标记）会被自动剥离后返回。

### 依赖

- `de.kherud:llama:4.2.0`（进程内路径，需 NVIDIA GPU + CUDA 驱动）
- `io.github.beehive-lab:jitllm:3.0.0-jdk22plus`（jitllm 引擎路径）
