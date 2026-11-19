# GPU Llama3 模块使用示例

## 快速开始

```java
import com.chua.deeplearning.support.gpu.llama3.GpuLlama3ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

// 创建 GPU Llama3 对话客户端
ChatClientSetting setting = new ChatClientSetting();
setting.setModelId("llama-3-8b-it");  // 可选: llama-3-8b-it, llama-3-70b-it, llama-3-2b-it

try (GpuLlama3ChatClient client = new GpuLlama3ChatClient(setting)) {
    client.start();
    
    // 对话示例
    String response = client.chat("你好，请用一句话介绍自己");
    System.out.println("AI: " + response);
}
```

## 配置 GPU 参数

在 `Llama3ChatTranslator` 中已预设 GPU 参数：

```java
// ModelParameters 配置
new ModelParameters()
    .setModel(modelPath.toString())
    .setCtxSize(4096)                    // 上下文大小
    .setThreads(Runtime.getRuntime().availableProcessors())
    .setGpuLayers(-1);                   // -1 表示全部层放到 GPU
```

## 支持的模型

| 模型 ID | 参数量 | 显存需求 | 适用场景 |
|--------|--------|---------|---------|
| `llama-3-2b-it` | 2.7B | ~6GB | 轻量级、边缘设备 |
| `llama-3-8b-it` | 8B | ~16GB | 通用对话 |
| `llama-3-70b-it` | 70B | ~40GB+ | 高质量推理 |

## 模型下载

首次使用时会自动从 HuggingFace Mirror 下载 GGUF 格式模型到 `../llama3/` 目录。

可通过环境变量 `LLAMA3_MODEL_PATH` 指定自定义路径：
```bash
export LLAMA3_MODEL_PATH=/path/to/Meta-Llama-3-8B-Instruct.Q4_K_M.gguf
```

## LFM3-1.7B（llama-server sidecar 模式）

LFM3-1.7B（LiquidAI，tri-hybrid 架构）需要 llama.cpp ≥ v0.8.0/b11960 才能加载，进程内 de.kherud 绑定（b10126）过旧无法支持。该模型走 **外部 llama-server sidecar + OpenAI 兼容接口** 方式：Java 侧由 `LlamaServerChatTranslator` 转发 `POST /v1/chat/completions`，chat template 由 llama-server 按 GGUF 元数据自动应用。

### 1. 准备模型（二选一）

- 手动放置：将 `LFM3-1.7B-Q4_K_M.gguf` 放到注册路径 `../llama3/` 下；
- 自动下载：首次对话时自动从 hf-mirror 下载（约 1.07GB，失败回落 huggingface.org），落盘到 `download/lfm3-1.7b/`，下载进度每 10% 打印一次日志（服务端未返回大小时按约 16MB 步进）。也可在应用启动时主动预热：

```java
ModelRegistry.ensureDownloaded("lfm3-1.7b");
```

### 2. 启动 sidecar（外部脚本管理，Java 侧不自动拉起）

```bash
llama-server -m ../llama3/LFM3-1.7B-Q4_K_M.gguf --host 127.0.0.1 --port 9931 --ctx-size 8192 --threads 8
```

### 3. Java 调用

```java
// 单轮对话
String answer = ChatClient.create("gpu-llama3", "EMPTY", "EMPTY")
        .model("lfm3-1.7b")
        .chatSync("你好，请用一句话介绍自己");

// 多轮对话 + 系统提示词（对话历史需显式维护）
ChatClient client = ChatClient.create("gpu-llama3", "EMPTY", "EMPTY").model("lfm3-1.7b");
client.system("你是一个简洁的中文助手");
client.addUserHistory("我叫张三");
client.addAssistantHistory("你好，张三！");
String reply = client.chatSync("我叫什么名字？");
```

> 多轮能力由核心模块的 `ConversationTranslator` 契约提供：`AbstractLocalChatClient` 会把 system + 历史 + 本轮输入组装成完整消息列表透传给 sidecar；不支持多轮的翻译器自动回退为纯文本拼接，行为与历史版本完全一致。系统提示词优先级：`system()` > `ChatClientSetting.getSystem()`。

### 4. 运行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `baseUrl` | llama-server 地址，系统属性 `llama.server.url` 可覆盖 | `http://127.0.0.1:9931` |
| `temperature` | 采样温度（LFM3 推荐 0.1） | 不注入 |
| `topK` | Top-K 采样（LFM3 推荐 50） | 不注入 |
| `topP` | Top-P 采样 | 不注入 |
| `nPredict` | 最大输出 token 数（max_tokens） | 不注入 |
| `timeoutMillis` | 单次推理读超时（CPU 推理较慢） | `300000` |

> `temperature / topK / topP / nPredict` 也可通过 `ChatClientSetting` 同名字段设置；`baseUrl` 与 `timeoutMillis` 需通过 translator 的 `configure(Map)` 注入。`device / gpuLayers / ctxSize / threads` 属于 sidecar 启动参数，需在启动 llama-server 时指定。输出中的 qwen3 风格思考段（`discussion...StandardError`）会被自动剥离后返回。

## 依赖说明

本模块基于 [de.kherud:llama:4.2.0](https://github.com/kaneg1/logging) 实现，支持 CUDA GPU 加速。

确保环境中有可用的 NVIDIA GPU 和对应 CUDA 驱动。
