# utils-support-deeplearning-onnx-starter

ONNX 推理引擎模块：YOLO 检测、图像分类等模型推理

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-onnx-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 启用 GPU 加速（可选）


```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AbstractOnnxTranslator` | AbstractOnnxTranslator |
| `CommonActionTranslator` | Translator KINETICS400 400 1. 224x224 2. ImageNet 3. CHW (SPI: `common_action`) |
| `GoogleNetAgeRecognitionTranslator` | GoogleNet Translator https://github.com/onnx/models/tree/main/validated/vision/b |
| `VggAgeRecognitionTranslator` | VGG-16 Translator https://github.com/onnx/models/tree/main/validated/vision/body |
| `AgeRaceGenderTranslator` | AgeRaceGenderNet AgeRaceGenderNet - 0-116 117 - - - 256x256 - [0, 1] - ImageNet  |
| `AnimeFaceDetectorTranslator` | Anime Face YOLOv8 ONNX anime face detection YOLOv8n bbox + confidence -> "anime_ |
| `AnimeGanV3Translator` | AnimeGANv3 AnimeGANv3 1. 2. 3. @version 4.0.0.30 |
| `NimaTranslator` | NIMA (Neural Image Assessment) Translator VGG16 10 1-10 Softmax , Mean Opinion S |
| `AnimeRealClsTranslator` | DeepGHS anime/real translator |
| `ClTaggerTranslator` | CL Tagger CL Tagger - - Resize + Normalize - Top-K 1. 448x448 2. [0, 1] 3. CHW 4 |
| ... | 共 122 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

---

## 依赖关系

```
utils-support-deeplearning-onnx-starter
├── utils-support-common-starter
├── utils-support-deeplearning-starter
│   └── onnxruntime (1.21.0)
```