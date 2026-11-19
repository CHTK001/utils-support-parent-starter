# utils-support-ffmpeg-rust-starter

> 基于 **Rust 原生库（JNI）** 的 `FFmpegProcessor` SPI 实现：把转码、截帧、拼接、推流/拉流、媒体信息查询等音视频操作，经 `FFmpegNativeSpi` 契约转发给预编译的 Rust ffmpeg 动态库。Rust/JNI 绑定由 `utils-support-native-ffmpeg` 提供，本模块只做 Java 侧的 API 翻译、返回码转异常与 SPI 注册。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`）｜包名：`com.chua.ffmpeg.rust.support.processor`

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
    <artifactId>utils-support-ffmpeg-rust-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 实现 | `FFmpegProcessor` 的 SPI 实现（名称 `rust`，别名 `native`，`order = 100`），实现类 `RustFFmpegProcessor` | 不包含 Rust/JNI 代码与动态库（它们在 `utils-support-native-ffmpeg`） |
| 调用方式 | 通过 `FFmpegNativeSpi` 契约 + `NativeServices` 查找 native 实现；原生返回码非 0 时转成 `IOException` | 不封装 ffmpeg 命令行；`execute(String...)` 明确返回不支持 |
| 形态 | 纯 SPI 模块：无 Spring 自动装配、无配置文件，引入即用 | 不提供 HTTP/RPC 端点，不做业务编排 |
| 流式 | `convertVideo(InputStream, OutputStream, ...)` 通过临时文件中转实现 | 无边读边转码的流式管道 |

### 1.3 模块结构

```text
src/main/java/com/chua/ffmpeg/rust/support/processor/
├── RustFFmpegProcessor.java   # FFmpegProcessor 实现，@Spi({"rust","native"}, order=100)
└── NativeFFmpeg.java          # 包私有 native 门面：FFmpegNativeSpi 查找 + 帧回调适配
src/main/resources/
├── META-INF/extensions/com.chua.common.support.media.ffmpeg.FFmpegProcessor  # rust=...RustFFmpegProcessor
└── META-INF/native-image/com.chua/utils-support-ffmpeg-rust-starter/         # reflect-config.json / resource-config.json
```

---

## 2. 核心 API 与速查表

统一入口：`ServiceProvider.of(FFmpegProcessor.class).getExtension("rust")`。

错误约定：所有处理方法先做可用性检查，native 库未加载时抛 `IOException`（`getDuration` 抛 `UnsupportedOperationException`；`getVersion` 经 `NativeServices.require` 抛 `IllegalStateException`）；原生调用返回非 0 时抛 `IOException("... failed with code: N")`；`execute` 不抛异常，返回 `success=false` 的 `FFmpegResult`。

| 需求 | 方法 | 说明 |
| --- | --- | --- |
| 视频转码 | `convertVideo(File input, File output, String targetFormat)` / `convertVideo(File, File, String, FFmpegOptions)` | options 可指定 videoCodec / audioCodec / width / height / fps |
| 流式视频转码 | `convertVideo(InputStream inputStream, OutputStream outputStream, String inputFormat, String outputFormat)` | 内部经临时文件（`ffmpeg_rust_input_*` / `ffmpeg_rust_output_*`）中转 |
| 音频转码 | `convertAudio(File input, File output, String targetFormat)` / `convertAudio(File, File, String, FFmpegOptions)` | 按 removeVideo=true 调用原生转码 |
| 提取音频 | `extractAudio(File videoInput, File audioOutput, String audioFormat)` | removeVideo=true |
| 截取单帧 | `captureFrame(File videoInput, File imageOutput, double timestamp)` | timestamp 单位秒，内部换算为毫秒 |
| 按间隔截多帧 | `captureFrames(File videoInput, File outputDir, double interval, String imageFormat)` | 输出文件名 `frame_%06d.<ext>`（ext 缺省 `jpg`）；依赖 `getDuration` |
| 生成缩略图 | `generateThumbnail(File videoInput, File imageOutput, int width, int height)` | 当前实现取第 0 秒帧（`captureFrame(..., 0)`），width/height 未参与缩放 |
| 视频裁剪 | `trim(File input, File output, double startTime, double duration)` | 单位秒 |
| 视频拼接 | `concat(File[] inputs, File output)` | 多个输入路径以 `;` 连接后交给原生 |
| 视频缩放 | `resize(File input, File output, int width, int height)` | |
| 视频旋转 | `rotate(File input, File output, int angle)` | 接口约定角度 90/180/270 |
| 添加水印 | `addWatermark(File videoInput, File watermarkFile, File output, int x, int y)` | 水印图片 + 左上角坐标 |
| 视频转 GIF | `videoToGif(File videoInput, File gifOutput, double startTime, double duration, int width, int fps)` | |
| 图片序列转视频 | `imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern)` | `imagePattern` 为空时用 `%06d.jpg` |
| RTMP 推流 | `pushStream(String input, String streamUrl, FFmpegOptions options)`；带帧回调重载 `pushStream(input, streamUrl, options, Consumer<FrameInfo>)`；`pushStreamWithFrames(...)` 同义 | |
| 拉流保存 | `pullStream(String streamUrl, File output, double duration)`；带帧回调重载 `pullStream(streamUrl, output, duration, Consumer<FrameInfo>)`；`pullStreamWithFrames(...)` 同义 | duration 单位秒 |
| 媒体信息 | `getMediaInfo(File input)` → `FFmpegMediaInfo` | 原生返回 JSON，本模块用 Jackson 解析 |
| 视频时长 | `getDuration(File input)` → `double` | 单位秒；原生返回负数视为失败 |
| 可用性 | `isAvailable()` | native 库是否加载成功 |
| 版本 | `getVersion()` | 原生库版本串 |
| 自定义命令 | `execute(String... args)` | **不支持**：返回 `success=false` 的 `FFmpegResult`，提示改用 javacv-starter / jaffree-starter |

数据模型速记：

- `FFmpegOptions` 中被本实现读取的字段：`videoCodec`、`audioCodec`、`width`、`height`、`fps`；其余字段（码率、quality、preset、pixelFormat、hwaccel、mute、timeout 等）本实现不消费。
- `FrameInfo`（帧回调负载）：`frameNumber`、`timestampMs`、`width`、`height`、`codec`、`fps`、`keyFrame`。
- `FFmpegMediaInfo`：`filename`、`formatName`、`formatLongName`、`duration`、`size`、`bitrate`，以及 `videoStream`（index/codec/codecLongName/width/height/fps/bitrate/duration）与 `audioStream`（index/codec/codecLongName/sampleRate/channels/channelLayout/bitrate/duration）。

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<!-- 1) 本模块：FFmpegProcessor 的 rust SPI 实现 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ffmpeg-rust-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- 2) 运行期 native 绑定（内含各平台预编译动态库）；缺失时所有操作报错 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-native-ffmpeg</artifactId>
    <version>4.0.0.43</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.media.ffmpeg.FFmpegMediaInfo;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.spi.ServiceProvider;

import java.io.File;

public class FFmpegRustDemo {

    public static void main(String[] args) throws Exception {
        // 按名字取 rust 实现
        FFmpegProcessor ffmpeg = ServiceProvider.of(FFmpegProcessor.class).getExtension("rust");

        // native 库未加载（例如缺 utils-support-native-ffmpeg）时为 false
        if (!ffmpeg.isAvailable()) {
            System.out.println("Rust ffmpeg native 库不可用");
            return;
        }

        File input = new File("demo.avi");

        // 视频转码：avi -> mp4
        ffmpeg.convertVideo(input, new File("demo.mp4"), "mp4");

        // 截取第 5 秒的一帧
        ffmpeg.captureFrame(input, new File("frame-5s.jpg"), 5.0);

        // 媒体信息与时长
        FFmpegMediaInfo info = ffmpeg.getMediaInfo(input);
        System.out.println("format=" + info.getFormatName()
                + ", duration=" + ffmpeg.getDuration(input) + "s");
    }
}
```

---

## 4. 配置项

**无。** 依据：

- `src/main/resources` 下只有 `META-INF/extensions/` 与 `META-INF/native-image/`，没有 `application.yml`、`spring.factories` 或 `AutoConfiguration.imports`；
- `pom.xml` 未引入任何 Spring 依赖——本模块不是 Spring Boot starter，不存在配置键；
- 唯一的可调参数是调用方法时传入的 `FFmpegOptions`（本实现读取 `videoCodec` / `audioCodec` / `width` / `height` / `fps`）以及各方法签名上的显式参数。

---

## 5. SPI 扩展点

### 5.1 本模块注册的 SPI（作为实现方）

- 接口：`com.chua.common.support.media.ffmpeg.FFmpegProcessor`（定义于 `utils-support-ffmpeg-starter`）。
- 实现：`com.chua.ffmpeg.rust.support.processor.RustFFmpegProcessor`。
- 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.media.ffmpeg.FFmpegProcessor`：

  ```text
  rust=com.chua.ffmpeg.rust.support.processor.RustFFmpegProcessor
  ```

- 类注解：`@Spi(value = {"rust", "native"}, order = 100)`——SPI 名称 `rust`、别名 `native`、优先级 100。
- 获取方式：`ServiceProvider.of(FFmpegProcessor.class).getExtension("rust")`。

### 5.2 本模块消费的 SPI（作为使用方）

- 契约：`com.chua.common.support.nativeapi.FFmpegNativeSpi`（common-starter 的 nativeapi 契约层），覆盖推流/拉流（含帧回调）、转码、截帧、拼接、旋转、水印、图片序列转视频、时长与媒体信息查询。
- 实现：由 `com.chua:utils-support-native-ffmpeg` 提供，经其 `META-INF/services/com.chua.common.support.nativeapi.FFmpegNativeSpi` 注册。
- 查找入口：本模块的包私有门面 `NativeFFmpeg` → `NativeServices.get(FFmpegNativeSpi.class)` / `NativeServices.require(...)`。
- 缺失/加载失败时的行为：`isAvailable()` 返回 `false`；各操作抛 `IOException`，错误文案提示「请自行添加依赖 com.chua:utils-support-native-ffmpeg」。

### 5.3 想替换或新增一个 FFmpegProcessor

实现 `FFmpegProcessor` 接口，并在自己模块的 `META-INF/extensions/com.chua.common.support.media.ffmpeg.FFmpegProcessor` 中登记 `<名称>=<实现类全限定名>`，即可与本实现并存、按名称选择。本模块自身不提供除此之外的扩展点。

---

## 6. 构建与依赖

编译依赖（`pom.xml`）：

| 依赖 | 用途 |
| --- | --- |
| `com.chua:utils-support-ffmpeg-starter` | `FFmpegProcessor` / `FFmpegOptions` / `FFmpegMediaInfo` / `FrameInfo` / `FFmpegResult` 等契约与模型 |
| `com.chua:utils-support-common-starter` | `@Spi` 注解、`ServiceProvider`、`NativeServices` / `FFmpegNativeSpi`、`StringUtils`、`Wraps` |
| `com.fasterxml.jackson.core:jackson-databind` | 解析原生返回的媒体信息 JSON |
| `org.projectlombok:lombok`（provided） | 编译期注解（`@Slf4j`） |

运行期依赖：

| 依赖 | 用途 |
| --- | --- |
| `com.chua:utils-support-native-ffmpeg`（属 `utils-support-native-parent`） | Rust cdylib 的 JNI 绑定。内置预编译动态库：`native/windows-x86_64/ffmpeg_rust.dll`、`native/linux-x86_64/libffmpeg_rust.so`、`native/darwin-x86_64/libffmpeg_rust.dylib`、`native/darwin-aarch64/libffmpeg_rust.dylib`；加载时按 `*ffmpeg_rust*` 从 classpath 提取到临时目录 `chua_native_ffmpeg` |

构建命令（与全仓一致）：

```bash
mvn -o -q compile -DskipTests
```

GraalVM native-image：`src/main/resources/META-INF/native-image/com.chua/utils-support-ffmpeg-rust-starter/` 提供 `reflect-config.json` 与 `resource-config.json`；其中 resource 规则包含 `META-INF/extensions/.*`，保证 native 镜像下 SPI 注册文件仍可读取。

---

## 7. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| Rust/JNI 实现与预编译动态库（`ffmpeg_rust.dll` / `.so` / `.dylib`） | `utils-support-native-ffmpeg`（`utils-support-native-parent` 仓库） |
| `FFmpegProcessor` 接口与 `FFmpegOptions` / `FFmpegMediaInfo` / `FrameInfo` / `FFmpegResult` 模型 | `utils-support-ffmpeg-starter` |
| ffmpeg CLI 风格的命令执行（`execute(String...)`） | 本模块明确不支持；需要 CLI 风格操作时按源码提示改用 `javacv-starter` / `jaffree-starter` |
| 基于 JavaCV 的另一套 FFmpeg 处理器实现 | `utils-support-ffmpeg-javacv-starter`（同属 `utils-support-filesystem-parent`） |
| 音视频业务编排（转码队列、切片、鉴权、存储等） | 上层业务模块；本模块只提供单次处理原语 |
| `FFmpegOptions` 的 `videoBitrate` / `audioBitrate` / `quality` / `preset` / `pixelFormat` / `hwaccel` / `mute` / `timeout` 等参数 | 字段存在于 `FFmpegOptions`，但本实现不读取、不会传给原生层 |
