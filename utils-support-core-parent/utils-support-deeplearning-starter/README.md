# utils-support-deeplearning-starter

深度学习核心模块：模型管理引擎（`IdentificationEngine`）、模型注册表（`ModelRegistry`）、翻译器模式（`ITranslator`）、
能力门面接口（OCR / 人脸 / 图像 / 语音 / 姿态 / 车牌 / 版面 / 活体 / 嵌入等）与 DJL / OpenCV 推理封装。

- 模块坐标：`com.chua:utils-support-deeplearning-starter:4.0.0.51`
- 基础包：`com.chua.deeplearning.support`
- JDK：25（继承父工程 `maven.compiler.source/target=25`，编译开启 `--enable-preview`）
- 依赖：`utils-support-common-starter`、`org.openpnp:opencv`（默认 4.9.0-0）、`ai.djl.onnxruntime:onnxruntime-engine`（DJL 0.36.0）

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界与限制](#边界与限制)

---

## 它是什么 / 不是什么

| 它是什么 | 它不是 |
|---|---|
| 模型能力中枢：单例引擎 + 静态注册表，统一托管模型定义、下载、加载与闲置回收 | 不是模型仓库：具体 ONNX/PyTorch 模型由 `utils-support-deeplearning-*-starter` 子模块通过 SPI 注册 |
| 翻译器模式：`ITranslator<I, O>` 把模型原始输入/输出转换为业务对象，每个 ONNX / OpenCV 模型对应一个翻译器实现 | 不是推理引擎本身：推理经由 DJL（OnnxRuntime/PyTorch/PaddlePaddle/TensorFlow）与 OpenCV 完成 |
| 业务能力门面：`OcrRecognizer`、`FaceDetector`、`ImageClassifier`、`ImageDetector`、`PoseEstimator`、`LicensePlateRecognizer`、`SpeechRecognizer`、`EmbeddingService`、`LivenessDetector`、`LayoutDetector` 等，统一 `create(name)` 静态工厂 | 不是 Spring Boot 自动配置模块：本模块零配置，不依赖 Spring 容器，直接以静态工厂 / SPI 使用 |
| 组合业务管线：`OcrPipeline`（检测+方向矫正+修复+识别编排）、`FacePipeline`（检测+活体+对齐+特征+检索，可选修复/超分/分割） | 不支持云厂商私有能力：云端 AI 客户端能力（chat / vision / text-to-image 等）在 `utils-support-common-starter` 一侧 |

---

## 核心 API 与速查表

### 引擎与注册表

| 入口 | 说明 |
|---|---|
| `AbstractIdentificationEngine.getInstance()` / `IdentificationEngine.create()` / `of(String)` | 获取识别引擎（单例，SPI 优先，回退匿名实例；`of(String)` 为兼容签名，当前实现与 `create()` 等价，参数不参与选择） |
| `IdentificationEngine#get(name, target, options)` | 按模型名 + 目标类型取翻译器，`options` 为运行参数（如 `threshold`、`iouThreshold`、`device`） |
| `IdentificationEngine#getModelNamesByCapability(Class\|String)` | 按能力接口/标签枚举可用模型 |
| `ModelRegistry.discoverAll()` | 扫描子模块注册的模型（`ModelRegistrar` SPI + 类路径模型目录），引擎初始化时自动调用 |
| `ModelRegistry.getModelIdsByCapability(Class)` / `getAllByCapability(Class)` | 按能力接口查询模型标识 / 注册条目 |
| `ModelRegistry.register(...)` / `registerExternalDirectory(dir, template)` | 注册模型（含下载地址、镜像、附属文件、硬件配置）或注册服务器本地模型目录 |
| `ModelParams.put(modelId, params)` / `resolveDevice` / `resolveThreshold` / `resolveNms` | 模型级运行参数存储与归一化解析（进程内） |
| `DetectOptions.of(threshold, nms)` / `DetectOptions.builder()` | 门面向引擎传参的 `Map<String,Object>` 构造工具 |

### 业务能力门面（统一 `create(name)` / `create(name, ModelSetting)` / `listModels()`）

| 能力 | 入口 | 典型方法 |
|---|---|---|
| OCR 文字识别 | `OcrRecognizer.create("paddleocrv6-medium-rec")` | `recognize(byte[])` → 文本；`recognizeDetail(byte[])` → `List<OcrResult>` |
| 人脸检测 | `FaceDetector.create("scrfd-face-detector")` | `detect(byte[])` → `List<PredictRectangle>`；`detectInfo`；`faceCount` |
| 图像分类 | `ImageClassifier.create(name)` | `classify(byte[])` → 类别名；`classifyTopK(byte[], k)` → `List<DetectionInfo>` |
| 图像检测 | `ImageDetector.create(name)` | `detect(byte[])` → `List<DetectionInfo>`（兼容 `PredictRectangle`） |
| 姿态估计 | `PoseEstimator.create(name)` | `estimate` → `List<PoseKeypoint>`；`estimateMulti` → 多人关键点 |
| 车牌识别 | `LicensePlateRecognizer.create(name)` | `recognize` → 车牌号；`recognizePlate` → `PlateResult`（号码+颜色） |
| 语音识别 | `SpeechRecognizer.create(name)` | `recognize(byte[])`；`recognize(byte[], language)`；默认采样率 16000 |
| 文本/图像嵌入 | `EmbeddingService.create(name)` | `embed(String)` / `embed(byte[])` → `float[]`；`embedBatch` |
| 活体检测 | `LivenessDetector.create(name)` | `isLive(byte[])` → boolean；`liveScore(byte[])` → 0~1 |
| 版面分析 | `LayoutDetector.create(name)` | `detect` → `Map<String, List<PredictRectangle>>`；`parse` → Markdown（端到端模型）或区域标签拼接文本（检测框模型） |

### 组合管线

| 入口 | 说明 |
|---|---|
| `OcrPipeline.builder().detector(...).recognizer(...).direction("pp-word-rotate").enhancer("text-bsr").minConfidence(0.6f).build()` | `recognize` / `recognizeDetail` / `recognizeDetailWithImage`（图+结果）/ `correct`（整图矫正）/ `enhance`（高清化） |
| `FacePipeline.builder().detector("scrfd-face-detector").feature("r50-face-feature").liveness("face-anti-spoof")....requireLive(true).build()` | `detectPipeline` → `List<FaceDetectionHit>`；`identifyPipeline` → `List<FaceIdentifyHit>`；`enrollPipeline(uid, imageBytes, metadata)` |

---

## 快速开始

```java
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.model.PredictRectangle;

public class Demo {
    public static void main(String[] args) {
        // 1. 引擎与注册表（子模块模型经 ModelRegistrar SPI 自动注册）
        AbstractIdentificationEngine.getInstance();
        ModelRegistry.discoverAll();

        // 2. OCR 识别
        OcrRecognizer ocr = OcrRecognizer.create("paddleocrv6-medium-rec");
        String text = ocr.recognize(imageBytes);

        // 3. 人脸检测
        FaceDetector detector = FaceDetector.create("scrfd-face-detector");
        List<PredictRectangle> faces = detector.detect(imageBytes);

        // 4. 文本嵌入
        float[] vec = EmbeddingService.create("bge-large-zh-embedding").embed("你好");
    }
}
```

完整组合示例（OCR 管线，示例模型来自 `utils-support-deeplearning-onnx-starter`）：

```java
OcrPipeline pipeline = OcrPipeline.builder()
        .detector("paddleocrv6-medium-det")
        .recognizer("paddleocrv6-medium-rec")
        .direction("pp-word-rotate")       // 方向矫正
        .enhancer("text-bsr")              // 文字高清化（可选）
        .minConfidence(0.6f)               // 置信度过滤
        .build();

String text = pipeline.recognize(imageBytes);                  // 拼接文本
List<OcrResult> results = pipeline.recognizeDetail(imageBytes); // 详情
byte[] corrected = pipeline.correct(imageBytes);               // 单独整图矫正
byte[] enhanced = pipeline.enhance(imageBytes);                // 单独高清化
```

> 模型标识以实际注册为准：`Xxx.listModels()` 可查询该能力下全部可用模型。

---

## 配置项

本模块**零 Spring 配置**（无 `spring.factories` / `application.yml` 绑定），以下系统属性与运行期参数由引擎直接读取：

| 系统属性 | 默认值 | 说明 |
|---|---|---|
| `deeplearning.device` | `auto` | 推理设备意图：`auto` / `cpu` / `gpu` / `cuda`；`auto` 自动探测 NVIDIA 驱动 + `onnxruntime_gpu` 构件，可用则 GPU |
| `deeplearning.model.idle-unload-ms` | `600000`（10 分钟） | 模型闲置多长毫秒后自动卸载（`AbstractIdentificationEngine` 闲置回收） |
| `deeplearning.model.idle-check-ms` | `60000` | 闲置模型回收检查间隔（毫秒） |
| `deeplearning.model.root-dir` | `models/onnx`（相对工作目录） | 模型根目录（`ModelRegistry`） |
| `deeplearning.model.cache-dir` | `%TEMP%/chua-dl-models` | 模型下载/解压缓存目录 |
| `deeplearning.<provider>.app-id` / `.app-secret` / `.active-key` / `.model-path` | 空 | 按 Provider 名称解析认证与模型路径（`DeeplearningEnvironment`，同时支持同名环境变量，点号转下划线大写） |

运行期参数（`ModelParams`，按 modelId 存储，参数读取优先级：**调用方显式 `options` > Provider 级设备覆盖 > 模型级持久化参数 > 系统属性 / 自动探测 > Translator 硬编码默认**）：

| 参数键 | 说明 |
|---|---|
| `device` | 推理设备：`cpu` / `gpu` / `auto`，同一进程内不同模型可以不同 |
| `threads` | 线程数（部分模型支持） |
| `threshold` | 置信度阈值（别名：`scoreThreshold`、`confThreshold` 等，按字段名反射回写 Translator） |
| `iouThreshold` | NMS IOU 阈值（别名：`nmsThreshold`、`nms` 等） |

门面层面的配置：`ModelSetting.builder().modelPath(...).device(...).build()` 传入 `create(name, setting)`；
`DetectionConfiguration`（全局静态配置：`useGpu` / `modelName` / `loadModelName` / `loadModelPath` / `systemOption`，含 `threshold` / `iouThreshold` 键常量）由各模型引擎共享。

---

## SPI 扩展点

| 扩展点 | 说明 |
|---|---|
| `ModelProvider`（函数式接口） | 提供单个 `TranslatorModelDefinition`（模型定义 + 翻译器实例 + 模型大小 + 配置），由引擎自动发现并加载 |
| `BulkModelProvider extends ModelProvider` | 一次注册多个模型定义（`getAll()`），引擎优先调用 |
| `ModelRegistrar`（函数式接口） | 子模块向 `ModelRegistry` 批量注册模型（下载地址、镜像、能力接口等） |
| `TranslatorModelDefinition` | `@Builder` 模型描述：`modelDefinition`、`translator`、`modelSize`、`config` |
| `ITranslator<I, O>` | 翻译器实现：`name()`（对应模型标识）+ `translate(I)` |

本模块自带的 `META-INF/extensions/` 注册内容：

| 注册文件 | 内容 |
|---|---|
| `com.chua.deeplearning.support.engine.ModelRegistrar` | `parakeet=com.chua.deeplearning.support.audio.CliAsrModelRegistrar` |
| `com.chua.common.support.ai.audio.VirtualClient` | `parakeet` / `parakeet-v3` / `nemo-speech` / `nemo-speech-asr` → `CliAsrAudioClient` |
| `com.chua.common.support.ai.agent.Agent` | `opencode-server=com.chua.deeplearning.support.agent.opencode.OpencodeServerAgent` |

新增模型引擎子模块的接入步骤（详见 `架构设计.md`）：

1. 新建 `utils-support-deeplearning-xxx-starter`，父模块改为 `deeplearning-parent`；
2. 依赖本模块；
3. 通过 `ModelProvider`（翻译器托管）或 common-starter SPI（通用能力）二选一/并存接入；
4. 在 `deeplearning-parent/pom.xml` 的 `<modules>` 中声明。

---

## 构建与依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

依赖关系：

```
utils-support-deeplearning-starter
├── utils-support-common-starter          (同仓基础模块)
├── org.openpnp:opencv                    (OpenCV Java 绑定，${opencv.version}，默认 4.9.0-0)
├── ai.djl.onnxruntime:onnxruntime-engine (DJL ONNX Runtime 引擎，${djl.version}，架构文档标注 0.36.0)
└── org.projectlombok:lombok              (provided)
```

- 版本：`4.0.0.51`（与 `utils-support-parent-starter` 全仓一致）
- 编译：JDK 25，`maven.compiler.source/target=25`（继承父工程），`--enable-preview`（本模块 compilerArgs）
- 测试：`skipTests=false`（执行单元测试）
- 镜像源与加载器：`ModelRegistry` 支持多镜像下载（`downloadMirrors`，主地址失败依次尝试）、重定向跟随（3xx）、断点续传（`.part` 后缀）与压缩包解压（`compress` + `downloadFileName`），模型文件后缀支持 `.onnx` / `.pt` / `.pth` / `.pdmodel` / `.pb` / `.safetensors`，类路径模型走 `classpath:` 前缀（`models/onnx/`、`models/pytorch/`、`models/paddle/`、`models/tensorflow/`、`models/safetensors/`，JAR 内嵌自动解压到缓存目录）

---

## 边界与限制

1. **OpenCV 原生库要求宿主 glibc / libstdc++ 足够新**。`org.openpnp:opencv` 把各平台原生库打进 jar，运行时解压到 `java.io.tmpdir` 再 `System.load`。实测（2026-11 量测）：

   | `opencv.version` | 需要 GLIBC | 需要 GLIBCXX | CentOS 7.6（glibc 2.17） |
   |---|---|---|---|
   | `3.4.2-2` | 2.15 | 3.4.15 | 可加载（但 3.x Java API 与本项目 4.x 代码不兼容） |
   | `4.5.1-2` / `4.5.5-1` / `4.7.0-0` | 2.27 | 3.4.22 | 不可加载 |
   | `4.9.0-0`（当前默认） | 2.29 | 3.4.26 | 不可加载 |

   `org.openpnp` 4.x 全线最低要求 glibc 2.27，**CentOS 7 上调版本号解决不了**；本模块与 `spring-support-ai-starter` 已按「能力不可用」降级（`ImageUtils.load()` 把 `LinkageError` 转成 `IllegalStateException`，人脸/OCR 入口返回空清单 + 原因），不再整页 500。

2. **模型文件缺失有可操作提示**。`DjlModelFactory` 对 `FileNotFoundException` 等根因给出「模型文件不存在：请把模型放到该路径，或在「提供商管理 → ONNX 模型目录」中重新注册」；CUDA 不可用时提示「请确认已安装 CUDA 运行库，或把该模型的推理设备改为 CPU」。

3. **GPU 自动降级**。`auto` 设备策略下：GPU 加载或推理失败自动降级 CPU 并重建会话（粘性标记，后续请求保持 CPU，进程内不再反复尝试 GPU）；降级依赖 `nvidia-smi` 可探测（3 秒超时）+ classpath 含 `onnxruntime_gpu` 构件（以 CUDA/TensorRT provider 原生库是否存在为判定）。

4. **推理需串行**。`DjlModelFactory#predict` 是 `synchronized` 的：DJL `Predictor` 内部持有单个 `OrtSession`，而 ONNX Runtime Java 绑定 `OrtSession.run()` 非线程安全，并发 run 会在原生层破坏内存并直接 SIGSEGV 掉整个 JVM；不使用 `ThreadLocal<Session>` 是因为推理跑在虚拟线程上会为每个虚拟线程各建一个原生 session 耗尽内存。

5. **DJL 0.36 下 `onnxruntime-engine` 不支持 NDArray 张量运算**（resize/sub/div 等），图像预处理统一走 OpenCV（经 `ImageUtils`，内部委托 `ImageProcessor` SPI，Rust > OpenCV > AWT 降级），再 `create(float[], shape)` 喂入模型。

6. **recognizeDetail 需要 检测+识别 管线**。对单模型 `OcrRecognizer`：检测模型输出为检测框列表、识别模型输出为 `String`，`recognizeDetail` 会抛 `IllegalStateException` 提示改用管线 API 或 `recognize()`。

7. **运行期参数热更新有线程安全约束**。`ModelParams.applyTo` / `DetectionConfigurable.configure` 会修改被多线程共享的 Translator 实例字段，若推理与配置变更并发，建议在业务层保证互斥或由各 Translator 自行加锁。

8. **能力标签与模型清单**。模型能力由 `ModelCapabilities`（`detect` / `ocr` / `pose` / `embedding` / `liveness` / `layout` / `asr` / `tts` 等标签）与注册时声明的能力接口共同决定；`FaceDetector.listModels()` 除 `FaceDetector` 能力位外，还会并入注册为 `ImageDetector` 且权重路径含 `face/detection/` 段的模型（SCRFD / RetinaFace / TinaFace / YOLO-Face / FacePlugin / UltraFace 等），避免「能力清单缺可用模型」。

9. **native-image 支持**：已提供 `META-INF/native-image/` 配置（`reflect-config.json` 注册 `com.chua.deeplearning.support.ocr.DirectionInfo`；`resource-config.json` 包含 `META-INF/extensions/.*` 与 `META-INF/services/.*`）。

> 详细架构、OCR/人脸管线流程与新增子模块指南见 [架构设计.md](./架构设计.md)。