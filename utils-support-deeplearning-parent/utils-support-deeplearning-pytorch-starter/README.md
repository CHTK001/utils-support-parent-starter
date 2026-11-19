# utils-support-deeplearning-pytorch-starter

> utils 深度学习体系的 **PyTorch（DJL）推理引擎适配模块**：把 57 个 TorchScript 模型集中注册进 `ModelRegistry`，并以 `pytorch` 为 SPI 名，把图像生成、文本嵌入、特征提取、文本生成（翻译）等本地推理能力接入 `utils-support-common-starter` 的 AI 客户端体系。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.deeplearning.support.pytorch`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 核心机制](#4-核心机制)
- [5. 内置模型清单](#5-内置模型清单)
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
    <artifactId>utils-support-deeplearning-pytorch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 引擎适配 | DJL `pytorch-engine` 的模型注册与推理适配层，模型文件为 TorchScript（`.pt` / `.pth`） | 不含模型训练、微调逻辑，只做推理 |
| SPI 实现 | `ChatClient` / `EmbeddingClient` / `FeatureClient` / `ImageClient` 的 `pytorch` 本地实现 | 不提供 REST 接口，不是可独立运行的服务 |
| 模型托管 | 声明模型的相对路径、classpath 资源或远程下载地址，按需懒加载 | 不在 jar 内打包任何模型权重文件 |
| 能力范围 | 覆盖分类、检测、特征、增强、深度估计、生成、翻译七类能力接口 | 不实现 ONNX / Paddle / TensorFlow 等其它引擎（各自在兄弟模块） |

### 1.3 规模

- Java 源文件 **56** 个，全部位于 `com.chua.deeplearning.support.pytorch` 及其子包（`biggan`、`classification`、`colorization`、`controlnet`、`depth`、`detection`、`diffusion`、`face`、`feature`、`layout`、`resolution`、`sentence`、`translation`）。
- 注册模型 **57** 个：图像分类 12、目标检测 7、特征提取 16、图像增强 10、深度估计 3、图像生成 3、文本翻译 6。
- SPI 扩展声明文件 **5** 个（`src/main/resources/META-INF/extensions/`）。
- 内置 GraalVM `native-image` 配置：`reflect-config.json` / `proxy-config.json` / `resource-config.json`。
- 公开类自 `4.0.0.42` 起提供（源码 `@since` 标记）。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 本地文生图 | `ImageClient.create(setting)`，`provider="pytorch"` | `generate(prompt)` 返回 `BufferedImage`；BigGAN 系列生成模型 |
| 文本向量化 | `EmbeddingClient.create(setting)` | `embedding(text)` 返回 `float[]`；bge / MiniLM / granite 等嵌入模型 |
| 特征提取 | `FeatureClient.create("pytorch", "")` | `extract(text)` / `extractImage(bytes)` 返回 `float[]` |
| 本地文本生成（翻译） | `ChatClient.create(setting)` | `chatSync(prompt)` 返回 `String`；NLLB / Opus 分片模型 |
| 图像分类 | `ImageClassifier.create("pytorch-resnet18")` | `classify(bytes)`、`classifyTopK(bytes, k)` |
| 查可用模型 | `client.models()` | 返回 `List<ModelDefinition>`，含 provider、下载地址等 |
| 查注册表 | `ModelRegistry.get(modelId)` / `ModelRegistry.getAllByCapability(...)` | 按模型标识或能力接口枚举已注册模型 |
| 版面检测 | 模型 `wevisdoc-layout`（本地）/ `wevisdoc-layout-remote`（HTTP 调 Python 服务） | 输出 11 类版面框 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-pytorch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 本地文生图（ImageClient）

```java
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import java.awt.image.BufferedImage;

ImageClient client = ImageClient.create(ImageClientSetting.builder()
        .provider("pytorch")
        .model("pytorch-biggan-128")   // 也可先 client.models() 查看可选模型
        .build());

BufferedImage image = client.generate("一只戴墨镜的猫");
```

### 3.3 文本嵌入（EmbeddingClient）

```java
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.embedding.EmbeddingClientSetting;

EmbeddingClient embedding = EmbeddingClient.create(EmbeddingClientSetting.builder()
        .provider("pytorch")
        .model("pytorch-bge-embedding")
        .build());

float[] vector = embedding.embedding("你好，世界");
```

### 3.4 文本 / 图像特征提取（FeatureClient）

```java
import com.chua.common.support.ai.feature.FeatureClient;

FeatureClient feature = FeatureClient.create("pytorch", "");
float[] textVector  = feature.extract("要提取特征的文本");
float[] imageVector = feature.extractImage(imageBytes);   // byte[] 图像数据
```

### 3.5 本地文本生成 / 翻译（ChatClient）

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

ChatClient chat = ChatClient.create(ChatClientSetting.builder()
        .provider("pytorch")
        .model("pytorch-nllb-encoder")   // NLLB / Opus 为编码器-解码器分片模型
        .build());

String result = chat.chatSync("要处理的文本");
```

### 3.6 图像分类（能力接口）

```java
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.model.DetectionInfo;

ImageClassifier classifier = ImageClassifier.create("pytorch-resnet18");
String label = classifier.classify(imageBytes);
List<DetectionInfo> top5 = classifier.classifyTopK(imageBytes, 5);
```

> 说明：首次使用某模型时才解析 / 下载权重；带下载地址的模型在本地未命中时自动下载（HuggingFace 地址自动追加 hf-mirror.com 镜像）。

### 3.7 查询模型

```java
client.models();                                              // 该客户端可用模型
ModelRegistry.getAllByCapability(ImageDetector.class);        // 某能力下全部已注册模型
ModelRegistry.get("pytorch-yolov5s");                         // 单个模型注册条目
```

---

## 4. 核心机制

### 4.1 模型集中注册（ModelRegistrar SPI）

`PytorchModelRegistrar` 实现 `com.chua.deeplearning.support.engine.ModelRegistrar`，并在
`META-INF/extensions/com.chua.deeplearning.support.engine.ModelRegistrar` 中登记。基础模块的
`ModelRegistry.discoverAll()` 启动时扫描该文件、触发类加载，静态块与 `register(ModelRegistry)`
都会执行 `registerAll()`，把全部模型以「类名字符串 + 懒加载 Translator」的方式写入 `ModelRegistry`。

- `reg(...)` 统一给相对路径加 `pytorch/` 前缀后调用 `ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capabilityInterface, relativePath[, downloadUrl, compress, downloadFileName])`。
- 模型文件为 TorchScript（`.pt` / `.pth`），由 DJL pytorch 引擎加载。
- 路径解析顺序（由基础模块 `ModelRegistry.resolveModelPath` 定义）：绝对/相对路径 → classpath（含 JAR 内嵌，必要时解压到缓存目录）→ 模型根目录 → 远程下载。

### 4.2 AI 客户端 SPI 装配

四个客户端均标注 `@Spi("pytorch")`，通过 `META-INF/extensions/` 登记到 common-starter 的四个 AI 接口：

| 接口 | SPI 名 | 实现 | 构造参数 |
| --- | --- | --- | --- |
| `com.chua.common.support.ai.image.ImageClient` | `pytorch` | `PytorchImageClient` | `ImageClientSetting` |
| `com.chua.common.support.ai.embedding.EmbeddingClient` | `pytorch` | `PytorchEmbeddingClient` | `EmbeddingClientSetting` |
| `com.chua.common.support.ai.feature.FeatureClient` | `pytorch` | `PytorchFeatureClient` | `FeatureClientSetting` |
| `com.chua.common.support.ai.chat.ChatClient` | `pytorch` | `PytorchChatClient` | `ChatClientSetting` |

客户端内部经 `AbstractLocal*Client` 基类调用 `IdentificationEngine` 取出模型 Translator 执行推理；
`models()` 按「引擎名 + 输入/输出类型」从 `DeeplearningModels` 过滤出 pytorch 名下的模型定义。

### 4.3 懒加载与空闲卸载

启动阶段只登记模型定义，不解析路径、不加载权重；首次推理时由引擎创建推理会话。模型空闲超过阈值会被
守护线程自动卸载、下次调用自动重建（阈值与检查周期由基础模块系统属性控制，见[配置项](#6-配置项)）。

---

## 5. 内置模型清单

相对路径均省略 `pytorch/` 前缀（注册时自动补齐）。「下载」列标注 ✓ 表示注册了 HuggingFace 下载地址，
本地未命中时自动下载并追加 hf-mirror.com 镜像。

### 5.1 图像分类（`ImageClassifier`，12 个）

| 模型标识 | 相对路径 | 下载 |
| --- | --- | --- |
| `pytorch-resnet18` | `classification/resnet18.pt` | — |
| `pytorch-mobilenet-v2` | `classification/mobilenet_v2.pt` | — |
| `pytorch-expression` | `face/expression_densenet.pt` | — |
| `pytorch-age-gender` | `face/age_gender.pt` | ✓ |
| `pytorch-clip-zero-shot` | `classification/clip-vit.pt` | ✓ |
| `pytorch-animals-10` | `classification/animals-10.pt` | ✓ |
| `pytorch-food101` | `classification/food101.pt` | ✓ |
| `pytorch-plant` | `classification/plant.pt` | ✓ |
| `pytorch-mobilenetv3` | `classification/mobilenetv3.pt` | ✓ |
| `pytorch-mobilenetv4` | `classification/mobilenetv4.pt` | ✓ |
| `pytorch-face-emotion` | `face/yolo11-face-emotion.pt` | ✓ |
| `pytorch-wd-tagger` | `classification/wd-swinv2-tagger.pt` | ✓ |

### 5.2 目标检测（`ImageDetector`，7 个）

| 模型标识 | 相对路径 | 下载 |
| --- | --- | --- |
| `pytorch-yolov5s` | `detection/yolov5s.pt` | ✓ |
| `pytorch-yolov8n` | `detection/yolov8n.pt` | ✓ |
| `pytorch-ultraface` | `face/ultraface.pt` | — |
| `pytorch-yolo-face` | `face/yolo11n-face.pt` | ✓ |
| `pytorch-retinaface` | `face/detection/retinaface/retinaface_traced_model.pt` | — |
| `wevisdoc-layout` | `layout/wevisdoc.pt` | — |
| `wevisdoc-layout-remote` | 无本地权重（HTTP 调用 Python 推理服务） | — |

### 5.3 特征提取（`FeatureExtractor`，16 个）

| 模型标识 | 相对路径 | 下载 |
| --- | --- | --- |
| `pytorch-image-feature` | `feature/resnet18_feature.pt` | — |
| `pytorch-face-feature` | `face/face_feature.pt` | — |
| `pytorch-insightface` | `face/insightface.pt` | — |
| `pytorch-sphereface` | `face/sphereface.pt` | — |
| `pytorch-vggface` | `face/vggface.pt` | — |
| `pytorch-elasticface` | `face/elasticface.pt` | — |
| `pytorch-evolveface` | `face/evolveface.pt` | — |
| `pytorch-amazon-face` | `face/amazon_face.pt` | — |
| `pytorch-clip-text-encoder` | `diffusion/clip_text_encoder.pt` | — |
| `pytorch-sentence` | `sentence/sentence.pt` | — |
| `pytorch-text-feature` | `feature/text_feature.pt` | — |
| `pytorch-bge-embedding` | `feature/bge-small.pt` | ✓ |
| `pytorch-all-MiniLM` | `feature/all-MiniLM-L6-v2.pt` | ✓ |
| `pytorch-clip-image` | `feature/clip_image.pt` | — |
| `pytorch-dinov3` | `feature/dinov3.pt` | ✓ |
| `pytorch-granite-embedding` | `feature/granite-embedding.pt` | ✓ |

### 5.4 图像增强（`ImageEnhancer`，10 个）

| 模型标识 | 相对路径 | 下载 |
| --- | --- | --- |
| `pytorch-realesrgan` | `resolution/realesrgan_x4.pt` | — |
| `pytorch-colorization` | `colorization/colorization.pt` | — |
| `pytorch-hed-scribble` | `controlnet/hed_scribble.pt` | — |
| `pytorch-lineart` | `diffusion/lineart.pt` | — |
| `pytorch-lineart-anime` | `diffusion/lineart_anime.pt` | — |
| `pytorch-net-normal` | `diffusion/net_normal.pt` | — |
| `pytorch-pidi-scribble` | `diffusion/pidi_scribble.pt` | — |
| `pytorch-gfpgan` | `face/restoration/gfpgan_traced/gfpgan_traced_model.pt` | — |
| `pytorch-parsenet` | `face/segmentation/parsenet_traced_model.pt` | — |
| `pytorch-image-text-sr` | `resolution/image_text_sr.pt` | — |

### 5.5 深度估计（`DepthEstimator`，3 个）

| 模型标识 | 相对路径 | 下载 |
| --- | --- | --- |
| `pytorch-midas-depth` | `depth/midas.pt` | — |
| `pytorch-dpt-depth` | `depth/dpt.pt` | — |
| `pytorch-diffusion-depth` | `diffusion/depth.pt` | — |

### 5.6 图像生成（`ImageGenerator`，3 个）

| 模型标识 | 相对路径 | 下载 |
| --- | --- | --- |
| `pytorch-biggan-128` | `generation/biggan_128.pt` | — |
| `pytorch-biggan-256` | `generation/biggan_256.pt` | — |
| `pytorch-biggan-512` | `generation/biggan_512.pt` | — |

### 5.7 文本翻译（`TextTranslator`，6 个）

NLLB / Opus 均为编码器-解码器分片模型，供流水线组合使用。

| 模型标识 | 相对路径 | 下载 |
| --- | --- | --- |
| `pytorch-nllb-encoder` | `translation/nllb_encoder.pt` | — |
| `pytorch-nllb-decoder` | `translation/nllb_decoder.pt` | — |
| `pytorch-nllb-decoder2` | `translation/nllb_decoder2.pt` | — |
| `pytorch-opus-encoder` | `translation/opus_encoder.pt` | — |
| `pytorch-opus-decoder` | `translation/opus_decoder.pt` | — |
| `pytorch-opus-decoder2` | `translation/opus_decoder2.pt` | — |

---

## 6. 配置项

本模块自身**无配置文件、无 Spring 配置项**：`pom.xml` 不含 Spring 依赖，`src/main/resources` 下仅有
SPI 声明（`META-INF/extensions/`）与 GraalVM native-image 配置（`META-INF/native-image/`）。

影响本模块运行期行为的系统属性定义在基础模块 `utils-support-deeplearning-starter` 的 `ModelRegistry`
与 `AbstractIdentificationEngine` 中：

| 系统属性 | 默认值 | 说明 |
| --- | --- | --- |
| `deeplearning.model.root-dir` | `models/onnx`（相对工作目录） | 模型根目录 |
| `deeplearning.model.cache-dir` | `%TEMP%/chua-dl-models` | classpath 解压与远程下载缓存目录 |
| `deeplearning.model.idle-unload-ms` | `600000` | 模型空闲卸载阈值（毫秒），0 或负数关闭 |
| `deeplearning.model.idle-check-ms` | `60000` | 空闲检查周期（毫秒） |

另：`wevisdoc-layout-remote` 走 HTTP 调用外部 Python 推理服务，`WevisdocHttpClient` 默认地址为
`http://localhost:8765`，可通过构造器传入自定义地址。

---

## 7. SPI 扩展点

### 7.1 本模块已注册的 SPI

| 扩展文件（META-INF/extensions/） | 登记内容 |
| --- | --- |
| `com.chua.common.support.ai.chat.ChatClient` | `pytorch=com.chua.deeplearning.support.pytorch.PytorchChatClient` |
| `com.chua.common.support.ai.embedding.EmbeddingClient` | `pytorch=com.chua.deeplearning.support.pytorch.PytorchEmbeddingClient` |
| `com.chua.common.support.ai.feature.FeatureClient` | `pytorch=com.chua.deeplearning.support.pytorch.PytorchFeatureClient` |
| `com.chua.common.support.ai.image.ImageClient` | `pytorch=com.chua.deeplearning.support.pytorch.PytorchImageClient` |
| `com.chua.deeplearning.support.engine.ModelRegistrar` | `com.chua.deeplearning.support.pytorch.PytorchModelRegistrar` |

### 7.2 新增一个 pytorch 模型

1. 写一个 DJL Translator：实现 `ai.djl.translate.Translator<I, O>`（可参考本模块
   `PytorchImageNetClassificationTranslator` 的「缩放 + 归一化 + softmax」写法）；
2. 在任一 `ModelRegistrar` 实现的 `register(ModelRegistry)` 中调用
   `ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capabilityInterface, relativePath[, downloadUrl, compress, downloadFileName])`；
   本模块约定 relativePath 以 `pytorch/` 为前缀（`reg(...)` 已自动补齐）；
3. 若是新的 Registrar 类，在 `META-INF/extensions/com.chua.deeplearning.support.engine.ModelRegistrar`
   中登记其全限定类名，`ModelRegistry.discoverAll()` 会自动加载。

### 7.3 接入新的本地引擎

参考本模块结构：实现 `ChatClient` / `EmbeddingClient` / `FeatureClient` / `ImageClient`（可继承
基础模块 `com.chua.deeplearning.support.ai.client` 包下的 `AbstractLocal*Client`），标注 `@Spi("引擎名")`，
并在 `META-INF/extensions/<接口全限定名>` 文件中登记 `引擎名=实现类全限定名`。

---

## 8. 构建与依赖

```
utils-support-deeplearning-pytorch-starter
├── com.chua:utils-support-common-starter         （SPI 接口、AI 客户端契约、工具）
├── com.chua:utils-support-deeplearning-starter   （ModelRegistry / 引擎 / 能力接口 / 本地客户端基类）
├── ai.djl:api                                     0.36.0
├── ai.djl.pytorch:pytorch-engine                  0.36.0
├── ai.djl.pytorch:pytorch-model-zoo               0.36.0
├── ai.djl.huggingface:tokenizers                  0.36.0
└── org.projectlombok:lombok                       provided
```

- 本模块位于聚合 `utils-support-deeplearning-parent`（父 pom `4.0.0.51`）之下，JDK 25 编译。
- 编译验证：`mvn -o -q compile -DskipTests`，`EXIT=0` 为通过。
- `pom.xml` 中 `skipTests=false`；`src/test/resources/layout-bench/` 下带有版面检测测试图（table / mixed / list / figure / dense / columns / article）。

---

## 9. 边界：这些能力不在本模块

- **不含模型权重文件**：模型按「配置路径 → classpath → 远程下载」解析，权重落盘在模型根目录或缓存目录。
- **不做训练 / 微调**：只加载 TorchScript 模型做推理。
- **不提供 HTTP / REST 接口与 Web UI**：本模块是纯库；`wevisdoc-layout-remote` 的 HTTP 是向外调用 Python 服务，不是对外服务。
- **不支持异步任务式文生图**：本地图像生成的 `createTask` / `queryTask` 抛 `UnsupportedOperationException`，只有同步 `generate(prompt)`。
- **不负责会话编排 / Agent / RAG**：由 common-starter 的 `ai` 体系及其他模块负责，本模块只提供 `pytorch` 引擎侧的客户端实现与模型注册。
- **不实现其它推理引擎**：ONNX、Paddle、TensorFlow、MXNet、Safetensors、llama.cpp 等分别位于兄弟模块 `utils-support-deeplearning-*-starter`，本模块不重复实现。
