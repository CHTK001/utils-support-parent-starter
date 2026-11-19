# utils-support-deeplearning-mxnet-starter

> MXNet / DJL 推理引擎适配模块：向深度学习引擎的模型注册表登记 MXNet 图像分类模型（InceptionV3 / VGG16），并提供 `provider=mxnet` 的 `FeatureClient` SPI 实现。
>
> 版本：`4.0.0.51`（继承父模块 `utils-support-deeplearning-parent`）｜JDK：`25`（仓库根 POM `maven.compiler.source/target=25`，编译启用 `--enable-preview`）｜包名：`com.chua.deeplearning.support.mxnet`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-mxnet-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎适配 | 把 MXNet（经 DJL 的 `ai.djl.mxnet:mxnet-engine` / `mxnet-model-zoo`）接入统一模型注册表 `ModelRegistry` | 不实现训练、数据集、微调等训练侧能力 |
| 模型登记 | 注册 2 个图像分类模型：`mxnet-inceptionv3`、`mxnet-vgg16`（能力接口 `ImageClassifier`） | 不包含模型权重文件本身，也不配置这两个模型的远程下载地址 |
| 预处理/后处理 | 提供 2 个 DJL `Translator`：`InceptionV3ActionTranslator`（299×299）、`Vgg16ActionTranslator`（224×224） | 不实现具体 CV 算法；推理执行由 `utils-support-deeplearning-starter` 的引擎体系完成 |
| SPI 实现 | `ModelRegistrar`（模型集中注册）与 `FeatureClient`（`@Spi("mxnet")`）的实现方 | 不是 Spring Boot starter：无自动配置类、无 `spring.factories`，引入即依赖，无需额外装配 |

模块源码共 4 个 Java 类：

| 类 | 职责 |
| --- | --- |
| `com.chua.deeplearning.support.mxnet.MxnetModelRegistrar` | 实现 `com.chua.deeplearning.support.engine.ModelRegistrar`，向 `ModelRegistry` 集中注册本引擎模型 |
| `com.chua.deeplearning.support.mxnet.MxnetFeatureClient` | `@Spi("mxnet")`，继承 `com.chua.deeplearning.support.ai.client.AbstractLocalFeatureClient`，作为 `FeatureClient` 的 mxnet 提供者 |
| `com.chua.deeplearning.support.mxnet.translator.InceptionV3ActionTranslator` | InceptionV3 图像分类 Translator，输入图像缩放至 299×299 并归一化，输出 ImageNet 类别概率分布 |
| `com.chua.deeplearning.support.mxnet.translator.Vgg16ActionTranslator` | VGG16 图像分类 Translator，输入图像缩放至 224×224 并归一化，输出 ImageNet 类别概率分布 |

### 1.3 注册的模型清单

由 `MxnetModelRegistrar` 在 SPI 加载（或静态块触发）时写入 `ModelRegistry`，已存在同 id 模型时跳过（幂等）：

| 模型标识 | Translator | 输入 → 输出 | 能力接口 | 模型相对路径 |
| --- | --- | --- | --- | --- |
| `mxnet-inceptionv3` | `InceptionV3ActionTranslator` | `ai.djl.modality.cv.Image` → `ai.djl.modality.Classifications` | `com.chua.deeplearning.support.image.ImageClassifier` | `mxnet/classification/inceptionv3` |
| `mxnet-vgg16` | `Vgg16ActionTranslator` | `ai.djl.modality.cv.Image` → `ai.djl.modality.Classifications` | `com.chua.deeplearning.support.image.ImageClassifier` | `mxnet/classification/vgg16` |

> 相对路径在注册时统一加 `mxnet/` 前缀（源码注释：支持 mxnet 模型符号文件与参数文件）。两个模型均未配置 `downloadUrl`，`ModelRegistry` 不会为它们自动联网下载。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 查看本引擎注册了哪些模型 | `MxnetModelRegistrar` → `ModelRegistry` | 注册 `mxnet-inceptionv3` / `mxnet-vgg16`，重复注册自动跳过 |
| 图像分类（单标签） | `ImageClassifier.create("mxnet-inceptionv3").classify(byte[] imageData)` | 返回最可能的类别名 |
| 图像分类（Top-K） | `ImageClassifier.create("mxnet-vgg16").classifyTopK(byte[] imageData, int k)` | 返回 `List<com.chua.deeplearning.support.model.DetectionInfo>` |
| 分类器链式配置 | `.topK(int)` / `.threshold(float)` / `.modelPath(String)` / `.device(String)` / `.model(String)` | 返回 `this`，支持链式调用 |
| 按能力枚举模型 | `ImageClassifier.listModels()` 或 `ModelRegistry.getModelIdsByCapability(ImageClassifier.class)` | 返回该能力下全部模型标识 |
| 解析模型本地路径 | `ModelRegistry.resolveModelPath("mxnet-inceptionv3")` | 按注册表解析顺序定位模型文件 |
| FeatureClient SPI（`provider=mxnet`） | `FeatureClient.create("mxnet", "")` | 得到 `MxnetFeatureClient`（可用性限制见 2.3） |
| 查询引擎侧模型列表 | `DeeplearningModels.models("mxnet")` | 按 provider 过滤引擎已发现的模型（当前返回空，见 2.3） |

### 2.2 Translator 自定义参数

两个 Translator 均提供无参与 `Map<String, ?> arguments` 两个构造器；未指定的键取默认值，最终委托给 DJL 的 `ai.djl.modality.cv.translator.ImageClassificationTranslator`（`optApplySoftmax(true)`），并转发 `getBatchifier()` / `processInput(...)` / `processOutput(...)`：

| 参数键 | 默认值 | 说明 |
| --- | --- | --- |
| `width` | VGG16 `224`、InceptionV3 `299` | 输入图像宽度（像素） |
| `height` | VGG16 `224`、InceptionV3 `299` | 输入图像高度（像素） |
| `resize` | `true` | 是否缩放 |
| `normalize` | `true` | 是否归一化 |
| `synsetFileName` | `classes.txt` | 类别标签文件名 |
| `applySoftmax` | `true` | 是否对输出应用 Softmax |

### 2.3 已知行为限制（源码行为，务必知悉）

1. **`FeatureClient`（`provider=mxnet`）当前不可直接用于特征提取。** `MxnetFeatureClient` 继承的 `AbstractLocalFeatureClient#extract / extractImage` 要求模型输出为 `float[]` / `double[]` 向量；而本模块注册的两个模型输出类型是 `ai.djl.modality.Classifications`（分类结果），向量转换会抛出 `IllegalStateException("模型输出不是向量: ...")`。
2. **`MxnetFeatureClient#models()` 当前返回空列表。** 其实现 `DeeplearningModels.models("mxnet")` 按 `provider` 字段过滤引擎模型；而引擎侧模型发现（`AbstractIdentificationEngine#discoverModels`）的 provider 取自 `resolveProvider(relativePath)`，该方法只识别 pytorch / safetensors / paddle / tensorflow 前缀，`mxnet/...` 路径会落入默认值 `onnx`。因此按 `provider=mxnet` 过滤结果为空，`extract / extractImage` 随之抛出 `IllegalStateException("引擎[mxnet]没有可用的特征模型")`。
3. **不要用 `ImageClassifier.create(provider, apiKey)` 传 `"mxnet"`。** 该重载走 `ServiceProvider` 按 SPI 名取 `ImageClassifier` 实现，本模块未注册 `ImageClassifier` 的 SPI 实现（登记文件只有 `ModelRegistrar` 与 `FeatureClient`）。请使用单参重载 `ImageClassifier.create(modelName)` 或 `ImageClassifier.create(modelName, ModelSetting)`。

> 结论：本模块对外**可靠可用**的入口是「模型注册 + `ImageClassifier` 能力调用」；`FeatureClient` 的 mxnet 提供者属于 SPI 层面的存在，待上游修正 provider 归类与向量输出后才能生效。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-mxnet-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

引入后无需任何装配：`MxnetModelRegistrar` 由 `META-INF/extensions/com.chua.deeplearning.support.engine.ModelRegistrar` 登记，主框架启动扫描 SPI 时即完成模型注册（`ModelRegistry` 的 javadoc：子模块通过 SPI 注册，Translator 类在首次推理时懒加载）。

### 3.2 图像分类（推荐入口）

```java
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MxnetClassifyDemo {
    public static void main(String[] args) throws Exception {
        byte[] image = Files.readAllBytes(Path.of("cat.jpg"));

        // 按模型标识创建分类器（底层走 IdentificationEngine 的已注册 Translator）
        ImageClassifier classifier = ImageClassifier.create("mxnet-inceptionv3");

        // 单标签：最可能的类别名
        String label = classifier.classify(image);
        System.out.println("类别: " + label);

        // Top-5：分类信息列表
        List<DetectionInfo> top5 = classifier.classifyTopK(image, 5);
        top5.forEach(d -> System.out.println(d));
    }
}
```

链式配置与能力枚举：

```java
ImageClassifier c = ImageClassifier.create("mxnet-vgg16")
        .topK(3)
        .device("cpu");            // 也可 .modelPath(...) / .threshold(0.5f)

String label = c.classify(image);

// 本能力下全部已注册模型标识：[mxnet-inceptionv3, mxnet-vgg16]
System.out.println(ImageClassifier.listModels());
```

### 3.3 查询与定位模型

```java
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageClassifier;

import java.nio.file.Path;

// 注册表条目（translator 类名、输入输出类型、能力接口、相对路径等）
ModelRegistry.Entry entry = ModelRegistry.get("mxnet-inceptionv3");

// 解析模型本地路径（解析顺序见 4.2）
Path path = ModelRegistry.resolveModelPath("mxnet-vgg16");

// 按能力接口枚举
System.out.println(ModelRegistry.getModelIdsByCapability(ImageClassifier.class));
```

### 3.4 FeatureClient SPI 形态（存在性示例）

```java
import com.chua.common.support.ai.feature.FeatureClient;

// SPI 名 "mxnet" 对应 MxnetFeatureClient（见 META-INF/extensions 登记文件）
try (FeatureClient client = FeatureClient.create("mxnet", "")) {
    System.out.println(client.getClass().getName()); // com.chua.deeplearning.support.mxnet.MxnetFeatureClient
    // 注意：client.models() 当前返回空列表；extract/extractImage 会抛 IllegalStateException（见 2.3）
}
```

---

## 4. 配置项

### 4.1 本模块自有配置项

**无。** 依据：本模块 `pom.xml` 只有依赖声明；源码 4 个类均无配置注解、无 `@ConfigurationProperties`、无 Spring 自动配置文件；`src/main/resources` 下仅有 SPI 登记文件与 GraalVM native-image 配置。原 README 的「本模块为零配置模块，引入依赖后即可使用」与此一致。

### 4.2 影响本模块模型解析的外部系统属性

以下系统属性定义在依赖模块 `utils-support-deeplearning-starter` 的 `com.chua.deeplearning.support.engine.ModelRegistry` 中，决定本模块注册模型的文件定位：

| 系统属性 | 默认值 | 说明 |
| --- | --- | --- |
| `deeplearning.model.root-dir` | `models/onnx`（相对当前工作目录） | 模型根目录；也可用 `ModelRegistry.setModelRootDir(String)` 在代码中设置 |
| `deeplearning.model.cache-dir` | `${java.io.tmpdir}/chua-dl-models` | 类路径资源解压与下载缓存目录 |

`ModelRegistry.resolveModelPath(modelId)` 的解析顺序（javadoc 摘录）：配置的相对/绝对/classpath 路径 → 下载缓存 → `{模型根}/{modelId}.{扩展名}` 与 `{模型根}/{modelId}/model.{扩展名}` → classpath 多前缀探测 → 递归扫描模型根 → 已注册外部目录 → 远程下载。本模块两个模型无 `downloadUrl`，需按 `mxnet/classification/inceptionv3`、`mxnet/classification/vgg16` 布局自备模型文件（符号文件 + 参数文件 + `classes.txt` 标签文件），否则推理时定位失败。

---

## 5. SPI 扩展点

### 5.1 本模块实现（作为提供方）

| SPI 接口 | 登记文件（`src/main/resources/META-INF/extensions/`） | 本模块实现 | SPI 名 |
| --- | --- | --- | --- |
| `com.chua.deeplearning.support.engine.ModelRegistrar` | 同名文件 | `MxnetModelRegistrar` | 无名（实现类直接登记） |
| `com.chua.common.support.ai.feature.FeatureClient` | 同名文件 | `MxnetFeatureClient` | `mxnet`（类上 `@Spi("mxnet")`） |

### 5.2 本模块定义的新 SPI 接口

**无。** 依据：源码中未出现新的接口定义；`ModelRegistrar`、`ImageClassifier`、`FeatureClient` 等契约分别位于 `utils-support-deeplearning-starter` 与 `utils-support-common-starter`。

### 5.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-deeplearning-mxnet-starter/`：

- `reflect-config.json`：对 `MxnetFeatureClient` 开放全部构造器/方法/字段反射；
- `resource-config.json`：打包 `META-INF/extensions/.*` 资源。

---

## 6. 构建与依赖

模块位于 `utils-support-parent-starter/utils-support-deeplearning-parent/` 下，随父聚合构建：

```bash
mvn -o -q compile -DskipTests
```

直接依赖（`pom.xml`）：

| 依赖 | 版本 | 作用 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 继承管理 | `FeatureClient`、`ServiceProvider`、`Spi`、`Wraps` 等契约与工具 |
| `com.chua:utils-support-deeplearning-starter` | `${project.version}` | `ModelRegistrar` / `ModelRegistry` / `ImageClassifier` / `AbstractLocalFeatureClient` / `DeeplearningModels` 等引擎契约（该模块实际位于 `utils-support-core-parent` 下） |
| `ai.djl.mxnet:mxnet-engine` | `${djl.version}` = `0.36.0` | DJL MXNet 推理引擎 |
| `ai.djl.mxnet:mxnet-model-zoo` | `${djl.version}` = `0.36.0` | DJL MXNet 模型库 |
| `org.projectlombok:lombok` | `provided` | 编译期代码生成 |

`djl.version` 定义于仓库根 POM `utils-support-parent-starter/pom.xml`。依赖方向符合仓库铁律：本模块只依赖底层 `utils-*` 模块，不依赖任何 `spring-*` 业务 starter。

---

## 7. 边界：这些能力不在本模块

| 能力 | 归属 |
| --- | --- |
| 推理执行、Translator 懒加载、模型路径解析与下载 | `utils-support-deeplearning-starter`（`IdentificationEngine` / `ModelRegistry` 等） |
| `ImageClassifier`、`ImageDetector` 等 CV 能力接口 | `utils-support-deeplearning-starter`（`com.chua.deeplearning.support.image` 等） |
| `FeatureClient`、`FeatureClientSetting`、`ServiceProvider`、`@Spi` 等 SPI 契约 | `utils-support-common-starter` |
| 其他推理引擎（ONNX / PyTorch / Paddle / TensorFlow 等） | 同级兄弟模块 `utils-support-deeplearning-*-starter` |
| 模型权重文件、标签文件本身 | 不随 jar 发布，需按 4.2 的目录布局自备 |
| 模型训练、微调、数据集管理 | 无（本模块与整个 utils 体系均不含） |
| Spring 自动配置、配置中心接入 | 无（本模块为零配置纯工具模块） |
