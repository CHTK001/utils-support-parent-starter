# utils-support-deeplearning-paddle-starter

> PaddlePaddle（飞桨）推理引擎适配模块：基于 DJL `paddlepaddle-engine`，向深度学习模型注册表登记 **14 个飞桨模型**（OCR、人脸、检测、分类、NLP），并提供 `@Spi("paddle")` 的本地特征提取客户端。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.deeplearning.support.paddle`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 快速开始](#2-快速开始)
- [3. 已注册模型总表](#3-已注册模型总表)
- [4. 核心 API](#4-核心-api)
- [5. 内置 Translator 一览](#5-内置-translator-一览)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-paddle-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | PaddlePaddle / DJL 推理引擎适配模块（pom 描述：OCR、人脸、检测、分类、NLP） | 不做模型训练、微调，只做推理 |
| 注册 | 通过 `ModelRegistrar` SPI 向 `ModelRegistry` 批量注册 14 个飞桨模型与对应 Translator | 不定义能力接口（`ImageClassifier` / `ImageDetector` / `FeatureExtractor` / `TextTranslator` 均在 `utils-support-deeplearning-starter`） |
| 客户端 | 提供 `@Spi("paddle")` 的本地特征提取客户端 `PaddleFeatureClient`（继承 `AbstractLocalFeatureClient`） | 不内置任何模型文件：模型为 `推理.pdmodel + .pdiparams`，需按 `paddle/` 相对路径前缀放置于模型根目录 |
| 形态 | 纯库模块，靠 SPI 装配生效，所有类 `@since 4.0.0.42` | 不是 Spring Boot starter：无自动配置类、无 `application.yml`、无 `@ConfigurationProperties` |

### 1.3 规模

- Java 源文件 **18** 个，全部位于 `com.chua.deeplearning.support.paddle` 及其子包 `classification`、`detection`、`face`、`ocr`、`nlp`、`util`。
- SPI 扩展声明文件 **2** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json`（`PaddleFeatureClient`、`PaddleModelRegistrar`）与 `resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 图像分类 | `ImageClassifier.create("paddle-animal")` | 动物 / 菜品分类、OCR 方向分类 |
| 目标检测 | `ImageDetector.create("paddle-vehicle")` | 车辆 / 行人 / 交通目标 / 人脸 / OCR 文本框检测 |
| 图像特征 | `FeatureExtractor.create("paddle-face-feature")` | 人脸特征（112×112）、人脸关键点（60×60），输出 `float[]` |
| 文本特征 | `FeatureExtractor.create("paddle-senta")` | 情感分类 / 评论 / SimNet-BOW，输出 `float[]` |
| 词法分析 | `TextTranslator.create("paddle-lac")` | LAC 分词与词性标注 |
| OCR 文字识别 | `TextTranslator.create("paddle-ocr-rec")` | 识别词表 `ppocr_keys_v1.txt` |
| 特征提取客户端 | `FeatureClient.create("paddle", apiKey)` | SPI 名 `paddle`，统一输出 `float[]`，可 `close()` |
| 按能力查模型 | `ImageClassifier.listModels()` 等 | 从 `ModelRegistry` 按能力接口枚举已注册模型 |

---

## 2. 快速开始

依赖本模块（或任一引入本模块的 starter）后，模型注册器经 SPI 自动加载，无需手工初始化。

```java
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.util.List;

// 1) 图像分类：动物识别（分类能力）
ImageClassifier classifier = ImageClassifier.create("paddle-animal")
        .topK(5)              // Top-K 分类数（默认 5）
        .threshold(0.5f)      // 置信度阈值（可选）
        .device("cpu");       // 运行设备（默认 cpu）
String top1 = classifier.classify(imageBytes);            // 最可能的类别
List<DetectionInfo> top5 = classifier.classifyTopK(imageBytes, 5);

// 2) 目标检测：车辆检测（检测能力）
ImageDetector detector = ImageDetector.create("paddle-vehicle")
        .threshold(0.5f)
        .device("cpu");
List<DetectionInfo> boxes = detector.detect(imageBytes);  // 边界框 + 类别

// 3) 图像特征：人脸特征提取（特征能力，输出 float[]）
FeatureExtractor extractor = FeatureExtractor.create("paddle-face-feature");
float[] vector = extractor.extract(imageBytes);

// 4) 按能力枚举本引擎可用模型
List<String> detectModels = ImageDetector.listModels();   // 含 paddle-vehicle 等
```

经统一特征客户端使用（SPI 名 `paddle`）：

```java
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;

try (FeatureClient client = FeatureClient.create(FeatureClientSetting.builder()
        .provider("paddle")
        .model("paddle-face-feature")
        .build())) {
    float[] v = client.extractImage(imageBytes);   // 文本用 extract(String)
}
```

---

## 3. 已注册模型总表

注册入口：`PaddleModelRegistrar`（SPI `ModelRegistrar`）。模型 ID 全局唯一，重复注册会被跳过（`ModelRegistry.get(modelId) == null` 才注册）。相对路径统一加 `paddle/` 前缀，相对于模型根目录；模型文件一般为 `推理.pdmodel + .pdiparams`。

| 模型 ID | 能力接口 | 输入 → 输出 | Translator | 相对路径 |
| --- | --- | --- | --- | --- |
| `paddle-animal` | `ImageClassifier` | `Image` → `Classifications` | `AnimalTranslator` | `paddle/classification/animal` |
| `paddle-dishes` | `ImageClassifier` | `Image` → `Classifications` | `DishesTranslator` | `paddle/classification/dishes` |
| `paddle-vehicle` | `ImageDetector` | `Image` → `DetectedObjects` | `VehicleTranslator` | `paddle/detection/vehicle` |
| `paddle-pedestrian` | `ImageDetector` | `Image` → `DetectedObjects` | `PedestrianTranslator` | `paddle/detection/pedestrian` |
| `paddle-traffic` | `ImageDetector` | `Image` → `DetectedObjects` | `TrafficTranslator` | `paddle/detection/traffic` |
| `paddle-face-detect` | `ImageDetector` | `Image` → `DetectedObjects` | `PaddleFaceDetectorTranslator` | `paddle/face/detect` |
| `paddle-face-feature` | `FeatureExtractor` | `Image` → `float[]` | `PaddleFaceFeatureTranslator` | `paddle/face/feature` |
| `paddle-face-landmark` | `FeatureExtractor` | `Image` → `float[]` | `FaceLandmarkTranslator` | `paddle/face/landmark` |
| `paddle-ocr-det` | `ImageDetector` | `Image` → `DetectedObjects` | `PpWordDetectionTranslator` | `paddle/ocr/det` |
| `paddle-ocr-rec` | `TextTranslator` | `Image` → `String` | `PpWordRecognitionTranslator` | `paddle/ocr/rec` |
| `paddle-ocr-rotate` | `ImageClassifier` | `Image` → `Classifications` | `PpWordRotateTranslator` | `paddle/ocr/rotate` |
| `paddle-senta` | `FeatureExtractor` | `String[]` → `float[]` | `SentaTranslator` | `paddle/nlp/senta` |
| `paddle-review` | `FeatureExtractor` | `String[]` → `float[]` | `ReviewTranslator` | `paddle/nlp/review` |
| `paddle-lac` | `TextTranslator` | `String` → `String[][]` | `LacTranslator` | `paddle/nlp/lac` |
| `paddle-simnet` | `FeatureExtractor` | `String[][]` → `float[]` | `SimnetBowTranslator` | `paddle/nlp/simnet` |

> 能力接口来自 `com.chua.deeplearning.support` 包（`image` / `feature` / `nlp`），由 `utils-support-deeplearning-starter` 提供；本模块只负责「模型 + Translator」的登记。

---

## 4. 核心 API

### 4.1 模型注册：`PaddleModelRegistrar`

- 实现 `com.chua.deeplearning.support.engine.ModelRegistrar` SPI，被 `ModelRegistry` 加载。
- `register(ModelRegistry)` 与静态初始化块都会触发 `registerAll()`；单个模型注册是幂等的（已存在即跳过）。
- 私有 `reg(...)` 方法统一拼接 `paddle/` 路径前缀后调用 `ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, path)`；Translator 按**类名字符串注册、懒加载**。

### 4.2 能力入口（定义在 deeplearning-starter）

四个能力接口均提供 `create(String name)`、`create(String name, ModelSetting setting)`、`listModels()` 静态方法，内部经 `IdentificationEngine` / `ModelRegistry` 按模型名取 Translator 执行推理：

| 能力 | 关键方法 | 说明 |
| --- | --- | --- |
| `ImageClassifier` | `classify(byte[])`、`classifyTopK(byte[], k)` | 链式 `topK` / `threshold` / `modelPath` / `device` |
| `ImageDetector` | `detect(byte[])` → `List<DetectionInfo>` | 链式 `threshold` / `modelPath` / `device` |
| `FeatureExtractor` | `extract(byte[])`、`extract(String)` → `float[]` | 链式 `modelPath` / `device` |
| `TextTranslator` | `translate(String)` | 链式 `modelPath` / `device` |

模型未注册时抛 `IllegalStateException("模型未注册: " + modelName)`。

### 4.3 特征提取客户端：`PaddleFeatureClient`

- `@Spi("paddle")`，继承 `AbstractLocalFeatureClient`，构造入参为 `FeatureClientSetting`。
- `models()` 返回本引擎下输出类型为 `float[]` 的模型清单（`DeeplearningModels.models("paddle", null, float[].class)`），覆盖人脸特征、人脸关键点等「图像 → float[]」模型与 senta / review / simnet 等「文本 → float[]」模型。
- 作为 `FeatureClient`（`AutoCloseable`）使用时统一暴露 `extract(String)` / `extractImage(byte[])`。

### 4.4 内部工具：`BoundFinderV2`

`util` 包内的 OCR 检测掩码连通域框查找器：对二值化概率图做四邻域连通域标记，按最小框宽阈值反算归一化边界框，供 `PpWordDetectionTranslator` 后处理使用。

---

## 5. 内置 Translator 一览

所有 Translator 实现 DJL `ai.djl.translate.Translator`，按模型懒加载。

| Translator | 包 | 要点 |
| --- | --- | --- |
| `AnimalTranslator` | `classification` | prepare 阶段从模型目录加载类别清单，输出 `Classifications` |
| `DishesTranslator` | `classification` | 同上，菜品类别清单 |
| `VehicleTranslator` | `detection` | 车辆检测，标签清单从模型目录加载，输出 `DetectedObjects` |
| `PedestrianTranslator` | `detection` | 行人检测，记录原图宽高用于坐标还原 |
| `TrafficTranslator` | `detection` | 继承 `VehicleTranslator`，与车辆检测同预处理/后处理协议 |
| `PaddleFaceDetectorTranslator` | `face` | 人脸检测，含缩放比例（shrink）与置信度阈值 |
| `PaddleFaceFeatureTranslator` | `face` | 人脸特征，输入 112×112，输出 `float[]` |
| `FaceLandmarkTranslator` | `face` | 人脸关键点，输入 60×60，输出 `float[]` |
| `PpWordDetectionTranslator` | `ocr` | OCR 文本框检测：长边限缩 960、边长对齐 32、0.3 阈值，后处理用 `BoundFinderV2` |
| `PpWordRecognitionTranslator` | `ocr` | OCR 文字识别：词表 `ppocr_keys_v1.txt`，输入 48×320 |
| `PpWordRotateTranslator` | `ocr` | OCR 方向分类：`No Rotate` / `Rotate` 两类，输入 192×48 |
| `LacTranslator` | `nlp` | LAC 词法分析：词典 `word.dic` / `tag.dic` / `q2b.dic`，LoD 反射处理，BIO 解码输出 `String[][]` |
| `SentaTranslator` | `nlp` | 情感分析：词表 `vocab.txt`，`String[]` → `float[]` |
| `ReviewTranslator` | `nlp` | 评论情感，继承 `SentaTranslator` |
| `SimnetBowTranslator` | `nlp` | SimNet-BOW 句对特征，`String[][]` → `float[]` |

---

## 6. 配置项

**本模块自身无配置项**：源码中没有任何配置类、`application.yml` 或 `@ConfigurationProperties`，也不读取自有配置键。运行期参数全部由调用方显式传入：

| 途径 | 内容 | 说明 |
| --- | --- | --- |
| `ModelSetting`（deeplearning-starter） | `modelPath`、`device` 等 | 经 `ImageClassifier.create(name, setting)` 等传入；也可链式 `.modelPath(...)` / `.device(...)` |
| `FeatureClientSetting`（common-starter） | `provider`、`baseUrl`、`appKey`、`appSecret`、`model`、`dimensions`、HTTP 代理等 | 构造 `PaddleFeatureClient` 时传入 |
| 系统属性 `deeplearning.model.root-dir` | 模型根目录 | 定义于 `ModelRegistry`（全引擎共用），默认 `models/onnx`（相对当前工作目录）；可用 `ModelRegistry.setModelRootDir(String)` 修改 |
| 系统属性 `deeplearning.model.cache-dir` | 模型下载缓存目录 | 默认 `%TEMP%/chua-dl-models` |

> 模型文件按「根目录 + `paddle/<相对路径>`」解析；类路径内置模型按 `models/paddle/` 前缀解析（`ModelRegistry` 的 `CLASSPATH_MODEL_PADDLE` 常量）。

---

## 7. SPI 扩展点

`src/main/resources/META-INF/extensions/`：

```text
com.chua.deeplearning.support.engine.ModelRegistrar
  └── com.chua.deeplearning.support.paddle.PaddleModelRegistrar

com.chua.common.support.ai.feature.FeatureClient
  └── paddle=com.chua.deeplearning.support.paddle.PaddleFeatureClient
```

| 扩展点 | 本模块的注册 | 何时扩展 |
| --- | --- | --- |
| `ModelRegistrar` | `PaddleModelRegistrar`（批量登记 14 个模型） | 新增飞桨模型：写一个 DJL `Translator`，在 `registerAll()` 中 `reg(...)`；或在自己的模块实现 `ModelRegistrar` 向 `ModelRegistry` 注册 |
| `FeatureClient` | `PaddleFeatureClient`（SPI 名 `paddle`） | 接入新的本地特征引擎：继承 `AbstractLocalFeatureClient`，`@Spi("名字")` 标注并在 `META-INF/extensions/com.chua.common.support.ai.feature.FeatureClient` 登记 `<名字>=<全限定类名>` |

---

## 8. 构建与依赖

父模块：`com.chua:utils-support-deeplearning-parent:4.0.0.51`。

```xml
<dependencies>
    <dependency>
        <groupId>com.chua</groupId>
        <artifactId>utils-support-common-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.chua</groupId>
        <artifactId>utils-support-deeplearning-starter</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>ai.djl.paddlepaddle</groupId>
        <artifactId>paddlepaddle-engine</artifactId>
        <version>0.27.0</version>
    </dependency>
    <dependency>
        <groupId>ai.djl.paddlepaddle</groupId>
        <artifactId>paddlepaddle-model-zoo</artifactId>
        <version>0.27.0</version>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

```bash
# 编译（JDK 25，release=25，开启预览特性）
mvn -o -q compile -DskipTests
```

---

## 9. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `ImageClassifier` / `ImageDetector` / `FeatureExtractor` / `TextTranslator` 能力接口与默认实现 | `utils-support-deeplearning-starter`（`com.chua.deeplearning.support.image` / `feature` / `nlp`） |
| `ModelRegistry` / `ModelRegistrar` SPI / `IdentificationEngine` / `ModelSetting` | `utils-support-deeplearning-starter`（`com.chua.deeplearning.support.engine`、`config`） |
| `FeatureClient` / `FeatureClientSetting` / `@Spi` / `ServiceProvider` | `utils-support-common-starter` |
| ONNX、PyTorch、TensorFlow、Safetensors 等其它本地引擎 | 各自对应的 `utils-support-deeplearning-*-starter` |
| 模型训练、微调、Python 依赖 | 均不提供；本模块只做 Java 侧推理 |
| 模型文件本体 | 不随 jar 发布；需按 `paddle/` 相对路径放置 `推理.pdmodel + .pdiparams`，或以 `models/paddle/` 前缀内置于类路径 |
