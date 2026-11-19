# utils-support-ffmpeg-starter

> utils 体系的 **FFmpeg 音视频处理模块**：提供 `FFmpegProcessor` SPI 的 Jaffree 实现（视频/音频格式转换、提取音频、截帧、缩略图、裁剪、拼接、缩放、旋转、水印、GIF、图片序列转视频、RTMP 推拉流、媒体信息读取），以及 `FileSystem` SPI 的 `video` 实现（`VideoFileSystem` / `VideoReadBuilder` / `VideoWriteBuilder`）和纯 Java 屏幕采集器 `RobotScreenCapture`。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`，`<release>25</release>`）｜包名：`com.chua.common.support.media.ffmpeg`、`com.chua.common.support.media.codec`、`com.chua.ffmpeg.support.processor`、`com.chua.ffmpeg.support.filesystem`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. FFmpegOptions 选项](#4-ffmpegoptions-选项)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ffmpeg-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `FFmpegProcessor` SPI 的 `jaffree` 实现 + `FileSystem` SPI 的 `video` 实现 + 屏幕采集接口与 Robot 实现 | 不是 Spring Boot starter（模块无任何 Spring 依赖，无自动装配） |
| 能力 | 命令行级 FFmpeg 封装：转码、提取音频、截帧、缩略图、trim/concat/resize/rotate/水印/GIF、图片序列转视频、RTMP 推拉流、ffprobe 媒体信息 | 不分发 ffmpeg 二进制；运行期需要系统里能找到 `ffmpeg` / `ffprobe` 可执行文件 |
| 依赖 | 依赖 `utils-support-common-starter` + `jaffree` + `javacv`（屏幕采集的 `Frame` 表示），另声明 `ffmpeg-platform` 配套依赖（见 7.2） | 不依赖数据库、AI SDK 等上层业务能力 |

**判断口径**：命令行级的 ffmpeg 调用能力放本模块；帧级 JavaCV 编解码、NVIDIA 硬编解码（NVENC/NVDEC）、gdigrab 屏幕采集在 `utils-support-ffmpeg-javacv-starter`；Rust JNI 原生实现在 `utils-support-ffmpeg-rust-starter`；中性的视频编解码模型（`VideoEncoder` / `VideoDecoder` 等）在 `utils-support-common-starter` 的 `codec.video`。

### 1.3 类地图

| 包 | 类型 | 说明 |
| --- | --- | --- |
| `com.chua.common.support.media.ffmpeg` | `FFmpegProcessor` | ffmpeg 处理器 SPI 接口，定义音视频处理核心操作 |
| | `FFmpegOptions` | 处理选项：编码器、码率、分辨率、帧率、CRF、preset 等 |
| | `FFmpegMediaInfo` | 媒体元数据（内含 `VideoStream` / `AudioStream` 两个内部类） |
| | `FFmpegResult` | 自定义命令执行结果：`success` / `exitCode` / `stdout` / `stderr` / `executionTime` |
| | `FrameInfo` | 推流/拉流回调的帧信息（帧号、时间戳、分辨率、是否关键帧、JPEG 图像数据） |
| `com.chua.common.support.media.codec` | `ScreenCature` | 屏幕采集 SPI 接口（`init` / `grabFrame` / `getWidth` / `getHeight` / `close`，`extends AutoCloseable`） |
| | `RobotScreenCapture` | 基于 `java.awt.Robot` 的纯 Java 实现，SPI 名称 `robot`，无 native 依赖，跨平台 |
| `com.chua.ffmpeg.support.processor` | `AbstractFFmpegProcessor` | 抽象基类：ffmpeg/ffprobe 可执行文件查找 + 通用参数构建（`buildCodecArgs`） |
| | `JaffreeFFmpegProcessor` | Jaffree 实现，SPI 名称 `jaffree` |
| `com.chua.ffmpeg.support.filesystem` | `VideoFileSystem` | `FileSystem` SPI 实现，SPI 名称 `video`，内部经 `ServiceProvider` 取 `jaffree` 处理器 |
| | `VideoReadBuilder` | 视频读取构建器（`extends ReadBuilder`）：`info()` / `duration()` |
| | `VideoWriteBuilder` | 视频写入构建器（`extends WriteBuilder`）：链式配置后 `write(File inputFile)` 执行转换 |

以上类型均 `@since 4.0.0.42`。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿处理器 | `ServiceProvider.of(FFmpegProcessor.class).getExtension("jaffree")` | 返回 `FFmpegProcessor`；先 `isAvailable()` 判断 ffmpeg 是否可用 |
| 视频格式转换 | `convertVideo(input, output, "mp4")` | 重载带 `FFmpegOptions`；另有流式 `convertVideo(InputStream, OutputStream, inputFormat, outputFormat)` |
| 音频转换/提取 | `convertAudio(...)` / `extractAudio(video, audioOut, "mp3")` | 提取音频内部加 `-vn` |
| 截帧/缩略图 | `captureFrame(video, img, timestamp)`、`captureFrames(video, dir, interval, "png")`、`generateThumbnail(video, img, w, h)` | `captureFrames` 按 `-vf fps=1/间隔` 输出 `frame_%05d.<格式>` 并返回文件数组；`generateThumbnail` 取视频正中间一帧 |
| 媒体信息/时长 | `getMediaInfo(file)` → `FFmpegMediaInfo`、`getDuration(file)` → `double` | 底层 ffprobe `show_streams + show_format` |
| 剪辑/拼接/几何变换 | `trim` / `concat` / `resize` / `rotate`（90/180/270）/ `addWatermark` | `trim`、`concat`、`pullStream` 均用 `-c copy` 流拷贝；`rotate` 只接受 90/180/270，否则抛 `IllegalArgumentException` |
| GIF 与图片序列 | `videoToGif(video, gif, startTime, duration, width, fps)`、`imagesToVideo(dir, out, fps, pattern)` | GIF 滤镜 `fps=..,scale=宽:-1:flags=lanczos`；序列转视频固定 `libx264 + yuv420p` |
| 推流/拉流 | `pushStream(input, url, options)` / `pullStream(url, output, duration)` | `pushStream` 在 URL 以 `rtmp://` 开头时输出格式置为 `flv`；两者均有带 `Consumer<FrameInfo>` 回调的重载与 `*WithFrames` 变体 |
| 执行自定义命令 | `execute(String... args)` → `FFmpegResult` | 成功 `exitCode=0`，失败 `-1`；本实现不填充 `stdout` / `stderr` |
| 视频文件系统 | `FileSystem.create("video")` 或 `new VideoFileSystem()` | `read(file)` → `VideoReadBuilder.info()/duration()`；`write(file)` → `VideoWriteBuilder` 链式转换 |
| 屏幕采集 | `new RobotScreenCapture()` 或 `ServiceProvider.of(ScreenCature.class)` | `init(w, h, fps)` 后 `grabFrame()` 逐帧采集，YUV420P `Frame` |

### 2.2 `FFmpegProcessor` 全量方法

```java
public interface FFmpegProcessor {
    void convertVideo(File input, File output, String targetFormat) throws IOException;
    void convertVideo(File input, File output, String targetFormat, FFmpegOptions options) throws IOException;
    void convertVideo(InputStream inputStream, OutputStream outputStream,
                      String inputFormat, String outputFormat) throws IOException;
    void convertAudio(File input, File output, String targetFormat) throws IOException;
    void convertAudio(File input, File output, String targetFormat, FFmpegOptions options) throws IOException;
    void extractAudio(File videoInput, File audioOutput, String audioFormat) throws IOException;
    void captureFrame(File videoInput, File imageOutput, double timestamp) throws IOException;
    File[] captureFrames(File videoInput, File outputDir, double interval, String imageFormat) throws IOException;
    void generateThumbnail(File videoInput, File imageOutput, int width, int height) throws IOException;
    void trim(File input, File output, double startTime, double duration) throws IOException;
    void concat(File[] inputs, File output) throws IOException;
    void resize(File input, File output, int width, int height) throws IOException;
    void rotate(File input, File output, int angle) throws IOException;
    void addWatermark(File videoInput, File watermarkFile, File output, int x, int y) throws IOException;
    void videoToGif(File videoInput, File gifOutput, double startTime, double duration, int width, int fps) throws IOException;
    void imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern) throws IOException;
    void pushStream(String input, String streamUrl, FFmpegOptions options) throws IOException;
    void pushStream(String input, String streamUrl, FFmpegOptions options, Consumer<FrameInfo> callback) throws IOException;   // default
    void pushStreamWithFrames(String input, String streamUrl, FFmpegOptions options, Consumer<FrameInfo> callback) throws IOException; // default
    void pullStream(String streamUrl, File output, double duration) throws IOException;
    void pullStream(String streamUrl, File output, double duration, Consumer<FrameInfo> callback) throws IOException;           // default
    void pullStreamWithFrames(String streamUrl, File output, double duration, Consumer<FrameInfo> callback) throws IOException; // default
    FFmpegMediaInfo getMediaInfo(File input) throws IOException;
    double getDuration(File input) throws IOException;
    boolean isAvailable();
    String getVersion();                       // jaffree 实现返回 "Jaffree FFmpeg Processor"
    FFmpegResult execute(String... args) throws IOException;
}
```

> 处理器不可用（`isAvailable() == false`）时，各处理方法抛 `IllegalStateException("FFmpeg unavailable: " + loadError)`；例外：`concat` 在不可用或无输入时直接返回，不抛异常。

### 2.3 数据模型

- `FFmpegMediaInfo`：`filename`、`formatName`、`formatLongName`、`duration`（秒）、`size`（字节）、`bitrate`（bps）、`videoStream`、`audioStream`。
  - `VideoStream`：`index`、`codec`、`codecLongName`、`width`、`height`、`fps`、`bitrate`、`duration`。
  - `AudioStream`：`index`、`codec`、`codecLongName`、`sampleRate`、`channels`、`channelLayout`、`bitrate`、`duration`。
- `FrameInfo`：`frameNumber`（从 0 起）、`timestampMs`、`width`、`height`、`codec`、`fps`、`keyFrame`、`imageData`（JPEG 字节）。
  - 注意：Jaffree 实现基于命令行调用，`*WithFrames` 变体也只通过进度监听提供帧号与时间戳，`imageData` 保持 `null`（源码注释明确说明无法获取帧图像数据）。
- `FFmpegResult`：`success`、`exitCode`、`stdout`、`stderr`、`executionTime`（毫秒）。

---

## 3. 快速开始

### 3.1 前置条件

系统需已安装 FFmpeg：本模块在构造处理器时按 `PATH` 环境变量查找 `ffmpeg` 与 `ffprobe`（Windows 找 `ffmpeg.exe` / `ffprobe.exe`），找不到再查 `/usr/bin`、`/usr/local/bin`、`/opt/homebrew/bin`、`C:\ffmpeg\bin`、`C:\Program Files\ffmpeg\bin`。都找不到则 `isAvailable() == false`。

### 3.2 最短可运行示例：转码 + 读媒体信息

```java
import com.chua.common.support.media.ffmpeg.FFmpegMediaInfo;
import com.chua.common.support.media.ffmpeg.FFmpegOptions;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.spi.ServiceProvider;

import java.io.File;

FFmpegProcessor ffmpeg = ServiceProvider.of(FFmpegProcessor.class).getExtension("jaffree");
if (ffmpeg == null || !ffmpeg.isAvailable()) {
    throw new IllegalStateException("未找到可用的 ffmpeg/ffprobe");
}

// 1. 视频格式转换（web 优化预设：libx264 + aac + crf23 + fast + yuv420p）
ffmpeg.convertVideo(new File("in.avi"), new File("out.mp4"), "mp4", FFmpegOptions.webOptimized());

// 2. 读取媒体信息与时长
FFmpegMediaInfo info = ffmpeg.getMediaInfo(new File("out.mp4"));
double seconds = ffmpeg.getDuration(new File("out.mp4"));
System.out.println(info.getFormatName() + " " + info.getVideoStream().getWidth()
        + "x" + info.getVideoStream().getHeight() + " " + seconds + "s");
```

### 3.3 视频文件系统（`video` SPI）

```java
import com.chua.common.support.file.FileSystem;
import com.chua.ffmpeg.support.filesystem.VideoFileSystem;
import com.chua.ffmpeg.support.filesystem.VideoReadBuilder;

VideoFileSystem vfs = new VideoFileSystem();

// 读：媒体信息 + 时长
VideoReadBuilder read = vfs.read(new File("out.mp4"));
System.out.println(read.info().getDuration());
System.out.println(read.duration());

// 写：把 in.avi 转成 out.mp4，链式配置参数
vfs.write(new File("out.mp4"))
   .videoCodec("libx264")
   .audioCodec("aac")
   .videoBitrate("2M")
   .audioBitrate("128k")
   .fps(30)
   .resolution(1920, 1080)
   .quality(23)
   .write(new File("in.avi"));
```

也可经统一门面按 SPI 名创建：`FileSystem video = FileSystem.create("video");`。

### 3.4 屏幕采集

```java
import com.chua.common.support.media.codec.RobotScreenCapture;
import com.chua.common.support.media.codec.ScreenCature;
import org.bytedeco.javacv.Frame;

try (ScreenCature capture = new RobotScreenCapture()) {   // ScreenCature extends AutoCloseable
    capture.init(1920, 1080, 30);
    Frame frame = capture.grabFrame();   // YUV420P；image[0]=Y, image[1]=U, image[2]=V，DirectByteBuffer 零拷贝
}
```

---

## 4. FFmpegOptions 选项

`FFmpegOptions` 是**程序化选项对象**（setter/getter），不是配置文件。静态工厂：`defaultOptions()`（全默认）、`highQuality()`（`libx264` + CRF 18 + `slow` + 音频 `320k`）、`webOptimized()`（`libx264` + `aac` + CRF 23 + `fast` + `yuv420p`）。

| 字段 | 类型 | 说明（源码注释口径） |
| --- | --- | --- |
| `videoCodec` | String | 视频编码器（libx264, libx265, h264_nvenc） |
| `audioCodec` | String | 音频编码器（aac, mp3, libopus） |
| `videoBitrate` | String | 视频码率（"2M", "5000k"） |
| `audioBitrate` | String | 音频码率（"128k", "320k"） |
| `fps` | Integer | 帧率 |
| `width` / `height` | Integer | 视频宽度 / 高度（两者都有值时映射为 `scale=w:h`） |
| `audioSampleRate` | Integer | 音频采样率（44100, 48000） |
| `audioChannels` | Integer | 音频声道数 |
| `quality` | Integer | CRF 质量 0-51，越小质量越高 |
| `preset` | String | 编码预设（ultrafast, fast, medium, slow） |
| `pixelFormat` | String | 像素格式（yuv420p, yuv444p） |
| `hwaccel` | String | 硬件加速（cuda, vaapi, videotoolbox） |
| `mute` | boolean | 是否静音（`-an`） |
| `removeVideo` | boolean | 是否移除视频流（`-vn`） |
| `removeAudio` | boolean | 是否移除音频流 |
| `overwrite` | boolean | 是否覆盖输出文件，默认 `true`（`-y`） |
| `timeout` | long | 超时时间（毫秒） |

> **消费范围**：本模块 jaffree 实现的 `buildCodecArgs` 只映射 `videoCodec(-c:v)`、`audioCodec(-c:a)`、`videoBitrate(-b:v)`、`audioBitrate(-b:a)`、`fps(-r)`、`width+height(-vf scale)`、`audioSampleRate(-ar)`、`audioChannels(-ac)`、`quality(-crf)`、`preset(-preset)`、`overwrite(-y)`、`mute(-an)`、`removeVideo(-vn)`；`pixelFormat`、`hwaccel`、`removeAudio`、`timeout` 四个字段在当前实现中未被消费。`pushStream` 只读取 `videoCodec` 与 `audioCodec`。

---

## 5. 配置项

**无配置文件配置项。** 依据：

1. 本模块 `src/main/resources` 下只有 `META-INF/extensions/`（SPI 登记文件）与 `META-INF/native-image/`（GraalVM 配置），没有任何 `application*.yml` / `*.properties` 配置；
2. 源码中没有 `@ConfigurationProperties`，也没有读取配置键的代码；
3. 旧版 README 即声明「本模块为零配置模块，引入依赖后即可使用」。

唯一的运行期环境依赖是 **ffmpeg 可执行文件的查找**：读 `PATH` 环境变量（见 3.1），无其他可配置键。参数级定制请使用第 4 节的 `FFmpegOptions`（代码内传参）。

---

## 6. SPI 扩展点

### 6.1 本模块注册的实现

扩展文件位于 `src/main/resources/META-INF/extensions/`：

| SPI 接口 | 扩展文件内容 | 实现说明 |
| --- | --- | --- |
| `com.chua.common.support.media.ffmpeg.FFmpegProcessor` | `jaffree=com.chua.ffmpeg.support.processor.JaffreeFFmpegProcessor` | Jaffree 命令行封装，`@Spi("jaffree")` |
| `com.chua.common.support.file.FileSystem` | `video=com.chua.ffmpeg.support.filesystem.VideoFileSystem` | 视频文件系统，`@Spi("video")` |
| `com.chua.common.support.media.codec.ScreenCature` | `com.chua.common.support.media.codec.RobotScreenCapture` | 文件内为类名直书；实现类另标 `@Spi("robot")` |

### 6.2 如何替换/扩展

`FFmpegProcessor` 与 `ScreenCature` 都是开放 SPI：实现接口 → 加 `@Spi("名字")` → 在自己的模块建同名扩展文件登记即可，调用方用 `ServiceProvider.of(...).getExtension("名字")` 取用，无需改本模块代码。同仓已有先例：

- `utils-support-ffmpeg-javacv-starter`：注册 FFmpegProcessor key `javacv`（`JavaCVFFmpegProcessor`），并注册 `VideoEncoder` / `VideoDecoder`；
- `utils-support-ffmpeg-rust-starter`：注册 FFmpegProcessor key `rust`（`RustFFmpegProcessor`）。

---

## 7. 构建与依赖

### 7.1 编译要求

- JDK 25（父 pom `maven.compiler.source/target=25`，compiler 插件 `<release>25</release>`）；
- 源码 UTF-8、无 BOM；Lombok 由父模块以 `provided` 引入。

```bash
mvn -pl utils-support-filesystem-parent/utils-support-ffmpeg-starter -am clean package
```

### 7.2 依赖清单（来自本模块 pom.xml）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | SPI（`ServiceProvider`、`@Spi`）、`FileSystem`/`ReadBuilder`/`WriteBuilder`、`OsFamily`、`Wraps` 等契约与工具 |
| `com.github.kokorin.jaffree:jaffree` | `2024.08.29` | FFmpeg/FFprobe 命令行封装（`FFmpeg`、`FFprobe`、`UrlInput`、`UrlOutput`、`PipeInput`、`PipeOutput`） |
| `org.bytedeco:javacv` | `${javacv.version}` = `1.5.14` | 屏幕采集侧的 `Frame` 表示与 `Java2DFrameConverter` 图像转换 |
| `org.bytedeco:ffmpeg-platform` | `${javacv.ffmpeg.version}` = `7.1.1-1.5.12` | javacv 配套依赖（pom 声明；本模块源码未直接 import 其类） |

### 7.3 GraalVM native-image

`src/main/resources/META-INF/native-image/com.chua/utils-support-ffmpeg-starter/` 下含 `reflect-config.json` 与 `resource-config.json`；其中 `resource-config.json` 将 `META-INF/extensions/.*` 纳入资源包含，保证 native 镜像下 SPI 登记文件仍可读。

---

## 8. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| FFmpeg 可执行文件（ffmpeg/ffprobe 二进制） | 需系统自备（`PATH` 或常见安装路径），本模块只负责查找与调用 |
| 帧级 JavaCV 编解码、NVIDIA 硬编解码（NVENC/NVDEC）、gdigrab 屏幕采集 | `utils-support-ffmpeg-javacv-starter` |
| Rust JNI 原生 FFmpeg 处理器 | `utils-support-ffmpeg-rust-starter` |
| 中性视频编解码模型（`VideoEncoder` / `VideoDecoder` / `VideoFrame` / `VideoPacket` / `PixelFormat`） | `utils-support-common-starter` 的 `codec.video` |
| 图像处理工具（缩放/旋转/水印/GIF 帧读写等 `BufferedImageUtils`、`ImageProcessors`） | `utils-support-common-starter` |
| Spring Boot 自动装配 / application 配置绑定 | 无（本模块不是 Spring starter，见第 5 节） |
