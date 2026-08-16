# utils-support-deeplearning-starter

深度学习核心：模型管理引擎、AI能力接口、OpenCV实现、翻译器模式

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AbstractIdentificationEngine` | 抽象识别引擎，SPI 自动发现子模块翻译器，集中托管模型 |
| `ModelRegistry` | 模型注册表（多镜像下载、classpath/jar 模型解析、SPI 发现） |
| `ITranslator<I, O>` | 翻译器接口，隔离原始张量/字节流与业务对象 |
| `OcrRecognizer` / `OcrResult` | OCR 文字识别业务接口 |
| `FaceDetector` / `FacePipeline` | 人脸检测 / 人脸统一能力门面 |
| `ImageClassifier` / `ImageDetector` | 图像分类 / 检测业务接口 |
| `FeatureExtractor` | 特征提取业务接口 |
| `ImageEnhancer` | 图像增强（超分/修复/风格）业务接口 |
| `PoseEstimator` | 姿态估计业务接口 |
| `EmbeddingService` | 文本嵌入业务接口 |
| `MattingService` | 图像抠图业务接口 |
| `ImageCaptioning` | 图像描述业务接口 |
| ... | 共 57 个类 |

### 核心机制

- **IdentificationEngine**：单例中枢，扫描子模块 `ITranslator` 与 `ModelDefinition` 并集中托管，
  业务调用方按名称取用翻译器，实现模型实现与业务调用解耦。
- **翻译器模式**：`ITranslator<I, O>` 隔离底层模型与业务对象。
- **业务能力门面**：接口 + 包级 `DefaultXxx`，统一 `create(name)` 静态工厂。

```java
OcrRecognizer.create("paddleocrv6-medium-rec").recognize(imageBytes);
FaceDetector.create("yolov8-face").detect(imageBytes);
EmbeddingService.create("bge-zh").embed("文本");
```

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-starter
├── utils-support-common-starter
├── opencv (4.9.0-0)
└── onnxruntime-engine (DJL 0.36.0)
```

> 详细架构见 [架构设计.md](./架构设计.md)
