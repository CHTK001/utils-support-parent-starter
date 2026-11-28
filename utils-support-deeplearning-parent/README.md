# utils-support-deeplearning-parent

推理引擎、远程推理客户端、Agent 与训练适配的集合。模型注册/下载和通用能力接口见 [核心 deeplearning](../utils-support-core-parent/utils-support-deeplearning-starter/README.md)。版本 `4.0.0.51`、JDK 25；父 POM 是构建聚合器，应用只引入所需引擎。

## 模块目录

| 模块 README | 主要能力 |
|---|---|
| [onnx](utils-support-deeplearning-onnx-starter/README.md) | 检测、OCR、人脸、分割、语音、嵌入、图像生成/修复与文本模型 |
| [sherpa](utils-support-deeplearning-sherpa-starter/README.md) | sherpa-onnx JNI 离线/流式 ASR、TTS、VAD |
| [pytorch](utils-support-deeplearning-pytorch-starter/README.md) | DJL TorchScript 注册、图像/文本/特征推理 |
| [paddle](utils-support-deeplearning-paddle-starter/README.md) | Paddle OCR、人脸、分类、检测和 NLP |
| [tensorflow](utils-support-deeplearning-tensorflow-starter/README.md) | TensorFlow/DJL 检测、分类与超分辨率 |
| [mxnet](utils-support-deeplearning-mxnet-starter/README.md) | MXNet 图像分类和特征提取 |
| [opencv](utils-support-deeplearning-opencv-starter/README.md) | OpenCV 人脸/行人检测、清晰度和质量评估 |
| [agentscope](utils-support-deeplearning-agentscope-starter/README.md) | AgentScope Harness、工具/MCP/技能与上下文管理 |
| [langchain4j](utils-support-deeplearning-langchain4j-starter/README.md) | LangChain4j ReAct Agent SPI |
| [safetensors](utils-support-deeplearning-safetensors-starter/README.md) | 独立 Python HTTP 推理服务与 Java 客户端 |
| [3d](utils-support-deeplearning-3d-starter/README.md) | 文本/图片/草图生成 3D、风格化及 Spring 装配 |
| [ollama](utils-support-deeplearning-ollama-starter/README.md) | Ollama 对话、嵌入及模型/服务管理 |
| [laya](utils-support-deeplearning-laya-starter/README.md) | 本地 NOUL/CHOICE/SCORE 决策及规则构造 |
| [dl4j](utils-support-deeplearning-dl4j-starter/README.md) | ResNet50 迁移训练、分类和特征比较 |
| [weka](utils-support-deeplearning-weka-starter/README.md) | 随机森林分类/回归及评估 |
| [jitllm](utils-support-deeplearning-jitllm-starter/README.md) | GGUF 对话/嵌入、TornadoVM 与 llama.cpp/llama-server 路径 |
| [wevisone](utils-support-deeplearning-wevisone-starter/README.md) | WeVisOne-VL HTTP 指令式视觉推理 |

## 安装与运行条件

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-onnx-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

引擎 jar 与模型权重分开准备。模型 ID、输入格式、资源下载/缓存、CPU/GPU 和原生库要求见具体 README；远程客户端须先有对应推理服务。注册表能列出模型不等于模型已下载、可加载或已通过推理。

AgentScope/LangChain4j 消费统一 ChatClient，不自动提供模型凭据。safetensors、Mage、WeVisOne 的 Java 客户端也不会把 Python 服务自动部署到宿主。

仓库根构建：`mvn -pl utils-support-deeplearning-parent -am compile -DskipTests`。表内明确未登记的三个模块不在默认 reactor 内。返回 [完整目录](../README.md)。
