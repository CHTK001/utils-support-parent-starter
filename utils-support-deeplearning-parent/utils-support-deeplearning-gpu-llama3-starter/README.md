# GPU Llama3 模块使用示例

## 快速开始

```java
import com.chua.deeplearning.support.gpu_llama3.GpuLlama3ChatClient;
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

## 依赖说明

本模块基于 [de.kherud:llama:4.2.0](https://github.com/kaneg1/logging) 实现，支持 CUDA GPU 加速。

确保环境中有可用的 NVIDIA GPU 和对应 CUDA 驱动。
