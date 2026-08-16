# utils-support-deeplearning-onnx-starter

ONNX 推理引擎模块：OCR 文字识别、YOLO 检测、图像分类、特征提取等模型推理。

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

### 2. 引入模型资源 jar（按需）

模型资源以独立 jar 发布，按需添加，如：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-models-onnx-paddleocrv6-medium</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-models-onnx-textbsr</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

### OCR 文字识别（OcrPipeline）

完整管线：**整图矫正 → 检测 → 裁剪 → 裁剪块矫正 → 修复可选 → 识别**

```java
OcrPipeline pipeline = OcrPipeline.builder()
        .detector("paddleocrv6-medium-det")
        .recognizer("paddleocrv6-medium-rec")
        .direction("pp-word-rotate")
        .enhancer("text-bsr")
        .minConfidence(0.6f)
        .build();

String text = pipeline.recognize(imageBytes);              // 识别整图文本
List<OcrResult> results = pipeline.recognizeDetail(imageBytes); // 详情（含位置）
byte[] corrected = pipeline.correct(imageBytes);           // 方向矫正
byte[] enhanced = pipeline.enhance(imageBytes);            // 文字高清化
```

- 检测：PP-OCRv6 tiny / medium（DB 算法，ORT 原生 + OpenCV）
- 识别：PP-OCRv6 tiny / medium（CTC 解码，dict 从 inference.yml 提取）
- 方向：PP-OCR 文本方向分类（0°/180°）
- 高清化：TextBSR 文字超分（默认 2x）
- 深背景自动反色、置信度过滤、结果图标注输出

### 其他能力

| 能力 | 示例模型 |
|------|---------|
| 图像分类 | `efficientnet-b1-classification`、`fer-plus` |
| 视觉特征 | `mobileclip-s0-vision`、`dino-v2-small-embedding` |
| 文本嵌入 | `minilm-embedding`、`bge-small-zh-embedding` |
| 抠图 | `modnet`（MattingService） |
| 姿态估计 | `yolov8n-pose` |
| 车牌检测 | `yolov5-plate-detect` |
| 卡片矫正 | `card-correction-detector` |
| 语音合成 | `mms-tts-eng` |
| 图像描述 | `vit-gpt2-captioning` |
| 人脸修复 | `codeformer` |

### 关键工具类

| 类 | 说明 |
|----|------|
| `OcrPipeline` | OCR 统一门面（检测+矫正+识别+修复） |
| `OpenCvImageUtils` | OpenCV 图像操作统一工具（decode/encode/upscale/rotate/invertIfDark/load） |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。GPU 加速需替换 `onnxruntime_gpu` + `Device.gpu()`。

---

## 依赖关系

```
utils-support-deeplearning-onnx-starter
├── utils-support-common-starter
├── utils-support-deeplearning-starter
│   ├── opencv (4.9.0-0)
│   └── onnxruntime-engine (DJL 0.36.0)
└── onnxruntime (1.21.1)
```
