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
| `DetectionConfiguration` | 检测/推理配置。 定义模型加载、设备选择、以及云端认证等通用参数。 所有模型引擎（onnx-starter、pytorch-starter 等）共享同一份配置。 |
| `Detector` | 检测器接口。 输入任意对象，输出检测结果对象。 |
| `Extractor` | 特征提取器接口。 输入任意对象，输出预测结果对象。 |
| `HumanPredictResult` | 人像分析预测结果。 适用于年龄、性别、种族等多属性分析场景。 |
| `PredictResult` | 通用预测结果。 适用于文本分类、情感分析、性别/年龄识别等场景。 支持 String 值和 float[] 向量两种结果形态。 |
| `PredictResultObject` | 预测结果封装。 持有任意类型结果与原始输入引用，用于统一返回格式。 |
| `ModelSetting` | ModelSetting |
| `EmbeddingService` | EmbeddingService |
| `AbstractIdentificationEngine` | 抽象识别引擎。 通过 SPI 自动发现 实现，构建模型注册表， 支持按名称和类型查找模型实例。 |
| `BulkModelProvider` | 批量模型提供者接口。 用于一次注册多个模型的场景， 会优先调用 注册所有模型。 |
| ... | 共 57 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-starter
├── utils-support-common-starter
```