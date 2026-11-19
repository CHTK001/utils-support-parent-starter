# utils-support-video-processor-starter

> 视频转 HLS 处理器（Rust JNI）：一个只有两个类的极薄 Java 门面，把「输入视频 → HLS 切片」这一件事委托给 Rust 原生库完成。
>
> 版本：`4.0.0.51`｜JDK：`25`（父 POM `maven.compiler.source/target=25`）｜包名：`com.chua.video.processor.support`

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
    <artifactId>utils-support-video-processor-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 「视频转 HLS」的静态门面：`VideoProcessor` 三个静态方法 + 一个 CLI 入口 | 不是通用视频处理框架，不含播放、剪辑、抽帧等能力 |
| 实现 | 只依赖 SPI 契约 `VideoProcessorSpi`，运行期由 `NativeServices` 查找实现 | 本模块不含 Rust 源码、JNI 绑定类或动态库；实现在 `utils-support-native-video-processor` |
| 形态 | 普通 jar：`VideoProcessor` 直接静态调用，无需任何容器 | 不是 Spring Boot starter：pom 无任何 Spring 依赖，无自动装配类 |
| 依赖 | 编译期仅依赖 `utils-support-common-starter` | 不依赖数据库、Web、消息中间件 |

判断口径：业务代码只需要「把一个视频转成 HLS」这一个动作时用本模块；需要更完整的 FFmpeg 媒体能力（视频文件读写等）请用 `utils-support-ffmpeg-starter` 体系。

### 1.3 源码规模

- 主代码仅 **2 个类**：`VideoProcessor`（公开门面）、`VideoProcessorBridge`（包私有桥接）。
- 资源仅 **2 个文件**：`META-INF/native-image/` 下的 GraalVM native-image 配置。
- 模块目录内另有《单元测试覆盖矩阵.md》记录测试覆盖口径（完整转码属 `[MANUAL]` 项，需动态库就绪后执行）。

---

## 2. 核心 API 与速查表

### 2.1 VideoProcessor（唯一公开入口）

`com.chua.video.processor.support.VideoProcessor`，全部为静态方法（`@since 4.0.0`）：

| 需求 | 方法签名 | 说明 |
| --- | --- | --- |
| 转码 HLS | `public static boolean transcodeToHls(String inputPath, String outputDir)` | 把 `inputPath` 视频转码为 HLS 并输出到 `outputDir`；成功返回 `true` |
| 查版本 | `public static String getVersion()` | 返回原生库版本串 |
| 探测可用 | `public static boolean isAvailable()` | 原生库是否已加载；不可用返回 `false`，不抛异常 |
| 命令行入口 | `public static void main(String[] args)` | `args[0]=输入路径`，`args[1]=输出目录`；打印 `Success` / `Failure` |

调用链：`VideoProcessor` → `VideoProcessorBridge`（包私有，提供 `isLoaded()` / `ensureLoaded()` / `transcodeToHls` / `getVersion`）→ `NativeServices.get(...)` / `require(...)` → SPI 实现。

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 视频转 HLS | `VideoProcessor.transcodeToHls(inputPath, outputDir)` | 最常用入口；原生库未就绪时抛 `IllegalStateException` |
| 先探测再转码 | `VideoProcessor.isAvailable()` | 返回 `false` 时不抛异常，可安全降级 |
| 查原生库版本 | `VideoProcessor.getVersion()` | 版本串来自 SPI 实现 |
| 命令行转码 | `java com.chua.video.processor.support.VideoProcessor <input> <output_dir>` | 成功打印 `Success`，失败打印 `Failure` |
| SPI 契约 | `com.chua.common.support.nativeapi.VideoProcessorSpi` | 接口定义在 `utils-support-common-starter` |
| SPI 查找 | `NativeServices.get(VideoProcessorSpi.class)` / `NativeServices.require(VideoProcessorSpi.class, "视频处理原生能力")` | 缺失实现时 `get` 返回 `null`，`require` 抛异常 |
| 加载失败原因 | `VideoProcessorSpi#getLoadError()` | 返回加载失败的 `Throwable`；加载成功返回 `null` |
| 引入原生实现 | 增加依赖 `utils-support-native-video-processor` | 经 `META-INF/services` 注册 `NativeVideoProcessorSpi` |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-video-processor-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- 原生实现（Rust 动态库 + JNI 绑定）；缺失时 isAvailable() == false -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-native-video-processor</artifactId>
    <version>4.0.0.43</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.video.processor.support.VideoProcessor;

public class Demo {
    public static void main(String[] args) {
        if (!VideoProcessor.isAvailable()) {
            System.out.println("native 库不可用：请检查 utils-support-native-video-processor 依赖");
            return;
        }
        System.out.println("native 版本: " + VideoProcessor.getVersion());
        boolean ok = VideoProcessor.transcodeToHls("input.mp4", "./hls_output");
        System.out.println(ok ? "Success" : "Failure");
    }
}
```

### 3.3 命令行方式

```text
java -cp <classpath> com.chua.video.processor.support.VideoProcessor input.mp4 ./hls_output
```

- 参数不足 2 个时打印 `Usage: java VideoProcessor <input> <output_dir>`，并抛出 `IllegalStateException("程序执行失败，请检查执行结果日志。")`。
- 转码完成打印 `Success` 或 `Failure`。

---

## 4. 配置项

**无。**

依据：本模块 `pom.xml` 未声明任何配置属性或配置插件参数；`src/main/resources` 下只有 GraalVM native-image 配置（`reflect-config.json`、`native-image.properties`），没有任何 properties/yml 配置文件；两个 Java 类源码中也没有读取系统属性或环境变量的代码。原生库的加载与定位由 SPI 实现（`utils-support-native-video-processor`）自行完成，本模块零配置。

---

## 5. SPI 扩展点

本模块是 SPI 的**消费方**。扩展点是 `utils-support-common-starter` 中的 `com.chua.common.support.nativeapi.VideoProcessorSpi`（`@since 4.0.0.50`）：

```java
public interface VideoProcessorSpi extends NativeService {
    boolean isLoaded();                    // 原生库是否已加载
    default boolean available() {          // NativeService 契约，默认等同 isLoaded()
        return isLoaded();
    }
    void ensureLoaded();                   // 确保已加载，未加载时抛异常
    boolean transcodeToHls(String inputPath, String outputDir);  // 成功返回 true
    String getVersion();                   // 原生库版本串
    Throwable getLoadError();              // 加载失败原因；成功返回 null
}
```

要点：

- **查找入口**：`NativeServices.get(VideoProcessorSpi.class)` 在实现缺失或声明不可用时返回 `null`（不把 `NoClassDefFoundError` 抛给调用方）；`NativeServices.require(VideoProcessorSpi.class, "视频处理原生能力")` 在缺失时抛 `IllegalStateException`，文案为「视频处理原生能力不可用：请自行添加对应的 utils-support-native-* 依赖」。
- **官方实现**：`utils-support-native-video-processor` 的 `com.chua.video.processor.support.bridge.NativeVideoProcessorSpi`（注解 `@Spi("native")`），经 `META-INF/services/com.chua.common.support.nativeapi.VideoProcessorSpi` 注册，内部委托同模块的 JNI 绑定类 `VideoProcessorBridge` 加载 Rust 动态库。
- **替换实现**：想改用其它方案（例如进程外 FFmpeg 服务），在自己的模块里实现 `VideoProcessorSpi` 并注册 `META-INF/services` 文件即可，本模块与 `utils-support-common-starter` 均无需改动。

---

## 6. 构建与依赖

### 6.1 依赖关系

```text
utils-support-video-processor-starter
├── utils-support-common-starter        （SPI 契约：NativeServices / VideoProcessorSpi）
└── utils-support-ffmpeg-rust-starter   （provided 备用；源码未直接 import）
```

### 6.2 编译本模块

在 `utils-support-parent-starter` 仓库根目录执行：

```bash
mvn -o -q compile -DskipTests -pl utils-support-extra-parent/utils-support-video-processor-starter
```

### 6.3 原生库构建（不在本模块内）

Rust 源码与 JNI 绑定位于 `utils-support-native-parent/utils-support-native-video-processor`：

- Rust 工程：`src/main/rust/`（`build.sh`、`Cargo.toml`、`Cargo.lock`、`src/lib.rs`）。
- 构建脚本用法：`./build.sh [平台] [架构] [构建模式]`，例如 `./build.sh linux x86_64 release`、`./build.sh darwin aarch64 release`、`./build.sh auto auto release`；构建完成后动态库被复制到 `../resources/native/<平台目录>/`。
- 仓库内已随库分发四平台产物：`windows-x86_64/video_processor.dll`、`linux-x86_64/libvideo_processor.so`、`darwin-x86_64/libvideo_processor.dylib`、`darwin-aarch64/libvideo_processor.dylib`。

### 6.4 GraalVM native-image 配置

本模块 `src/main/resources/META-INF/native-image/com.chua/utils-support-video-processor-starter/` 下提供：

- `reflect-config.json`：注册 `com.chua.video.processor.support.bridge.VideoProcessorBridge` 的全部构造器/方法/字段（JNI 回调需要反射可见）。
- `native-image.properties`：`Args = --initialize-at-build-time=com.chua.video.processor.support.bridge`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 实际归属 |
| --- | --- |
| Rust 源码、JNI 绑定类、四平台动态库产物 | `utils-support-native-video-processor` |
| SPI 契约（`VideoProcessorSpi`、`NativeService`、`NativeServices`） | `utils-support-common-starter` |
| 通用 FFmpeg 媒体处理（视频文件系统、`FFmpegProcessor` SPI） | `utils-support-ffmpeg-starter` 体系；本模块对 `utils-support-ffmpeg-rust-starter` 仅为 provided 备用依赖，源码未 import |
| 抽帧、截图、剪辑、合成、字幕等其它视频能力 | 源码未提供；本模块公开能力仅有 `transcodeToHls` / `getVersion` / `isAvailable` / `main` |
| Spring 自动装配、配置绑定（`@ConfigurationProperties` 等） | 源码未提供；本模块为普通 jar，无任何 Spring 依赖 |
