# utils-support-deeplearning-mage-starter

[Microsoft Mage](https://github.com/microsoft/Mage) 远程推理客户端（SPI provider=`"mage"`）。

Mage 家族固定 4B 参数（fp16 约 8GB/个），官方仅发布 PyTorch 权重，**无 ONNX/TorchScript 导出**，
无法内嵌到 JVM 进程运行。本模块通过 HTTP 调用**自部署**的 Mage 推理服务接入两大能力：

| 能力 | 模型 | Java SPI |
|---|---|---|
| 文生图 / 指令图像编辑 | Mage-Flow（Base / RL / 4 步 Turbo） | `com.chua.common.support.ai.image.ImageClient` |
| 图像 / 视频理解 | Mage-VL（Codec-ViT + Qwen3-4B） | `com.chua.common.support.ai.chat.ChatClient` |

## 服务端部署

见 [`scripts/server.py`](scripts/server.py) 头部注释。要点：

```bash
git clone https://github.com/microsoft/Mage && cd Mage/mage_flow
pip install -r requirements.txt && pip install -e . --no-deps

pip install fastapi "uvicorn[standard]" pillow opencv-python-headless transformers accelerate

MAGE_API_KEY=sk-my-secret python server.py --host 0.0.0.0 --port 7861
```

- 首次调用各模型时自动从 🤗 Hub 拉取权重并缓存
- 环境变量：`MAGE_API_KEY`（Bearer 鉴权）、`MAGE_DEVICE`、`MAGE_VL_MODEL`
- 接口为 OpenAI 风格子集：`POST /v1/images/generations`、`POST /v1/images/edits`、
  `POST /v1/chat/completions`、`GET /v1/models`

## 模型 ID

### 生成（ImageClient.model）

| ID | 权重 | 默认步数 | 说明 |
|---|---|---|---|
| `mage-flow-base` | microsoft/Mage-Flow-Base | 30 | 基础版 |
| `mage-flow` | microsoft/Mage-Flow | 20 | RL 对齐，GenEval 0.90 开源最佳 |
| `mage-flow-turbo` | microsoft/Mage-Flow-Turbo | 4 | 蒸馏，A100 约 0.59s/张 |

编辑模型在 ID 后加 `-edit*` 前缀段：`mage-flow-edit-base` / `mage-flow-edit` / `mage-flow-edit-turbo`
（Java 侧设置参考图后自动切换 `/v1/images/edits` 接口）。

### 理解（ChatClient.model）

| ID | 权重 | 能力 |
|---|---|---|
| `mage-vl` | microsoft/Mage-VL | 图像/视频问答、时间定位、事件门控流式评论 |

## Java 调用示例

依赖：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-mage-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

文生图：

```java
BufferedImage image = ImageClient.create("mage", "sk-my-secret")
        .baseUrl("http://gpu-host:7861")
        .model("mage-flow-turbo")          // 4 步蒸馏；cfg 已按模型自动处理
        .size(1024, 1024)
        .seed(42L)
        .generate("一只柴犬在樱花树下，电影感光线");
```

指令编辑（带参考图即走编辑通道）：

```java
BufferedImage edited = ImageClient.create("mage", "sk-my-secret")
        .baseUrl("http://gpu-host:7861")
        .model("mage-flow-edit-turbo")
        .referenceImage(Files.readAllBytes(Path.of("dog.jpg")))
        .generate("把背景换成一片向日葵田");
```

图文理解：

```java
String answer = ChatClient.create("mage", "sk-my-secret")
        .baseUrl("http://gpu-host:7861")
        .model("mage-vl")
        .addImage("http://example.com/scene.jpg")   // 也支持本地路径与 Base64 data URI
        .chatSync("描述这段画面里发生了什么");
```

视频理解（抽帧后端）：

```java
String answer = ChatClient.create("mage", "sk-my-secret")
        .baseUrl("http://gpu-host:7861")
        .model("mage-vl")
        .addAttachmentUrl("broadcast", "file:///data/soccer.mp4", "video/mp4")
        .chatSync("这段视频的关键事件是什么？");
```

多模态字节附件：

```java
client.addAttachment("frame.png", pngBytes, "image/png");
```

## 设计说明

- **为何不是 onnx-starter / pytorch-starter？** DJL 的 ONNX Runtime 引擎只吃 ONNX 文件、
  PyTorch 引擎只吃 TorchScript 追踪模型；Mage 的 Codec-ViT（需编解码器运动向量输入）、
  认知门控流式逻辑、NR-MMDiT（原生分辨率打包 + rectified flow）均含自定义 Python 运算，
  官方未提供任何静态化导出。
- **models-parent 为何没有 mage 包？** 模型 jar 内嵌 ONNX 权重资源，最大现有包约 300MB；
  Mage 单权重 8GB 且非 ONNX 格式，不适合也不必要打包。
- 超时：客户端读超时 5 分钟（首次调用含权重下载/加载）；尺寸自动对齐 16 的倍数并约束在 512–2048。

## License

Java 客户端遵循仓库根 LICENSE。Mage 模型权重许可：
Mage-VL Apache-2.0 / Mage-ViT MIT / Mage-Flow MIT，仅供研究用途（官方 Responsible AI 声明）。
