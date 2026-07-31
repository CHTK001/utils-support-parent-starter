# utils-support-deeplearning-tensorflow-starter

TensorFlow / DJL 推理引擎模块：目标检测、超分辨率、分类

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-tensorflow-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MobilenetClassificationTranslator` | TensorFlow MobileNet / ImageNet 分类 Translator。 |
| `SavedModelObjectDetectionTranslator` | TensorFlow Object Detection SavedModel Translator。 |
| `SuperResolutionTranslator` | TensorFlow 超分辨率 Translator。 输入 FLOAT32 HWC；输出 clip 到 [0,255] 后还原 Image。 |
| `SuperImageSuperResolution` | TensorFlow 超分辨率。 基于 ，使用 tf-super-resolution 模型进行图像超分辨率重建。 |
| `TensorflowModelRegistrar` | TensorFlow 模块模型集中注册器。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-tensorflow-starter
├── utils-support-common-starter
├── utils-support-deeplearning-starter
```