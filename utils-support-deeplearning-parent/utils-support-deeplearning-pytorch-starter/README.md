# utils-support-deeplearning-pytorch-starter

PyTorch / DJL 推理引擎模块：图像分类、目标检测、特征提取等

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-pytorch-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `BigGAN128Translator` | BigGAN 128x128 生成 Translator。 |
| `BigGAN256Translator` | BigGAN 256x256 生成 Translator。 |
| `BigGAN512Translator` | BigGAN 512x512 生成 Translator。 |
| `BigGANTranslator` | BigGAN 图像生成 Translator。 输入类别 ID（Long），输出生成图像。 |
| `PytorchImageNetClassificationTranslator` | PyTorch ImageNet 分类 Translator。 |
| `ColorizationTranslator` | 图像上色 Translator（灰度 → 彩色）。 |
| `HedScribbleTranslator` | HED 线稿/涂鸦预处理 Translator。 输出边缘强度可视化图，用于 ControlNet 条件输入。 |
| `DptDepthTranslator` | DPT 深度估计 Translator。 |
| `MidasDepthTranslator` | MiDaS 深度估计 Translator。 输入 RGB 图，输出可视化深度图。 |
| `PytorchYoloTranslator` | PyTorch YOLO 目标检测 Translator。 |
| ... | 共 46 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-pytorch-starter
├── utils-support-common-starter
├── utils-support-deeplearning-starter
```