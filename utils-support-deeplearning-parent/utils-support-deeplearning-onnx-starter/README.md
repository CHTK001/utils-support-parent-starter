# utils-support-deeplearning-onnx-starter

ONNX Runtime 推理实现，通过 deeplearning 核心门面提供目标检测、分类、特征提取、OCR、版面分析、抠图、图像增强、文本嵌入和部分语音/文本模型。模型目录由 `OnnxModelRegistrar` 登记；目录存在不表示权重、词表、原生库已经就绪，也不表示所有模型都经过当前机器的推理验证。

## 安装与运行条件

JDK 25，版本与仓库一致：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-onnx-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

当前 POM 使用 DJL API / ONNX Runtime Engine / Hugging Face Tokenizers `0.36.0`，`cpu` profile 默认引入 `onnxruntime:1.30.0`，`gpu` profile 引入 `onnxruntime_gpu:1.30.0`。POM 中仍有提到旧 Runtime 版本的注释，依赖声明才是当前构建依据。

编译模块时可显式二选一：

```bash
mvn -o -pl utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter -am compile -DskipTests '-P!gpu,cpu'
mvn -o -pl utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter -am compile -DskipTests '-P!cpu,gpu'
```

这是本模块构建时的 profile；消费已发布依赖时，应核对实际依赖树，确保运行时只保留所选 Runtime。GPU 版还要求匹配的驱动、CUDA/cuDNN 原生运行库和具体翻译器的 GPU 配置，添加 jar 或设置 `Device.gpu()` 本身不能证明 CUDA 已启用。OpenCV 与 Tokenizers 也包含原生加载条件。

## 模型发现、路径与下载

```java
import com.chua.deeplearning.support.engine.ModelRegistry;

ModelRegistry.discoverAll();
var entry = ModelRegistry.get("paddleocrv6-medium-det");
if (entry == null) {
    throw new IllegalStateException("模型未注册");
}
System.out.println(entry.modelId());
```

`discoverAll()` 扫描可用引擎的注册器；直接使用 `ModelRegistry.ensureDownloaded(modelId)` 前也要先发现模型。下载只能使用该条目实际登记的地址，未登记 URL 的模型需要自行提供文件。不要把获取注册项当成模型可用性探测。

- `-Ddeeplearning.model.root-dir=...` 指定文件系统模型根目录，默认 `models/onnx`，相对于进程工作目录。
- `-Ddeeplearning.model.cache-dir=...` 指定 classpath 解压及下载缓存根目录，默认 `java.io.tmpdir/chua-dl-models`；目录必须可写且有足够空间。上述属性应在类初始化前配置。
- 支持文件系统模型和 classpath 资源；资源在 jar 内时会解压。模型所需的 tokenizer、字典、配置、ONNX external data 等伴随文件也必须齐全，不能只拷贝主 `.onnx`。
- 核心依赖不附带整套模型资源。按登记路径手动布置文件，或另行安装所选模型的资源包；资源仓库独立维护。
- 如原生库解压受限，可使用 Runtime 支持的 `-Donnxruntime.native.path=...` 指向匹配版本的原生库目录；目录中应直接存在平台所需库文件。

## OCR：检测、裁剪与行识别

`OcrPipeline` 位于 deeplearning 核心模块，本模块提供 ONNX 检测和识别适配。以下是放在方法体中的用法，`document.png` 是调用方提供的真实输入文件：

```java
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import java.nio.file.Files;
import java.nio.file.Path;

ModelRegistry.discoverAll();
var pipeline = OcrPipeline.builder()
        .detector("paddleocrv6-medium-det")
        .recognizer("paddleocrv6-medium-rec")
        .minConfidence(0.6f)
        .build();
byte[] image = Files.readAllBytes(Path.of("document.png"));
var results = pipeline.recognizeDetail(image);
System.out.println(results);
```

也可调用 `recognize(byte[])` 取得合并文本，或 `recognizeDetailWithImage(byte[])` 同时取得识别详情与结果图。tiny 对应 `paddleocrv6-det` / `paddleocrv6-rec`，medium 对应上述两个名称。识别模型还需要匹配的字典/配置文件。

可选构建项：

- `.direction("doc-orientation")`：四方向整图矫正，需要 `ocr/direction/doc_ori/model.onnx`；旧 `pp-word-rotate` 当前已停用，不能继续当作可用默认模型。
- `.enhancer("text-bsr")`：安装 `vision/text_restore/textbsr/textbsr.onnx` 后提供文字图像修复；是否并入识别流程由 `enhanceInPipeline` 或质量门控控制。单独 `enhance(byte[])` 返回增强图像。
- `qualityGate/blurThreshold` 控制模糊图处理，`cropPadding/cropMinHeight/cropRotateThreshold` 控制裁剪，`sortReadingOrder/minConfidence` 控制输出。不要用固定阈值保证所有文档的准确率。

`correct(byte[])` 是图像方向矫正入口。`OnnxOcrPipeline` 保留旧包兼容委托，实际编排仍由核心 `OcrPipeline` 执行。

## 文本嵌入

```java
import com.chua.common.support.ai.embedding.EmbeddingClient;

try (var embedding = EmbeddingClient.create("onnx", "")) {
    float[] vector = embedding.model("bge-small-zh-embedding")
            .embedding("需要检索的中文文本");
    System.out.println(vector.length);
}
```

`OnnxEmbeddingClient` 注册为 `onnx`，使用实际模型 ID。目录含 `minilm-embedding`、`bge-small-zh-embedding`、`bge-large-zh-embedding`、`embeddinggemma-2-embedding` 等；维度、分词、归一化和伴随资源由各模型翻译器决定，不要混用不同模型的向量。

## 其它实现入口

| 任务 | 门面/源码入口 | 登记示例 |
| --- | --- | --- |
| 检测与分类 | `ImageDetector` / `ImageClassifier` | YOLO 系列、`efficientnet-b1-classification` |
| 视觉特征、人脸与重识别 | `FeatureExtractor` 与对应翻译器 | `mobileclip-s0-vision`、`osnet-reid` |
| 文档版面 | `LayoutDetector` | `pp-doc-layout`、`doc-layout-yolo` |
| 抠图与分割 | 分割/抠图翻译器 | `modnet`、`matting-u2netp`、`matting-isnet` |
| 图像增强与人脸修复 | `ImageEnhancer` 与专用翻译器 | `real-esrgan`、`naf-net`、`onnx-gfpgan` |
| 语音、图像描述与文本任务 | 对应音频/文本翻译器 | `mms-tts-eng`、`vit-gpt2-captioning`、BERT/GPT 系列 |

完整名称及模型路径见 [OnnxModelRegistrar](src/main/java/com/chua/deeplearning/support/onnx/OnnxModelRegistrar.java)。部分条目因实现或资源缺失被注释停用，不能从注释推断为可调用能力。不同翻译器的输入输出类型也不完全相同，调用应经对应门面或确认注册项类型后进行。

本 README 的示例按源码签名核对；编译检查不下载模型、不启动推理，不代表上述模型的运行验收。

返回 [深度学习目录](../README.md) / [完整目录](../../README.md)。
