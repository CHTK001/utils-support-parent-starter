# utils-support-deeplearning-tensorflow-starter

> utils 深度学习体系中的 **TensorFlow 引擎模块**：基于 DJL（Deep Java Library）的 TensorFlow Engine，把图像分类、目标检测、超分辨率三类图像模型以统一 SPI 接入 `ModelRegistry`，并以 SPI 名 `tensorflow` 对外暴露 `FeatureClient`。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`，全仓开启 preview）｜包名：`com.chua.deeplearning.support.tensorflow`｜DJL：`0.36.0`（根 pom `djl.version`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 核心 API 与内置模型](#3-核心-api-与内置模型)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-tensorflow-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎实现 | TensorFlow（DJL `tensorflow-engine` + `tensorflow-model-zoo`）的模型注册与 Translator 实现 | 不是通用 AI 网关，不含路由与多引擎调度 |
| 能力范围 | 图像分类（MobileNet）、目标检测（SavedModel SSD）、图像超分辨率 | 不含训练与微调，只有推理 |
| 接入方式 | 通过 `ModelRegistrar` SPI 向 `ModelRegistry` 批量注册模型；通过 `FeatureClient` SPI 以 `tensorflow` 名称暴露 | 不定义 SPI 契约本身（契约在 `utils-support-deeplearning-starter` 与 `utils-support-common-starter`） |
| 配置 | 零自有配置，模型路径解析复用上游 `ModelRegistry` | 不管理模型下载清单（本模块注册的模型均未配置 `downloadUrl`） |

### 1.3 规模

- Java 源文件 **5** 个：1 个模型注册器、1 个 `FeatureClient` 实现、1 个超分便捷门面、3 个 Translator。
- SPI 扩展声明文件 **2** 个（`META-INF/extensions/`）。
- GraalVM `native-image` 配置 **2** 个（`reflect-config.json`、`resource-config.json`）。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 图像分类 | `ImageClassifier.create("tf-mobilenet")` | `classify(byte[])` 返回最可能类别名；`classifyTopK(byte[], k)` 返回 Top-K 列表 |
| 目标检测 | `ImageDetector.create("tf-object-detection")` | `detect(byte[])` 返回 `List<DetectionInfo>`（类别、置信度、边界框 x/y/w/h） |
| 图像超分辨率 | `new SuperImageSuperResolution().superResolution(byte[])` | 本模块便捷门面；等价于 `ImageEnhancer.create("tf-super-resolution").enhance(byte[])` |
| 指定模型路径 / 运行设备 | `.modelPath("...")` / `.device("cpu")` | 三个能力接口均支持链式设置（`device` 传设备名，如 cpu、gpu） |
| 模型清单 | `ImageClassifier.listModels()` 等能力静态方法 | 也可用 `TensorflowFeatureClient.models()` 按 `tensorflow` 引擎枚举 |
| 图像理解客户端 | `FeatureClient.create("tensorflow", "")` | SPI 名 `tensorflow`；向量提取限制见 [边界](#8-边界这些能力不在本模块) |
| 引擎接入（新增模型） | 实现 `ModelRegistrar`，向 `ModelRegistry` 注册 | 参见 [SPI 扩展点](#6-spi-扩展点) |
| 复用 Translator | `MobilenetClassificationTranslator` 等 3 个类 | 公有构造器，可直接实例化用于自定义 DJL `Criteria` 推理 |

---

## 3. 核心 API 与内置模型

### 3.1 内置模型（由 `TensorflowModelRegistrar` 注册）

| modelId | Translator | 输入 → 输出 | 能力接口 | 模型相对路径（相对模型根目录） |
| --- | --- | --- | --- | --- |
| `tf-mobilenet` | `MobilenetClassificationTranslator` | `ai.djl.modality.cv.Image` → `ai.djl.modality.Classifications` | `ImageClassifier` | `tensorflow/classification/mobilenet` |
| `tf-object-detection` | `SavedModelObjectDetectionTranslator` | `ai.djl.modality.cv.Image` → `ai.djl.modality.cv.output.DetectedObjects` | `ImageDetector` | `tensorflow/detection/ssd` |
| `tf-super-resolution` | `SuperResolutionTranslator` | `ai.djl.modality.cv.Image` → `ai.djl.modality.cv.Image` | `ImageEnhancer` | `tensorflow/resolution/esr` |

- 注册时统一补 `tensorflow/` 前缀，且幂等（`ModelRegistry.get(modelId) == null` 才写入）。
- 模型形态支持「SavedModel 目录」或 `.pb` 文件（`TensorflowModelRegistrar` 类注释：支持保存模型目录或 .pb）。
- Translator 类在首次推理时才懒加载（`ModelRegistry` 的设计），注册期不加载引擎。

### 3.2 类一览

| 类 | 说明 |
| --- | --- |
| `TensorflowModelRegistrar` | `ModelRegistrar` SPI 实现；`register(ModelRegistry)` 与静态初始化块都会执行 `registerAll()`，把上表 3 个模型写入注册表 |
| `TensorflowFeatureClient` | `@Spi("tensorflow")` 的 `FeatureClient` 实现，继承 `AbstractLocalFeatureClient`；`models()` 返回 `DeeplearningModels.models("tensorflow")` |
| `SuperImageSuperResolution` | 超分便捷门面：构造即绑定模型 `tf-super-resolution`；`modelPath(String)`、`device(String)` 链式设置；`superResolution(byte[])` 输入图像字节、返回高分辨率图像字节 |
| `MobilenetClassificationTranslator` | `Translator<Image, Classifications>`：输入 resize 到 224×224 并归一化到 `[-1,1]`（`div(127.5).sub(1.0)`）；`prepare` 阶段从模型目录依次尝试 `synset.txt`、`labels.txt`、`label_list.txt`、`imagenet_classes.txt` 读取类别，全部缺失时按输出维度合成 `class_0..n`；批处理用 `Batchifier.STACK` |
| `SavedModelObjectDetectionTranslator` | `NoBatchifyTranslator<Image, DetectedObjects>`：输入 resize 到 640×640、转 UINT8、`expandDims(0)` 成 `[1,H,W,C]`；输出按名解析 `detection_boxes` / `detection_scores` / `detection_classes`（无名输出按常见顺序兜底）；TF 框格式 `[ymin, xmin, ymax, xmax]` 归一化坐标，转 `Rectangle`；默认参数：最多 10 框、置信度阈值 0.5、输入边长 640、内置 COCO 80 类映射（含 background） |
| `SuperResolutionTranslator` | `Translator<Image, Image>`：输入 FLOAT32 HWC；输出 `clip(0,255)` 后转 UINT8，若为 4 维且第 0 维为 1 则 `squeeze(0)`，经 `ImageFactory.fromNDArray` 还原为 `Image`；批处理用 `Batchifier.STACK` |

### 3.3 能力接口方法（契约在 `utils-support-deeplearning-starter`）

| 接口 | 链式设置 | 推理方法 |
| --- | --- | --- |
| `ImageClassifier` | `threshold(float)`、`topK(int)`、`modelPath(String)`、`device(String)` | `classify(byte[])` → `String`；`classifyTopK(byte[], int)` → `List<DetectionInfo>` |
| `ImageDetector` | `threshold(float)`、`nms(float)`、`modelPath(String)`、`device(String)` | `detect(byte[])` → `List<DetectionInfo>` |
| `ImageEnhancer` | `modelPath(String)`、`device(String)` | `enhance(byte[])` → `byte[]` |

三个接口均有静态方法：`create(name)`、`create(name, ModelSetting)`、`listModels()`。`device` 传设备名称（如 cpu、gpu）。

---

## 4. 快速开始

### 4.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-tensorflow-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

会连带引入 `utils-support-deeplearning-starter`（注册表与能力接口）、`utils-support-common-starter`（SPI 基础设施）与 DJL TensorFlow 引擎。

### 4.2 放置模型文件

按注册的相对路径放到模型根目录（默认 `models/onnx`，可用系统属性 `deeplearning.model.root-dir` 修改）：

```text
<模型根目录>/tensorflow/classification/mobilenet/    # SavedModel 目录，或同名 .pb
<模型根目录>/tensorflow/detection/ssd/
<模型根目录>/tensorflow/resolution/esr/
```

也可打进 classpath 的 `models/tensorflow/...`，`ModelRegistry` 解析到 JAR 内嵌资源时会自动解压到临时缓存目录。

### 4.3 最短可运行示例

```java
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.tensorflow.SuperImageSuperResolution;

byte[] imageBytes = /* 读取图像文件 */;

// 1) 图像分类（MobileNet）：返回最可能的类别名
String label = ImageClassifier.create("tf-mobilenet")
        .device("cpu")
        .classify(imageBytes);

// 2) 目标检测（TF SavedModel SSD）：返回边界框 + 类别 + 置信度
var detections = ImageDetector.create("tf-object-detection")
        .threshold(0.5f)
        .device("cpu")
        .detect(imageBytes);

// 3) 超分辨率：输入低清图字节，返回高清图字节
byte[] hr = new SuperImageSuperResolution()
        .device("cpu")
        .superResolution(imageBytes);
```

未显式 `modelPath(...)` 时，模型路径按 `ModelRegistry.resolveModelPath` 的顺序解析：注册的相对路径（绝对路径 → 模型根目录 → 当前工作目录 → classpath）→ 模型根目录递归扫描 → 远程下载（本模块模型未配置下载地址，故不会联网）。

---

## 5. 配置项

**本模块无自有配置项。** 依据：`src/main/resources` 下只有 SPI 扩展声明与 `native-image` 描述符，源码中无配置类、无 `@ConfigurationProperties`、无 properties/yaml 文件。

推理行为依赖的上游配置（定义在 `utils-support-deeplearning-starter` 的 `ModelRegistry`，对本模块同样生效）：

| 系统属性 | 默认值 | 说明 |
| --- | --- | --- |
| `deeplearning.model.root-dir` | `models/onnx`（相对当前工作目录） | 模型根目录；历史命名，各引擎共用 |
| `deeplearning.model.cache-dir` | `${java.io.tmpdir}/chua-dl-models` | classpath 模型解压与下载缓存目录 |

通过 `FeatureClient.create("tensorflow", setting)` 使用 `TensorflowFeatureClient` 时，`FeatureClientSetting`（定义在 `utils-support-common-starter`）可设置：`provider`、`appKey`（本地模型可留空）、`baseUrl`、`model`（模型名，`auto`/空表示按硬件自动挑选推荐模型）、`dimensions`（输出向量维度）。

---

## 6. SPI 扩展点

### 6.1 本模块注册的两类 SPI

`src/main/resources/META-INF/extensions/` 下：

| SPI 接口 | 注册内容 | 形式 |
| --- | --- | --- |
| `com.chua.deeplearning.support.engine.ModelRegistrar` | `com.chua.deeplearning.support.tensorflow.TensorflowModelRegistrar` | 单实现类名（主框架启动时扫描加载，触发批量注册） |
| `com.chua.common.support.ai.feature.FeatureClient` | `tensorflow=com.chua.deeplearning.support.tensorflow.TensorflowFeatureClient` | `名称=实现类`（`@Spi("tensorflow")`） |

### 6.2 GraalVM native-image

`META-INF/native-image/com.chua/utils-support-deeplearning-tensorflow-starter/`：

- `reflect-config.json`：对 `TensorflowFeatureClient`、`TensorflowModelRegistrar` 开放全部构造器/方法/字段反射。
- `resource-config.json`：打包 `META-INF/extensions/.*` 资源。

### 6.3 如何向本引擎新增模型

1. 写一个 `ai.djl.translate.Translator`（参考本模块 3 个 Translator 的输入预处理/输出后处理写法）；
2. 在 `TensorflowModelRegistrar.registerAll()` 中追加一条 `reg(modelId, translatorClassName, inputType, outputType, capabilityInterface, relativePath)`，相对路径会自动补 `tensorflow/` 前缀；
3. 无需新增 SPI 文件——注册器由 `ModelRegistrar` SPI 统一加载。

> 也可以完全不经过本模块：用 `ModelRegistry.register(...)` 在运行期注册，或用 DJL `Criteria` 直接加载模型并注入自定义 Translator。

---

## 7. 构建与依赖

模块位于 `utils-support-deeplearning-parent` 聚合下（`utils-support-parent-starter/utils-support-deeplearning-parent/`）。

```text
utils-support-deeplearning-tensorflow-starter
├── com.chua:utils-support-common-starter              # SPI / 契约层
├── com.chua:utils-support-deeplearning-starter        # ModelRegistry、能力接口、引擎（同版本）
├── ai.djl.tensorflow:tensorflow-engine:0.36.0         # DJL TensorFlow 引擎
├── ai.djl.tensorflow:tensorflow-model-zoo:0.36.0      # DJL TensorFlow 模型库
└── org.projectlombok:lombok (provided)
```

编译（在 `utils-support-parent-starter` 仓根或本模块目录）：

```bash
mvn -o -q compile -DskipTests
```

> TensorFlow 原生库由 DJL `tensorflow-engine` 按平台拉取；CPU 平台开箱即用，GPU 需按 DJL 文档配置对应 CUDA 版本依赖。

---

## 8. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `ModelRegistry` / `ModelRegistrar` / `IdentificationEngine`（模型注册表与引擎） | `utils-support-deeplearning-starter` |
| `ImageClassifier` / `ImageDetector` / `ImageEnhancer` / `DetectionInfo`（能力接口与结果模型） | `utils-support-deeplearning-starter` |
| `FeatureClient` / `FeatureClientSetting` / `ModelDefinition`（AI 客户端契约） | `utils-support-common-starter` |
| 其他推理引擎（ONNX、PyTorch、Paddle、sherpa-onnx 等） | 各自独立的 `utils-support-deeplearning-*-starter` |
| 模型训练 / 微调 / 导出 | 无（本模块只做推理） |
| 模型文件本体 | 不随本模块 JAR 分发；按第 4.2 节路径自备 |

**已知限制（源自码行为）**：`TensorflowFeatureClient` 继承的 `extract(String)` / `extractImage(byte[])` 要求模型输出为 `float[]`/`double[]` 向量；本引擎当前注册的 3 个模型输出分别是 `Classifications`、`DetectedObjects`、`Image`，因此经 `FeatureClient` 调用向量提取会抛出 `IllegalStateException`（“模型输出不是向量”）。本模块的图像能力请优先走 `ImageClassifier` / `ImageDetector` / `ImageEnhancer` 三个能力接口。
