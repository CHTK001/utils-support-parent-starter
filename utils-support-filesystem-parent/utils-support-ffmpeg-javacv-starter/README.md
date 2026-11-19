# utils-support-ffmpeg-javacv-starter

> utils 体系的**多媒体处理实现模块**：基于 JavaCV/FFmpeg 提供视频转码、截帧、缩略图、剪辑拼接、GIF、推拉流、媒体信息读取，以及 NVIDIA 硬编解码（NVENC/NVDEC）与 gdigrab 屏幕采集，全部以 SPI 实现的形式挂入 utils 体系。
>
> 版本：`4.0.0.51`｜JDK：`25`｜包名：`com.chua.ffmpeg.support.processor`、`com.chua.ffmpeg.support.codec`、`com.chua.common.support.media.capture`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ffmpeg-javacv-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `FFmpegProcessor` / `ScreenCature` / `VideoEncoder` / `VideoDecoder` 的 **JavaCV 与 NVIDIA 实现** | 不定义这些 SPI 契约（契约在 `utils-support-ffmpeg-starter` 与 `utils-support-common-starter`） |
| 能力 | 转码、抽/转音频、截帧、缩略图、剪辑、拼接、GIF、图片合成视频、推拉流、媒体信息读取；NVENC/NVDEC 硬编硬解；gdigrab 屏幕采集 | 不支持旋转、水印、自定义 ffmpeg 命令（调用即抛 `UnsupportedOperationException`） |
| 依赖 | 携带 `org.bytedeco:javacv` 与 `ffmpeg-platform` 原生绑定 | 不依赖 Spring、无持久层、无 Web 端点、零配置 |

### 1.3 类清单

全部源码共 5 个类（均 `@since 4.0.0.42`）：

| 类 | 包 | 职责 |
| --- | --- | --- |
| `JavaCVFFmpegProcessor` | `com.chua.ffmpeg.support.processor` | `FFmpegProcessor` 的 JavaCV 实现，SPI 名 `javacv`，order 50 |
| `H264NvencVideoEncoder` | `com.chua.ffmpeg.support.codec` | 基于 ffmpeg `h264_nvenc` 的 H.264 硬件编码器，SPI 名 `h264_nvenc` / `nvenc`，order 100 |
| `H264NvencVideoDecoder` | `com.chua.ffmpeg.support.codec` | 基于 ffmpeg `h264_cuvid` 的 H.264 硬件解码器，SPI 名 `h264_cuvid` / `nvdec`，order 100 |
| `NvCodecProbe` | `com.chua.ffmpeg.support.codec` | NV 编解码可用性探针（模块内私有工具类） |
| `JavaCVScreenCapture` | `com.chua.common.support.media.capture` | 基于 gdigrab 的屏幕采集器，SPI 名 `javacv` |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 视频转码 | `FFmpegProcessor`（SPI 名 `javacv`）→ `convertVideo(...)` | 任意 ffmpeg 可解码输入 → 指定封装格式，默认 H.264 + AAC |
| 提取 / 转换音频 | `extractAudio(...)` / `convertAudio(...)` | 从视频抽取音轨（输出 MP3 编码），或音频格式转换 |
| 截图 / 批量截帧 | `captureFrame(...)` / `captureFrames(...)` | 按时间点取一帧存为图片；或按秒间隔批量导出 |
| 生成缩略图 | `generateThumbnail(...)` | 取视频中间帧，缩放到指定宽高 |
| 剪辑 / 拼接 | `trim(...)` / `concat(...)` | 按起始时间 + 时长剪辑；多个视频顺序拼接 |
| 视频转 GIF / 图片合成视频 | `videoToGif(...)` / `imagesToVideo(...)` | 片段转 GIF；图片目录按文件名模式合成视频 |
| 推流 / 拉流 | `pushStream(...)` / `pullStream(...)` | `rtmp://` 目标自动用 flv 封装；拉流可定时录制并逐帧回调 |
| 媒体信息 / 时长 | `getMediaInfo(...)` / `getDuration(...)` | 封装格式、视频/音频流参数、码率、时长（秒） |

> NVIDIA 硬编解码与屏幕采集的入口见 [2.3](#23-nvidia-硬编解码h264nvencvideoencoder--h264nvencvideodecoder) 与 [2.4](#24-屏幕采集javacvscreencapture)；SPI 名称总表见 [第 5 节](#5-spi-扩展点)。

### 2.2 JavaCVFFmpegProcessor

实现 `com.chua.common.support.media.ffmpeg.FFmpegProcessor`。类加载时把 ffmpeg 日志级别设为 `AV_LOG_ERROR`；若原生库加载失败，`isAvailable()` 返回 `false`，各方法抛 `IllegalStateException`（信息为「JavaCV FFmpeg不可用: 原因」）。

| 方法 | 说明 |
| --- | --- |
| `convertVideo(File, File, String targetFormat)` | 视频转码；默认视频编码 H.264、音频编码 AAC，分辨率/帧率沿用源 |
| `convertVideo(File, File, String, FFmpegOptions)` | 带参数转码：可覆盖宽、高、帧率、视频/音频编码器、质量 |
| `convertVideo(InputStream, OutputStream, String inFormat, String outFormat)` | 流式转码（经临时文件中转） |
| `extractAudio(File video, File audio, String audioFormat)` | 抽取音轨，输出编码固定 MP3 |
| `convertAudio(File, File, String targetFormat[, FFmpegOptions])` | 音频转码，可覆盖声道数、采样率、编码器 |
| `captureFrame(File video, File image, double timestamp)` | 按时间戳（秒）截取一帧；图片格式取输出扩展名，缺省 jpg |
| `captureFrames(File video, File dir, double interval, String fmt)` | 按间隔批量截帧，输出 `frame_00000.<fmt>` 起，返回 `File[]` |
| `generateThumbnail(File video, File image, int w, int h)` | 取中间帧生成缩略图（SCALE_SMOOTH） |
| `trim(File, File, double startTime, double duration)` | 剪辑：从 startTime 秒起保留 duration 秒 |
| `concat(File[] inputs, File output)` | 顺序拼接多个视频，以第一个文件的流参数为准 |
| `resize(File, File, int w, int h)` | 调整分辨率（内部走 `convertVideo`） |
| `videoToGif(File, File gif, double start, double dur, int width, int fps)` | 视频片段转 GIF，高度按源宽高比自动推算 |
| `imagesToVideo(File imageDir, File video, int fps, String pattern)` | 图片目录按文件名正则（`%` 通配）排序后合成视频 |
| `pushStream(String input, String url, FFmpegOptions)` | 把输入推到 `url`；`rtmp://` 自动用 `flv` 封装，否则按扩展名 |
| `pushStream(String, String, FFmpegOptions, Consumer<FrameInfo>)` | 推流同时逐帧回调 `FrameInfo` |
| `pushStreamWithFrames(String, String, FFmpegOptions, Consumer<FrameInfo>)` | 同上，且回调带 JPEG 帧数据（`imageData`） |
| `pullStream(String url, File output, double duration)` | 拉流录制到文件；`duration` 秒后停止，≤0 表示不限时 |
| `pullStream(String, File, double, Consumer<FrameInfo>)` / `pullStreamWithFrames(...)` | 拉流 + 逐帧回调（`WithFrames` 变体额外带 JPEG 帧数据） |
| `getMediaInfo(File)` | 返回 `FFmpegMediaInfo`：封装格式、时长、大小、总码率、视频流（宽高/帧率/编码/码率）、音频流（声道/采样率/编码/码率） |
| `getDuration(File)` | 时长（秒） |
| `isAvailable()` / `getVersion()` | 原生库可用性；版本串为 `"JavaCV-based FFmpeg Processor"` |

`FFmpegOptions` 在本模块中消费的字段：`width`、`height`、`fps`、`videoCodec`、`audioCodec`、`quality`、`audioChannels`、`audioSampleRate`；`FFmpegOptions.defaultOptions()` 提供全默认值。回调对象 `FrameInfo` 携带：`frameNumber`、`timestampMs`、`width`、`height`、`codec`、`fps`、`keyFrame`（以及可选 `imageData`）。

### 2.3 NVIDIA 硬编解码（H264NvencVideoEncoder / H264NvencVideoDecoder)

编码器走 ffmpeg `h264_nvenc`，解码器走 `h264_cuvid`，均逐帧/逐包增量交付、不经容器层。可用性由 `NvCodecProbe` 判定：**真的打开一次 64×64 最小会话**（而非仅查编解码器是否编译进去），结果按编解码器名缓存。

编码器默认参数（`VideoCodecOptions` 未指定时）：

| 项 | 默认值 |
| --- | --- |
| 分辨率 | 1280 × 720 |
| 帧率 | 30 fps |
| 码率 | 4000 kbps |
| GOP | 60 |
| preset / tune | `p5` / `hq` |
| B 帧 | 0 |

行为约定：

- 输入仅接受 `PixelFormat.YUV420P` 与 `PixelFormat.NV12`；YUV420P 会在送入前把 U/V 交织成 NV12 单平面。
- 透传给 nvenc 的附加选项仅限：`rc`、`delay`、`gpu`、`b_adapt`、`spatial_aq`、`temporal_aq`；`profile`、`level`、`cq` 不透传（在该绑定上无效）。
- `qmin`/`qmax` 不支持：`configure` 时直接抛 `CodecException`，码控请用 `rc` 选项。
- 实例持有原生编码上下文，**非线程安全**，一路编码一个实例；用完 `close()` 释放。
- 解码器输出 NV12 或 YUV420P 的紧致平面帧（直接内存缓冲）；会话时基固定为 `1/fps`，与编码器构成对称闭环。
- 解码器不做软解兜底：环境没有 NVDEC 时 `isAvailable()` 返回 `false`，调用方应改用其它 `VideoDecoder` 实现。

```java
VideoEncoder encoder = ServiceProvider.of(VideoEncoder.class).getNewExtension("h264_nvenc");
if (encoder != null && encoder.isAvailable()) {
    encoder.configure(new VideoCodecOptions());          // 默认 1280x720@30fps、4000kbps
    List<VideoPacket> packets = encoder.encode(frame);   // frame 需为 YUV420P / NV12
    encoder.flush();
    encoder.close();
}
```

### 2.4 屏幕采集（JavaCVScreenCapture）

实现 `com.chua.common.support.media.codec.ScreenCature`，使用 ffmpeg 的 `gdigrab` 设备（`desktop`）采集 Windows 桌面：

- `init(width, height, fps)`：fps 钳制到 1–60，设置 `probesize=1M`、`analyzeduration=0`；失败时返回 `false` 并释放资源。
- `grabFrame()`：最多重试 3 次（间隔 10ms）取有效帧；对单平面 BGR/BGRA 数据做一次深拷贝并复用缓冲，避免每帧约 2.7MB 的堆分配。返回的 Frame 供下游 `FFmpegFrameRecorder.record(Frame)` 同步消费（单线程 grab → encode 安全）。
- `getWidth()` / `getHeight()` / `close()`：实际采集尺寸与释放。

---

## 3. 快速开始

```java
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.spi.ServiceProvider;

import java.io.File;

// 通过 SPI 获取 JavaCV 实现（SPI 名：javacv）
FFmpegProcessor ffmpeg = ServiceProvider.of(FFmpegProcessor.class).getExtension("javacv");

// 视频转码：avi -> mp4（默认 H.264 + AAC）
ffmpeg.convertVideo(new File("input.avi"), new File("output.mp4"), "mp4");

// 截取第 1 秒画面为 jpg
ffmpeg.captureFrame(new File("output.mp4"), new File("cover.jpg"), 1.0);

// 读取时长（秒）
double seconds = ffmpeg.getDuration(new File("output.mp4"));
```

也可以直接实例化：`FFmpegProcessor ffmpeg = new JavaCVFFmpegProcessor();`（`com.chua.ffmpeg.support.processor.JavaCVFFmpegProcessor`）。

带参数的转码示例：

```java
FFmpegOptions options = new FFmpegOptions();
options.setWidth(1280);
options.setHeight(720);
options.setFps(25);
ffmpeg.convertVideo(new File("input.avi"), new File("output.mp4"), "mp4", options);
```

---

## 4. 配置项

**无。**

依据：模块 `pom.xml` 只声明依赖，无任何配置插件；`src/main/resources` 下仅有 `META-INF/extensions/` SPI 注册文件与 `META-INF/native-image/` 配置，不存在 `application*.yml` / `*.properties` 等配置文件，源码中也没有 `@ConfigurationProperties` 之类的配置绑定。运行期参数全部通过方法入参传递（`FFmpegOptions`、`VideoCodecOptions`）。

---

## 5. SPI 扩展点

本模块是**实现提供方**：为 `utils-support-ffmpeg-starter` 与 `utils-support-common-starter` 定义的 4 个 SPI 接口提供实现，注册文件位于 `src/main/resources/META-INF/extensions/`：

| SPI 接口 | 注册名 | 实现类 | order |
| --- | --- | --- | --- |
| `com.chua.common.support.media.ffmpeg.FFmpegProcessor` | `javacv` | `com.chua.ffmpeg.support.processor.JavaCVFFmpegProcessor` | 50 |
| `com.chua.common.support.media.codec.ScreenCature` | （无名注册） | `com.chua.common.support.media.capture.JavaCVScreenCapture` | — |
| `com.chua.common.support.codec.video.VideoEncoder` | `h264_nvenc` | `com.chua.ffmpeg.support.codec.H264NvencVideoEncoder` | 100 |
| `com.chua.common.support.codec.video.VideoDecoder` | `h264_cuvid` | `com.chua.ffmpeg.support.codec.H264NvencVideoDecoder` | 100 |

注册文件内容（与源码 `@Spi` 注解一致）：

```text
# META-INF/extensions/com.chua.common.support.media.ffmpeg.FFmpegProcessor
javacv=com.chua.ffmpeg.support.processor.JavaCVFFmpegProcessor

# META-INF/extensions/com.chua.common.support.media.codec.ScreenCature
com.chua.common.support.media.capture.JavaCVScreenCapture

# META-INF/extensions/com.chua.common.support.codec.video.VideoEncoder
h264_nvenc=com.chua.ffmpeg.support.codec.H264NvencVideoEncoder

# META-INF/extensions/com.chua.common.support.codec.video.VideoDecoder
h264_cuvid=com.chua.ffmpeg.support.codec.H264NvencVideoDecoder
```

> 编码器/解码器另有 `@SpiDescribe` 描述：`ffmpeg h264_nvenc 硬件视频编码器`、`ffmpeg h264_cuvid 硬件视频解码器`。SPI 获取方式统一走 `ServiceProvider.of(接口.class)`：无状态接口（如 `FFmpegProcessor`）用 `getExtension(名称)`；会话型编解码器（`VideoEncoder` / `VideoDecoder`，实例持有原生上下文、非线程安全）按契约用 `getNewExtension(名称)` 取独立实例，不要复用共享实例。

---

## 6. 构建与依赖

依赖（`pom.xml`）：

| 依赖 | 作用 |
| --- | --- |
| `com.chua:utils-support-ffmpeg-starter` | `FFmpegProcessor`、`FFmpegOptions`、`FFmpegMediaInfo`、`FrameInfo`、`ScreenCature` 等 SPI 契约 |
| `com.chua:utils-support-common-starter` | `VideoEncoder` / `VideoDecoder` / `VideoFrame` / `VideoPacket` / `VideoCodecOptions` / `PixelFormat` 契约与 SPI 框架 |
| `org.bytedeco:javacv`（`1.5.14`） | `FFmpegFrameGrabber` / `FFmpegFrameRecorder` / `Java2DFrameConverter` 等 JavaCV 门面 |
| `org.bytedeco:ffmpeg-platform`（`7.1.1-1.5.12`） | ffmpeg 原生绑定（全平台） |
| `org.projectlombok:lombok`（provided） | 编译期代码生成 |

> bytedeco 全家桶必须**同号**：`ffmpeg-platform` 版本末尾的 `1.5.12` 后缀代表该组原生绑定针对 javacpp `1.5.12` 编译。版本错配在编译期不可见，只在运行期抛 `NoSuchMethodError`。

构建命令（与仓库约定一致）：

```bash
mvn -o -q compile -DskipTests
```

GraalVM native-image：父 POM 提供 `native` profile（`native-maven-plugin` `1.1.14`）。本模块附带 `META-INF/native-image/` 配置——`resource-config.json` 保留 `META-INF/extensions/.*`，`reflect-config.json` 注册 `JavaCVScreenCapture` 全量反射，保证原生镜像下 SPI 与反射可用。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去向 |
| --- | --- |
| 视频旋转（`rotate`）、水印（`addWatermark`）、执行自定义 ffmpeg 命令（`execute`） | 本模块的 `JavaCVFFmpegProcessor` 对这三个方法**直接抛 `UnsupportedOperationException`**（提示改用命令行处理器）；命令行等其它 `FFmpegProcessor` 实现由同仓 `utils-support-ffmpeg-starter`（如 `JaffreeFFmpegProcessor`）提供 |
| SPI 契约定义 | `FFmpegProcessor` / `FFmpegOptions` / `FFmpegMediaInfo` / `FFmpegResult` / `FrameInfo` / `ScreenCature` 在 `utils-support-ffmpeg-starter`；`VideoEncoder` / `VideoDecoder` / `VideoFrame` / `VideoPacket` / `VideoCodecOptions` / `PixelFormat` 在 `utils-support-common-starter`。本模块只做实现 |
| NVENC/NVDEC 软编软解兜底 | 不提供。无 NVIDIA 显卡/驱动时 `isAvailable()` 为 `false`，请换用其它实现 |
| 非 Windows 屏幕采集 | `JavaCVScreenCapture` 基于 `gdigrab`（Windows 桌面设备），不覆盖其它平台 |
| 视频文件系统的读写门面 | `VideoFileSystem` / `VideoReadBuilder` / `VideoWriteBuilder` 位于 `utils-support-ffmpeg-starter`，不在本模块 |
