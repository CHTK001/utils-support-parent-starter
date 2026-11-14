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
| `AbstractIdentificationEngine` | 抽象识别引擎，SPI 自动发现子模块翻译器，集中托管模型 |
| `ModelRegistry` | 模型注册表（多镜像下载、classpath/jar 模型解析、SPI 发现） |
| `ITranslator<I, O>` | 翻译器接口，隔离原始张量/字节流与业务对象 |
| `OcrRecognizer` / `OcrResult` | OCR 文字识别业务接口 |
| `FaceDetector` / `FacePipeline` | 人脸检测 / 人脸统一能力门面 |
| `ImageClassifier` / `ImageDetector` | 图像分类 / 检测业务接口 |
| `FeatureExtractor` | 特征提取业务接口 |
| `ImageEnhancer` | 图像增强（超分/修复/风格）业务接口 |
| `PoseEstimator` | 姿态估计业务接口 |
| `EmbeddingService` | 文本嵌入业务接口 |
| `MattingService` | 图像抠图业务接口 |
| `ImageCaptioning` | 图像描述业务接口 |
| ... | 共 57 个类 |

### 核心机制

- **IdentificationEngine**：单例中枢，扫描子模块 `ITranslator` 与 `ModelDefinition` 并集中托管，
  业务调用方按名称取用翻译器，实现模型实现与业务调用解耦。
- **翻译器模式**：`ITranslator<I, O>` 隔离底层模型与业务对象。
- **业务能力门面**：接口 + 包级 `DefaultXxx`，统一 `create(name)` 静态工厂。

```java
OcrRecognizer.create("paddleocrv6-medium-rec").recognize(imageBytes);
FaceDetector.create("yolov8-face").detect(imageBytes);
EmbeddingService.create("bge-zh").embed("文本");
```

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-starter
├── utils-support-common-starter
├── opencv (4.9.0-0)
└── onnxruntime-engine (DJL 0.36.0)
```

> 详细架构见 [架构设计.md](./架构设计.md)

---

## OpenCV 原生库与 glibc 对应关系

`org.openpnp:opencv` 把各平台原生库（`libopencv_java4xx.so`）直接打进 jar，运行时解压到
`java.io.tmpdir` 再 `System.load`。**它要求宿主机的 glibc / libstdc++ 不低于编译时所用版本**，
版本不足时 `UnsatisfiedLinkError`（首次）→ `NoClassDefFoundError: Could not initialize class`
（此后同进程内一律如此），并在调用栈里只剩一句 `Could not initialize class`。

下表为**实测值**（2026-11 量测）：从各版本 jar 里取出
`nu/pattern/opencv/linux/x86_64/libopencv_java*.so`，读其动态符号版本需求。

| `opencv.version`（坐标 `org.openpnp:opencv`） | 原生库 | 需要 GLIBC | 需要 GLIBCXX | CentOS 7.6（glibc 2.17 / GLIBCXX 3.4.19） |
|---|---|---|---|---|
| `3.4.2-2` | `libopencv_java342.so` | 2.15 | 3.4.15 | 可加载（但 3.x Java API，与本项目 4.x 代码不兼容） |
| `4.3.0-4` | `libopencv_java430.so` | 2.27 | 3.4.22 | 不可加载 |
| `4.5.1-2` | `libopencv_java451.so` | 2.27 | 3.4.22 | 不可加载 |
| `4.5.5-1` | `libopencv_java455.so` | 2.27 | 3.4.22 | 不可加载 |
| `4.7.0-0` | `libopencv_java470.so` | 2.27 | 3.4.22 | 不可加载 |
| `4.8.1-0` | `libopencv_java481.so` | 2.29 | 3.4.26 | 不可加载 |
| `4.9.0-0`（当前默认） | `libopencv_java490.so` | 2.29 | 3.4.26 | 不可加载 |

版本口径（向下兼容，按「不低于」看）：

| 宿主平台 | glibc | 能加载的最高 openpnp 版本 |
|---|---|---|
| CentOS / RHEL 7 | 2.17 | 3.4.2-2（4.x 全部不行） |
| CentOS / RHEL 8 | 2.28 | 4.7.0-0 |
| Debian 10 | 2.28 | 4.7.0-0 |
| Ubuntu 20.04 / Debian 11 | 2.31 | 4.9.0-0 |
| Ubuntu 22.04 / Rocky 9 | 2.35 | 4.9.0-0 |

**结论**：`org.openpnp` 的 4.x 全线最低要求 glibc 2.27，没有为 glibc 2.17 编译的版本
（最新版 `4.9.0-0` 发布于 2023-12，项目已不再更新）。因此 CentOS 7 这类老节点上
**调版本号解决不了**，只有三条路：

1. 换/升节点到 glibc ≥ 2.29（Ubuntu 20.04+、CentOS 8 需降到 `4.7.0-0`）；
2. 自行针对 glibc 2.17 编译 OpenCV Java 绑定（工作量大，且要与 `nu.pattern` 的加载器约定一致）；
3. 接受该能力不可用：本模块与 `spring-support-ai-starter` 已按「能力不可用」降级
   （`ImageUtils.load()` 把 `LinkageError` 转成 `IllegalStateException`，人脸/OCR 入口返回空清单 + 原因），
   不再把整页渲染成 500。

自测宿主是否够新（够则输出为空）：

```bash
ldd --version | head -1
strings /lib64/libm.so.6 | grep -o 'GLIBC_2\.[0-9]*' | sort -uV | tail -1
strings /usr/lib64/libstdc++.so.6 | grep -o 'GLIBCXX_3\.4\.[0-9]*' | sort -uV | tail -1
```

量测某个 opencv jar 的实际要求（Linux/macOS 宿主机上）：

```bash
unzip -p ~/.m2/repository/org/openpnp/opencv/4.9.0-0/opencv-4.9.0-0.jar \
  'nu/pattern/opencv/linux/x86_64/libopencv_java490.so' > /tmp/cv.so
strings /tmp/cv.so | grep -oE 'GLIBC_2\.[0-9]+|GLIBCXX_3\.4\.[0-9]+' | sort -uV
```


