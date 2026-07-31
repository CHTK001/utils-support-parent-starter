# utils-support-deeplearning-opencv-starter

纯 OpenCV 推理引擎模块：人脸检测、图像分类等，不依赖 DJL/ONNX Runtime

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-opencv-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `OpencvCascadeDetector` | 通用 OpenCV Haar/LBP 级联检测翻译器。 |
| `OpencvFaceDetector` | 纯 OpenCV 人脸检测翻译器。 |
| `OpencvModelProvider` | 纯 OpenCV 批量模型提供者。 一次注册人脸检测、眼睛、微笑、侧脸、人体、HOG 行人、图像质量、人脸质量等模型。 |
| `OpencvModelTranslator` | 纯 OpenCV 翻译器抽象基类。 |
| `OpencvNative` | OpenCV 原生库加载工具。 使用 openpnp OpenCV 包内置的 加载本地动态库， 保证 Haar/LBP 级联与图像编解码在 JVM 中可用。 |
| `OpencvHogPedestrianDetector` | 纯 OpenCV HOG 行人检测翻译器。 使用 OpenCV 内置的默认行人检测器，无需外部模型文件。 |
| `OpencvFaceQualityAssessor` | 纯 OpenCV 人脸质量评估翻译器。 结合 Haar 人脸检测与模糊/亮度/尺寸指标，判断人脸是否可用于后续识别。 |
| `OpencvImageQualityAssessor` | 纯 OpenCV 图像质量评估翻译器。 基于 Laplacian 方差评估清晰度，基于灰度均值/标准差评估亮度与对比度。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-opencv-starter
├── utils-support-common-starter
├── utils-support-deeplearning-starter
├── utils-support-opencv-cascades
```