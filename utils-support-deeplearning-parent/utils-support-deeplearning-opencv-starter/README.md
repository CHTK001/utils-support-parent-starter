# utils-support-deeplearning-opencv-starter

> `com.chua:utils-support-deeplearning-opencv-starter`，当前版本 **4.0.0.51**，要求 **JDK 25**。
> 代码根包：`com.chua.deeplearning.support.opencv`（子包：`cascade` / `clarity` / `face` / `pedestrian` / `quality`）。
>
> **一句话定位**：utils-support 体系中的**纯 OpenCV 推理引擎模块**——基于 `org.openpnp:opencv 4.9.0-0` 提供人脸/眼睛/微笑/侧脸/全身 Haar 级联检测、HOG 行人检测、图像/人脸质量评估与高性能图像处理，**不依赖 DJL / ONNX Runtime**（pom 描述原文：*纯 OpenCV 推理引擎模块：人脸检测、图像分类等，不依赖 DJL/ONNX Runtime*）。

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界（这些能力不在本模块）](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

| ✅ 它是 | ❌ 它不是 |
|---|---|
| 纯 OpenCV（`org.openpnp:opencv 4.9.0-0`）实现的传统视觉推理引擎：Haar/LBP 级联检测、HOG 行人检测、Laplacian 方差质量评估、图像处理算子 | DJL / ONNX Runtime 深度学习推理模块（不含任何神经网络权重文件，推理全部走 OpenCV 传统算法） |
| 一组 SPI 的 `opencv` 提供者：`ImageProcessor`、`ModelProvider`、`FeatureClient`、`ImageClarityDetector`、`FaceClarityDetector` | Spring Boot 自动装配 starter（模块内无自动配置类、无 `@ConfigurationProperties`、无配置元数据文件） |
| `utils-support-deeplearning-starter` 引擎体系的模型/翻译器实现层（`ITranslator` / `BulkModelProvider`） | 高层视觉门面（OCR、人脸识别比对、姿态估计、图像分割等门面在 `utils-support-deeplearning-starter`） |
| 自带 5 个内嵌 Haar 级联模型资源（`src/main/resources/models/opencv/*.xml`），开箱即用、无需下载模型 | 模型仓库 / 模型下载器（不负责下载或管理外部模型文件） |
| 原生库加载失败的优雅降级：图像处理 SPI 自动回退 Rust / JDK 实现 | 图像处理算子的唯一实现（`binarize` / `denoise` / `erode` / `dilate` 不在 OpenCV 实现内，由 SPI 其他实现兜底） |

---

## 核心 API 与速查表

### 速查表：别人最常查的 8 个入口

| # | 想做什么 | 入口（一行） | 返回 |
|---|---|---|---|
| 1 | 图像处理（缩放/灰度/旋转/裁剪/模糊/翻转/亮度/对比度/边框/边缘） | `ImageProcessors.from(bytes).resize(200,200).grayscale().toBytes()` | `byte[]` |
| 2 | 直接调图像处理器（指定操作与参数） | `ImageProcessors.getProcessor().process(bytes, "rotate", Map.of("angle", 90))` | `byte[]` |
| 3 | 模板匹配（仅 OpenCV 实现支持） | `ImageProcessors.getProcessor().process(bytes, "templateMatch", params)` | `byte[]`（匹配结果回填 `params["matchResult"]`） |
| 4 | 图片清晰度评估 | `ImageClarityDetector.create("opencv", "").blurThreshold(100.0).assess(bytes)` | `ImageQualityInfo` |
| 5 | 人脸质量评估（检测 + 模糊/亮度/尺寸） | `FaceClarityDetector.create("opencv", "").blurThreshold(80.0).minFaceRatio(0.05f).assess(bytes)` | `FaceQualityInfo` |
| 6 | 通用目标检测（按模型 ID 走引擎） | `AbstractIdentificationEngine.getInstance().get("opencv-face-detector", ITranslator.class).translate(bytes)` | `List<PredictRectangle>` |
| 7 | 图像理解客户端（列出 opencv 引擎模型） | `FeatureClient.create("opencv", "")` → `models()` / `listModels()` | `List<ModelDefinition>`（`listModels()` 返回 `List<String>`） |
| 8 | 查看 opencv 引擎注册的全部模型 | `new OpencvModelProvider().getAll()` / `getDefinition()` | `List<TranslatorModelDefinition>` |

> `ImageProcessors.getProcessor()` 返回按 `@SpiOrder` 自动降级的代理：Rust 实现（order=100）优先，OpenCV 实现（order=50）次之，JDK AWT（order=-100）兜底；当前实现失败时自动降级到下一优先级。

### 引擎模型清单（`OpencvModelProvider` 一次注册 8 个，provider=`opencv`）

| 模型 ID | 实现类 | 输入 | 输出 | 默认参数 |
|---|---|---|---|---|
| `opencv-face-detector` | `OpencvFaceDetector` | `byte[]` | `List<PredictRectangle>`（labelName=`face`，confidence 固定 `1.0`） | scaleFactor `1.1`，minNeighbors `3`，最小尺寸 `30x30` |
| `opencv-eye-detector` | `OpencvCascadeDetector` | `byte[]` | `List<PredictRectangle>`（label=`eye`） | `1.1 / 3 / 20x20` |
| `opencv-smile-detector` | `OpencvCascadeDetector` | `byte[]` | `List<PredictRectangle>`（label=`smile`） | `1.1 / 15 / 20x20` |
| `opencv-profile-face-detector` | `OpencvCascadeDetector` | `byte[]` | `List<PredictRectangle>`（label=`profile_face`） | `1.1 / 3 / 30x30` |
| `opencv-fullbody-detector` | `OpencvCascadeDetector` | `byte[]` | `List<PredictRectangle>`（label=`fullbody`） | `1.1 / 3 / 40x80` |
| `opencv-pedestrian-hog` | `OpencvHogPedestrianDetector` | `byte[]` | `List<DetectionInfo>`（label=`person`） | hitThreshold `0.0`；宽 >800 时等比缩放到 800 再检测 |
| `opencv-image-quality` | `OpencvImageQualityAssessor` | `byte[]` | `ImageQualityInfo` | blurThreshold `100.0`，亮度区间 `[40, 220]`；综合分 = 清晰度 0.7 + 亮度 0.3 |
| `opencv-face-quality` | `OpencvFaceQualityAssessor` | `byte[]` | `FaceQualityInfo` | blurThreshold `80.0`，minFaceRatio `0.05`，亮度区间 `[40, 220]`；评分权重 检出 0.35 / 尺寸 0.20 / 清晰 0.30 / 亮度 0.15 |

### `OpenCVImageProcessor` 支持的操作与参数

| 操作 | 参数（键: 含义，默认值） |
|---|---|
| `resize` | `width`: 目标宽（200）、`height`: 目标高（200） |
| `grayscale` | 无 |
| `rotate` | `angle`: 角度（90），支持任意角度（非 90 倍数时按外接矩形扩边防裁剪） |
| `crop` | `x`（0）、`y`（0）、`width`（100）、`height`（100），越界自动收敛 |
| `blur` | `sigma`: 高斯模糊半径（3），核尺寸 = sigma*2+1 |
| `flip` | `axis`: `h` 水平 / `v` 垂直（默认 `h`） |
| `brightness` | `value`: [-255,255]（10） |
| `contrast` | `value`: [-100,100]（10） |
| `border` | `width`: 边框宽（1）、`color`: `#RRGGBB`（`#000000`） |
| `edge` | `method`: `canny`（默认）/ `sobel`；`threshold1`（50）、`threshold2`（150）为 Canny 双阈值；`direction`: `h`/`v`/`both`（Sobel 用，默认 `both`） |
| `templateMatch` | `template`: 模板图 `byte[]`（必填）；`method`: `ccoeff_normed`（默认）/`ccoeff`/`ccorr`/`ccorr_normed`/`sqdiff`/`sqdiff_normed`；`threshold`: 0~1（0.8）；`maxCount`（10）；`drawMatch`（true，绿框+红色分数标注）。匹配结果以 `List<Map>`（每项 `x`/`y`/`width`/`height`/`score`）回填到入参 `params["matchResult"]` |
| 通用 | `format`: 输出格式 `png`（默认）/ `jpeg` |

> 注意：接口级表格中的 `binarize` / `denoise` / `erode` / `dilate` **不在本模块 OpenCV 实现的分支内**，由 SPI 链路中的其他实现（如 JDK AWT）提供。

### 基础设施类

| 类 | 职责 |
|---|---|
| `OpencvNative` | 原生库加载工具。`OpencvNative.ensureLoaded()` 线程安全、幂等，内部调 `nu.pattern.OpenCV.loadLocally()`；失败抛 `IllegalStateException("OpenCV 原生库加载失败")`。所有翻译器构造时都会自动触发 |
| `OpencvModelTranslator` | 翻译器抽象基类（`ITranslator<Object, Object>`）。提供 `bytesToMat` / `bufferedToMat` / `matToBytes`（PNG）/ `matToBufferedImage` 转换，及 `resolveModelPath(String)`：文件系统路径优先，其次类路径（含 `models/opencv/` 多级回退），类路径资源会复制为临时文件供 `CascadeClassifier` 按路径读取 |
| `OpencvModelProvider` | `BulkModelProvider` 实现，向引擎批量注册上表 8 个模型；`TranslatorModelDefinition` 携带 `ModelDefinition`（id/name/provider/description）、translator、modelSize 与 `config.path` |

### 结果记录（定义在 `utils-support-deeplearning-starter`）

| 记录 | 字段 |
|---|---|
| `PredictRectangle` | `x`、`y`、`width`、`height`、`confidence`、`label`、`labelName`（、`keypoints` 可空） |
| `DetectionInfo` | `label`、`confidence`、`x`、`y`、`width`、`height`（、旋转框 `angle`/`rw`/`rh`/`cx`/`cy`） |
| `ImageQualityInfo` | `blurScore`（Laplacian 方差，越高越清晰）、`brightness`（0~255）、`contrast`（灰度标准差）、`sharpnessOk`、`brightnessOk`、`overallScore`（0~1）、`message` |
| `FaceQualityInfo` | `faceCount`、`faceAreaRatio`、`blurScore`、`brightness`、`contrast`、`faceOk`、`sizeOk`、`sharpnessOk`、`brightnessOk`、`overallScore`、`message`、`face`（最大人脸框，可空） |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-opencv-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最短可运行示例

```java
import com.chua.common.support.image.ImageProcessors;
import com.chua.deeplearning.support.face.FaceClarityDetector;
import com.chua.deeplearning.support.image.ImageClarityDetector;
import com.chua.deeplearning.support.model.FaceQualityInfo;
import com.chua.deeplearning.support.model.ImageQualityInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenCvQuickStart {

    public static void main(String[] args) throws Exception {
        byte[] image = Files.readAllBytes(Path.of("test.jpg"));

        // 1) 图像处理：缩放 + 灰度（OpenCV 实现，原生库不可用时自动降级 Rust/JDK）
        byte[] gray = ImageProcessors.from(image)
                .resize(200, 200)
                .grayscale()
                .toBytes();

        // 2) 图片清晰度评估（Laplacian 方差，无需下载模型）
        ImageQualityInfo q = ImageClarityDetector.create("opencv", "")
                .blurThreshold(100.0)
                .assess(image);
        System.out.println(q.message() + ", score=" + q.overallScore());

        // 3) 人脸质量评估（Haar 级联 + Laplacian，模型已内嵌 jar）
        FaceQualityInfo f = FaceClarityDetector.create("opencv", "")
                .blurThreshold(80.0)
                .minFaceRatio(0.05f)
                .assess(image);
        System.out.println(f.message() + ", faces=" + f.faceCount());

        // 4) 模板匹配（仅 OpenCV 实现支持；结果回填 params["matchResult"]）
        Map<String, Object> params = new HashMap<>();
        params.put("template", Files.readAllBytes(Path.of("template.jpg")));
        params.put("threshold", 0.8);
        byte[] matched = ImageProcessors.getProcessor().process(image, "templateMatch", params);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) params.get("matchResult");
        System.out.println("匹配数=" + matches.size());
    }
}
```

### 3. 走引擎按模型 ID 调用（人脸 / 眼睛 / 微笑 / 侧脸 / 全身 / 行人）

```java
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;

@SuppressWarnings("unchecked")
ITranslator<Object, Object> detector =
        AbstractIdentificationEngine.getInstance().get("opencv-face-detector", ITranslator.class);
List<PredictRectangle> faces = (List<PredictRectangle>) detector.translate(image);
```

> 模型 ID 可换为 `opencv-eye-detector`、`opencv-smile-detector`、`opencv-profile-face-detector`、`opencv-fullbody-detector`、`opencv-pedestrian-hog`、`opencv-image-quality`、`opencv-face-quality`（输出分别见上方模型清单）。

---

## 配置项

**无。** 本模块不读取任何配置文件键：`src/main/resources` 下仅有 `models/opencv/*.xml`（5 个 Haar 级联）、`META-INF/services`、`META-INF/extensions`、`META-INF/native-image`，没有 `@ConfigurationProperties`、`application.yml` 约定或 Spring 自动配置元数据。

所有行为参数均通过代码显式传入：

| 参数 | 传入方式 | 默认值 |
|---|---|---|
| 模糊阈值 `blurThreshold` | `ImageClarityDetector`/`FaceClarityDetector` 链式 API，或 `OpencvImageQualityAssessor`/`OpencvFaceQualityAssessor` 构造器 | 图像 `100.0`，人脸 `80.0` |
| 最小人脸面积比 `minFaceRatio` | `FaceClarityDetector.minFaceRatio(float)` 或构造器 | `0.05` |
| 亮度区间 `minBrightness`/`maxBrightness` | 质量评估器构造器 | `[40.0, 220.0]` |
| 模型路径 `modelPath` | 链式 API / 构造器 / `ModelSetting` | 内嵌 `models/opencv/haarcascade_frontalface_default.xml` 等 |
| 运行设备 `device` | 链式 API | `cpu`（OpenCV 实现仅 CPU） |
| HOG 命中阈值 `hitThreshold` | `new OpencvHogPedestrianDetector(double)` | `0.0` |
| 客户端配置 | `FeatureClientSetting`（provider、appKey 等） | — |

---

## SPI 扩展点

本模块通过 `com.chua.common.support.spi` 体系向框架注册 5 个扩展点（`ImageProcessor` 走 `META-INF/services`，SPI 键为 `image-processor`；其余 4 个走 `META-INF/extensions`，键均为 **`opencv`**）：

| SPI 接口 | 本模块实现 | 注册文件 | 说明 |
|---|---|---|---|
| `com.chua.common.support.image.ImageProcessor` | `OpenCVImageProcessor`（`@Spi("image-processor")`、`@SpiOrder(50)`，`name()="opencv"`） | `META-INF/services/...ImageProcessor` | 图像处理算子；静态块加载原生库，失败时 `available()=false`，由上层自动回退 Rust/JDK 实现 |
| `com.chua.deeplearning.support.engine.ModelProvider` | `OpencvModelProvider` | `META-INF/extensions/...ModelProvider` | 引擎发现模型入口；`BulkModelProvider.getAll()` 一次注册 8 个模型 |
| `com.chua.common.support.ai.feature.FeatureClient` | `OpencvFeatureClient`（`@Spi("opencv")`） | `META-INF/extensions/...FeatureClient` | 本地图像理解客户端，继承 `AbstractLocalFeatureClient`，`models()` 返回引擎下已注册模型 |
| `com.chua.deeplearning.support.image.ImageClarityDetector` | `OpencvImageClarityDetector` | `META-INF/extensions/...ImageClarityDetector` | 图片清晰度检测（默认模型 `opencv-image-quality`），`assess(byte[])` 返回 `ImageQualityInfo` |
| `com.chua.deeplearning.support.face.FaceClarityDetector` | `OpencvFaceClarityDetector` | `META-INF/extensions/...FaceClarityDetector` | 人脸清晰度检测（默认模型 `opencv-face-quality`）；`isAcceptable(byte[])` = `faceOk && sizeOk && sharpnessOk && brightnessOk` |

调用方式统一为 `Xxx.create("opencv", apiKey)` 或 `ServiceProvider.of(接口.class).getNewExtension("opencv", ...)`。

**自定义扩展**：想替换/新增 opencv 引擎下的模型，实现 `ITranslator`（建议继承 `OpencvModelTranslator`，复用 Mat 转换与模型路径解析），再提供自己的 `ModelProvider` 实现注册到 `META-INF/extensions/com.chua.deeplearning.support.engine.ModelProvider` 即可；引擎侧无需改动。

**GraalVM Native Image**：`META-INF/native-image/com.chua/utils-support-deeplearning-opencv-starter/` 下提供 `resource-config.json`（包含 `META-INF/services/.*` 与 `META-INF/extensions/.*`）、`reflect-config.json`、`proxy-config.json`。

---

## 构建与依赖

### 依赖树（实际声明于 pom.xml）

```text
com.chua:utils-support-deeplearning-opencv-starter 4.0.0.51
├── com.chua:utils-support-common-starter            （SPI / ImageProcessor / FeatureClient 契约）
├── com.chua:utils-support-deeplearning-starter      （引擎、ITranslator、模型记录、门面接口）
├── org.openpnp:opencv 4.9.0-0                       （OpenCV Java 绑定 + 内置原生库）
└── org.projectlombok:lombok                         （provided）
```

> pom 注释说明：OpenCV Haar/LBP 级联模型资源（人脸/眼睛/微笑/全身/侧脸等）以 **provided** 方式管理，编译期可见、不向下传递；调用方运行时如需更多级联模型，需显式引入 `utils-support-opencv-cascades`。本模块自身已在 `src/main/resources/models/opencv/` 内嵌 5 个级联文件：
> `haarcascade_frontalface_default.xml`、`haarcascade_eye.xml`、`haarcascade_smile.xml`、`haarcascade_profileface.xml`、`haarcascade_fullbody.xml`。

### 构建

```bash
# 仓库统一 JDK 25（根 pom：maven.compiler.source/target=25），离线编译：
mvn -o -q compile -DskipTests
```

- 首次使用无需任何安装步骤：`org.openpnp:opencv` 自带各平台原生库，由 `OpencvNative.ensureLoaded()` 通过 `nu.pattern.OpenCV.loadLocally()` 在 JVM 内加载。
- 类 `@since` 均为 4.0.0.42；父模块为 `utils-support-deeplearning-parent`（同版本 4.0.0.51）。

---

## 边界（这些能力不在本模块）

| 能力 | 去处 |
|---|---|
| 深度神经网络推理（ONNX / PyTorch / TensorFlow / Paddle /sherpa 等） | 同级其他 `utils-support-deeplearning-*-starter`；本模块明确**不依赖 DJL / ONNX Runtime** |
| OCR、版面分析、车牌、姿态估计、图像分割、图像生成/增强、以图搜图、ReID、活体检测等高层门面 | `utils-support-deeplearning-starter`（`com.chua.deeplearning.support.ocr` / `layout` / `plate` / `pose` / `image` / `reid` / `liveness` 等包） |
| `binarize` / `denoise` / `erode` / `dilate` 图像算子 | `ImageProcessor` SPI 的其他实现（如 JDK AWT 实现 `JdkImageProcessor`）；OpenCV 实现的 `process` 分支未包含它们 |
| 学习型图像质量评估（NIMA 等） | 其他引擎模块；本模块的 `opencv-image-quality` 是纯 OpenCV 统计量算法（Laplacian 方差 + 灰度均值/标准差） |
| 检测框后续业务（跟踪、比对、计数等编排） | 上层业务模块；本模块只产出 `PredictRectangle` / `DetectionInfo` / 质量记录 |
| 模型下载与版本管理 | 不提供；Haar 级联已内嵌 jar，其余模型需调用方自备路径（`resolveModelPath` 支持文件系统与类路径） |

**已知行为约束**：

- Haar 级联无置信度输出，检测结果的 `confidence` 固定为 `1.0`。
- OpenCV 实现仅支持 CPU（`device` 默认即 `cpu`）。
- 原生库加载失败时：`OpenCVImageProcessor.available()` 返回 `false`，图像处理调用自动降级；但直接构造各翻译器 / `FaceClarityDetector.create("opencv", ...)` 等路径会因 `OpencvNative.ensureLoaded()` 抛出 `IllegalStateException`。
- 翻译器输入仅接受 `byte[]`（图像编码字节），传入其他类型抛 `IllegalArgumentException`。
