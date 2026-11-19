# utils-support-deeplearning-arcsoft-starter

> 基于虹软 ArcSoft SDK 的人脸特征相似度比较模块，用于 VecStorage 1:N 识别：把 ArcSoft 人脸引擎包装成 `VectorCompareAlgorithm` SPI 实现，并提供人脸检测 / 特征提取 / 人脸裁剪三个模型翻译器。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.deeplearning.support.arcsoft`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API](#2-核心-api)
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
    <artifactId>utils-support-deeplearning-arcsoft-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

> 注意：由于依赖商业闭源 SDK `com.arcsoft.face:arcsoft-sdk-face`（离线仓库不可用），本模块**已从 `utils-support-deeplearning-parent` 的聚合构建中移除**；使用前需手动安装 SDK，再把本模块加回父 pom 的 `<modules>`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 比较算法 | `FaceFeatureCompareAlgorithm`：`VectorCompareAlgorithm` 的 ArcSoft 实现（SPI 名 `arcsoft`），做人脸特征相似度比较 | 不是通用向量距离库；欧氏 / 余弦 / 点积等内置算法在 `utils-support-common-starter` |
| 模型提供 | `ArcFaceModelProvider`：`BulkModelProvider` 实现，注册 3 个模型翻译器 | 不含推理编排与模型注册表（`IdentificationEngine` / `ModelRegistry` 在 `utils-support-deeplearning-starter`） |
| 引擎封装 | `ArcFaceEngineFactory`：统一创建 ArcSoft `FaceEngine` | 不负责 SDK 原生库分发、授权激活工具，也不含模型文件 |
| 装配方式 | 纯 SPI（`META-INF/extensions`），无 Spring 依赖 | 没有 Spring 自动配置、没有 HTTP/Controller 接口 |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 人脸特征 1:N 相似度比较 | `FaceFeatureCompareAlgorithm` | `VectorCompareAlgorithm` 实现，SPI 名 `arcsoft` |
| 按名字取比较算法 | `ServiceProvider.of(VectorCompareAlgorithm.class).getExtension("arcsoft")` | 首次实例化时完成原生库抽取与引擎初始化 |
| 注入向量库做 1:N 检索 | `VectorStorageBuilder.newBuilder().algorithm(algo)` | 算法在 `common` 的 `vector` 包 |
| 直接创建人脸引擎 | `ArcFaceEngineFactory.create()` | 返回 ArcSoft `FaceEngine` 实例 |
| 批量注册模型 | `ArcFaceModelProvider` | SPI：`com.chua.deeplearning.support.engine.ModelProvider` |
| 人脸检测 | `ArcFaceDetectorTranslator` | 输入图像 → `List<DetectionInfo>` |
| 人脸特征提取 | `ArcFaceFeatureTranslator` | 输入图像 → `float[]` 特征 |
| 人脸裁剪 | `ArcFaceCropperTranslator` | 输入图像 → 裁剪后 `BufferedImage` |

---

## 2. 核心 API

### 2.1 类一览

| 类 | 实现 / 继承 | 说明 |
| --- | --- | --- |
| `FaceFeatureCompareAlgorithm` | `VectorCompareAlgorithm`、`AutoCloseable`，`@Spi("arcsoft")` | 人脸特征相似度比较算法 |
| `ArcFaceModelProvider` | `BulkModelProvider` | 注册 3 个 ArcFace 模型翻译器 |
| `ArcFaceEngineFactory` | final 工具类 | `create()` 创建 `FaceEngine` |
| `ArcFaceDetectorTranslator` | `ITranslator<Object, List<DetectionInfo>>` | 人脸检测 |
| `ArcFaceFeatureTranslator` | `ITranslator<Object, float[]>` | 人脸特征提取 |
| `ArcFaceCropperTranslator` | `ITranslator<Object, BufferedImage>` | 人脸裁剪 |

### 2.2 FaceFeatureCompareAlgorithm

构造流程（首次实例化时执行）：

1. `DeeplearningEnvironment.of("arcsoft")` 读取配置；`model-path` 为空直接抛 `IllegalStateException("人脸识别引擎路径未配置，请设置 deeplearning.arcsoft.model-path")`；
2. 在 `<model-path>/arcsoft` 下创建原生库目录（失败抛 `IllegalStateException`）；
3. `NativeLoader.of("arcsoft").from(classLoader).toTarget(dllDir).glob("libarcsoft_face*").extractOnly(true).load()`——从类路径抽取 `libarcsoft_face*` 原生库到目标目录，**仅抽取不加载**（由 FaceEngine 自行加载）；
4. 校验 `libarcsoft_face_engine_jni.dll`（Windows）或 `.so` 存在，否则抛 `IllegalStateException("虹软 DLL 未找到: …")`；
5. `new FaceEngine(libPath)`；若 `app-id` 与 `app-secret` 均已配置则调用 `activeOnline` 激活（`MOK` 或 `MERR_ASF_ALREADY_ACTIVATED` 视为成功），否则跳过激活并告警；
6. 以 `ASF_DETECT_MODE_IMAGE` / `ASF_OP_ALL_OUT` / 最大人脸数 1 / 开启人脸检测的 `EngineConfiguration` 初始化引擎，失败抛 `IllegalStateException`。

方法：

| 方法 | 说明 |
| --- | --- |
| `name()` | 返回 `"FACE_COMPARE"` |
| `compare(float[] feature1, float[] feature2)` | 比较两个人脸特征；任一为 `null` 返回 `0.0f`；内部转 `FaceFeature` 后调用 `compareFaceFeature`，返回 `1 - min(score, 100) / 100`（score 为 ArcSoft 相似度 0~100） |
| `close()` | `faceEngine.unInit()` 释放引擎 |

> `compare()` 返回值是**距离语义：数值越小越相似**（完全同分时趋于 0）。但 `VectorCompareAlgorithm` 契约与 `VectorStorage` 的排序均按「越大越相似」处理（`MemoryVectorStorage` / `DefaultVectorStorage` 均按 compare 值降序取 Top-K）。因此把本算法注入 `VectorStorage` 前必须包一层 `1 - 返回值` 变换（即 ArcSoft score/100），否则 1:N 检索会返回最不相似的记录。

### 2.3 三个翻译器

均实现 `ITranslator`（`name()` + `translate(Object input)`），输入先用 `Converter.convertIfNecessary(input, BufferedImage.class)` 归一化；引擎为 `null` 或输入转不出图像时，`ArcFaceDetectorTranslator` 返回空列表、`ArcFaceFeatureTranslator` 返回 `float[0]`、`ArcFaceCropperTranslator` 返回原图。

| 翻译器 | `name()` | 输入 → 输出 | 行为与回退 |
| --- | --- | --- | --- |
| `ArcFaceDetectorTranslator` | `arcface-detector` | 图像 → `List<DetectionInfo>` | `detectFaces` 后把每张人脸映射为 `DetectionInfo("face", 1.0f, x, y, w, h)`；无人脸/异常返回空列表 |
| `ArcFaceFeatureTranslator` | `arcface-feature` | 图像 → `float[]` | 取第一张人脸 `extractFaceFeature`，返回特征数组；无人脸/异常返回 `float[0]` |
| `ArcFaceCropperTranslator` | `arcface-cropper` | 图像 → `BufferedImage` | 按第一张人脸框裁剪（越界自动收敛到图像范围）；无人脸/无效框/异常返回原图 |

### 2.4 ArcFaceModelProvider 与 ArcFaceEngineFactory

- `ArcFaceModelProvider`：无参构造时经 `ArcFaceEngineFactory.create()` 创建引擎，失败仅告警（引擎置 `null`，翻译器随后走空结果回退）。`getAll()` 返回 3 个 `TranslatorModelDefinition`（id/name 分别为 `arcface-detector`、`arcface-feature`、`arcface-cropper`，`provider` 均为 `arcsoft`）；`getDefinition()` 返回第一个。
- `ArcFaceEngineFactory`：`static FaceEngine create()`，即 `new FaceEngine()`；按 Javadoc，SDK 动态库加载失败或环境异常时抛运行时异常。

---

## 3. 快速开始

### 3.1 前置条件

1. 已把 `com.arcsoft.face:arcsoft-sdk-face:3.0.0.0` 安装到本地/私服 Maven 仓库（商业 SDK，仓库不含）；
2. ArcSoft 原生库（`libarcsoft_face*`）已放到类路径 `NAT/{platform}/` 目录（`NativeLoader` 的默认抽取位置）；
3. 已配置 `deeplearning.arcsoft.model-path`（必需）与 `app-id` / `app-secret`（用于激活）。

### 3.2 最短示例

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageBuilder;

public class ArcFaceDemo {
    public static void main(String[] args) {
        // 1. 配置（也可改用同名环境变量，见第 4 节）
        System.setProperty("deeplearning.arcsoft.model-path", "/data/models");
        System.setProperty("deeplearning.arcsoft.app-id", "<你的AppId>");
        System.setProperty("deeplearning.arcsoft.app-secret", "<你的AppSecret>");

        // 2. 按 SPI 名 arcsoft 取比较算法（首次获取时完成原生库抽取 + 引擎初始化）
        VectorCompareAlgorithm arcface =
                ServiceProvider.of(VectorCompareAlgorithm.class).getExtension("arcsoft");

        // 3. 1:1 比较：返回值越小越相似
        float distance = arcface.compare(feature1, feature2);

        // 4. 接入向量库做 1:N 检索
        // VectorStorage 按「compare 值越大越相似」降序取 Top-K，而 arcface.compare 返回的是
        // 距离（越小越相似），需包一层 1 - compare 变换，否则检索会返回最不相似的记录
        VectorCompareAlgorithm similarity = new VectorCompareAlgorithm() {
            @Override public String name() { return arcface.name(); }
            @Override public float compare(float[] a, float[] b) { return 1f - arcface.compare(a, b); }
        };
        VectorStorage storage = VectorStorageBuilder.newBuilder()
                .type("MEMORY")              // 向量存储 SPI 名称，此处用内存实现
                .dimension(feature1.length)  // 与实际特征维度一致（ArcSoft 特征维度以 SDK 输出为准）
                .algorithm(similarity)       // 注入「越大越相似」的包装算法
                .build();
        storage.add("person-1", feature1);
        var top1 = storage.search(feature2, 1);   // 命中列表（含 id 与向量）
    }
}
```

> `feature1` / `feature2` 为 `float[]` 人脸特征，可由 `ArcFaceFeatureTranslator` 从人脸图提取，或来自业务侧已存特征。使用完引擎进程退出前可 `((AutoCloseable) arcface).close()` 释放。

---

## 4. 配置项

配置经 `DeeplearningEnvironment`（provider=`arcsoft`）解析：**先读系统属性，再读环境变量**（环境变量名为系统属性键的点号替换成下划线后大写）。本模块无配置文件、无 Spring 配置绑定。

| 系统属性 | 对应环境变量 | 必填 | 说明 |
| --- | --- | --- | --- |
| `deeplearning.arcsoft.model-path` | `DEEPLEARNING_ARCSOFT_MODEL_PATH` | 是 | 模型/原生库根目录；其下 `arcsoft/` 子目录存放抽取出的原生库。未配置时 `FaceFeatureCompareAlgorithm` 构造直接抛 `IllegalStateException` |
| `deeplearning.arcsoft.app-id` | `DEEPLEARNING_ARCSOFT_APP_ID` | 否 | 与 `app-secret` 同时配置时调用 `activeOnline` 在线激活；缺任一则跳过激活（仅告警） |
| `deeplearning.arcsoft.app-secret` | `DEEPLEARNING_ARCSOFT_APP_SECRET` | 否 | 同上 |
| `deeplearning.arcsoft.active-key` | `DEEPLEARNING_ARCSOFT_ACTIVE_KEY` | 否 | `DeeplearningEnvironment` 支持该键，但本模块源码未使用 |

除上述键外无其他配置项；引擎检测模式、朝向、最大人脸数（1）与开启的功能（仅人脸检测）均在源码中写死，不提供配置。

---

## 5. SPI 扩展点

本模块是 SPI 的**实现方**，通过 `src/main/resources/META-INF/extensions/` 注册两个扩展：

| SPI 接口 | key | 本模块实现 | 消费方 |
| --- | --- | --- | --- |
| `com.chua.common.support.vector.VectorCompareAlgorithm` | `arcsoft` | `FaceFeatureCompareAlgorithm` | `VectorStorageBuilder#algorithm(...)` 注入向量库；也可 `ServiceProvider.of(VectorCompareAlgorithm.class).getExtension("arcsoft")` 直接取用 |
| `com.chua.deeplearning.support.engine.ModelProvider` | （类名注册） | `ArcFaceModelProvider` | `AbstractIdentificationEngine` 通过 `ServiceProvider.of(ModelProvider.class)` 自动发现；因实现的是 `BulkModelProvider`，会调用 `getAll()` 批量注册全部模型 |

本模块自身不定义新的可扩展接口；扩展点位于：

- `VectorCompareAlgorithm`（`utils-support-common-starter`）：`name()` + `compare(a, b)` 两个方法，内置 `euclidean()` / `cosine()` / `dotProduct()` 静态工厂；
- `ModelProvider` / `BulkModelProvider` / `ITranslator`（`utils-support-deeplearning-starter`）：模型注册与翻译器契约。

GraalVM native-image：`resource-config.json` 包含 `META-INF/extensions/.*`；`reflect-config.json` 为空（`null`）。

---

## 6. 构建与依赖

```xml
<dependencies>
    <dependency>
        <groupId>com.chua</groupId>
        <artifactId>utils-support-deeplearning-starter</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>com.arcsoft.face</groupId>
        <artifactId>arcsoft-sdk-face</artifactId>
        <version>3.0.0.0</version>
    </dependency>
</dependencies>
```

- 父级：`utils-support-deeplearning-parent` → `utils-support-parent-starter`，版本 `4.0.0.51`；另继承 provided 作用域的 Lombok。
- 传递依赖主要来自 `utils-support-deeplearning-starter`（其再依赖 `utils-support-common-starter` 等）。
- **聚合构建状态**：父 pom 已注释掉本模块——`arcsoft-sdk-face` 为商业闭源 SDK，离线仓库不可用。恢复步骤：手动 `mvn install` 该 SDK 到本地仓库（或配私服），再把 `<module>utils-support-deeplearning-arcsoft-starter</module>` 加回 `utils-support-deeplearning-parent/pom.xml`。
- 编译验证（需 SDK 已在仓库中）：

```text
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| 向量存储与检索（`VectorStorage`、`VectorStorageBuilder`、`VectorStorageProvider`）、内置比较算法（EUCLIDEAN / COSINE / DOT） | `utils-support-common-starter`（`com.chua.common.support.vector`） |
| 推理编排、模型注册表、DJL 模型工厂（`IdentificationEngine`、`ModelRegistry`、`DjlModelFactory` 等） | `utils-support-deeplearning-starter`（`com.chua.deeplearning.support.engine`） |
| 其他人脸能力（关键点、质量、对齐、搜索、比对管线等 `Face*` 接口） | `utils-support-deeplearning-starter`（`com.chua.deeplearning.support.face`） |
| ArcSoft SDK 本体、原生库文件、授权激活账号 | 需自行向虹软获取并部署；本模块只消费 |
| Spring 自动装配、HTTP 接口、配置文件（application.yml 等） | 均无；本模块是纯 SPI jar，配置只走系统属性 / 环境变量 |
| 任意图像格式解码 | 只接受能被 `Converter` 转成 `BufferedImage` 的输入，格式转换不在本模块 |
