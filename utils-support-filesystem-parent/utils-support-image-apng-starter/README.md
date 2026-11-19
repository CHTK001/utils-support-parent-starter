# utils-support-image-apng-starter

> APNG（Animated PNG）动画图片处理模块：提供 APNG 格式检测、动画信息读取、帧提取与合成、图像优化/缩放，以及 GIF↔APNG 文件级格式转换（SPI：`gif2apng` / `apng2gif`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source`/`maven.compiler.target=25`）｜包名：`com.chua.apng.support`

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
    <artifactId>utils-support-image-apng-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 格式检测 | PNG 签名 + `acTL` 控制块探测（`ApngUtils.isApngFormat` / `validateApng`） | 不做 PNG 全量 chunk 规范校验 |
| 动画信息 | 帧数、宽高、是否动图（`ApngUtils.getApngInfo` → `ApngInfo`） | 不解析播放循环次数等 acTL 明细字段 |
| 帧操作 | 提取全部帧（`extractFrames`）、多帧合成 APNG（`createApng`，可指定每帧延迟） | 不做帧间差分压缩等编码优化 |
| 图像处理 | ARGB 优化（`optimizeImage`）、平滑缩放（`resizeImage`） | 不做水印/裁剪/滤镜（见 image-starter） |
| 格式转换 | GIF→APNG、GIF→PNG、APNG→GIF、PNG→GIF 的文件级转换（`ConvertFileSystem` SPI） | 不做 WebP/视频等其它动图格式 |
| 模块形态 | 纯 SPI 扩展模块：`META-INF/extensions` 注册 + GraalVM native-image 配置 | 不是 Spring Boot Starter，无自动配置类 |

**判断口径**：APNG/GIF 动图能力放本模块；通用图像处理在 `utils-support-image-starter` 与 common-starter 的 `BufferedImageUtils`；多格式转换的统一编排入口在 common-starter 的 `com.chua.common.support.file.converter` 包（如 `FileConvertSystemFlow`）。

### 1.3 规模

- Java 源文件 **3** 个：`ApngUtils`、`GifToApngConvertFileSystem`、`ApngToGifConvertFileSystem`。
- SPI 注册文件 **1** 个：`META-INF/extensions/com.chua.common.support.file.converter.ConvertFileSystem`。
- GraalVM `native-image` 配置 **2** 个：`reflect-config.json`（两个转换器全量反射注册）、`resource-config.json`（保留 `META-INF/extensions/.*`）。

---

## 2. 核心 API 与速查表

### 2.1 ApngUtils（`com.chua.apng.support.utils`，全静态方法）

| 方法 | 说明 |
| --- | --- |
| `boolean isApngFormat(InputStream)` | 读流头部 64 字节，校验 PNG 签名（`89 50 4E 47 0D 0A 1A 0A`）且含 `acTL`；异常返回 `false`。需要流支持 mark/reset |
| `ApngInfo getApngInfo(InputStream)` | 用 ImageIO 的 `APNG` Reader（缺省回退 `PNG`）读取帧数与首帧宽高；失败返回 `null`。会消费并关闭传入流 |
| `List<BufferedImage> extractFrames(InputStream)` | 提取全部帧；失败返回已解析出的帧（可能为空列表）。会消费并关闭传入流 |
| `byte[] createApng(List<BufferedImage> frames, List<Integer> delays)` | 多帧按 `delays`（毫秒，未给或不足时每帧默认 100ms）合成 APNG；单帧直接写静态 PNG；失败返回空数组 |
| `boolean validateApng(byte[] data)` | 校验字节数据是否为 APNG（内部走 `isApngFormat`） |
| `BufferedImage optimizeImage(BufferedImage image)` | 非 `TYPE_INT_ARGB` 图像转为 ARGB（保透明度），已是 ARGB 原样返回 |
| `BufferedImage resizeImage(BufferedImage image, int width, int height)` | `SCALE_SMOOTH` 缩放到目标尺寸；参数非法时原样返回 |

`ApngInfo` 提供 `getFrameCount()`、`getWidth()`、`getHeight()`、`isAnimated()`（帧数 > 1），`toString()` 形如 `ApngInfo{frames=3, size=320x240, animated=true}`。

### 2.2 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 判断是不是 APNG | `ApngUtils.isApngFormat(in)` | PNG 签名 + `acTL` |
| 校验字节数据 | `ApngUtils.validateApng(bytes)` | 已拿到 `byte[]` 时更方便 |
| 看帧数 / 尺寸 / 是否动图 | `ApngUtils.getApngInfo(in)` → `ApngInfo` | `isAnimated()` 判定 |
| 抽出所有帧 | `ApngUtils.extractFrames(in)` | 返回 `List<BufferedImage>` |
| 把帧合成 APNG | `ApngUtils.createApng(frames, delays)` | delays 单位毫秒，缺省 100 |
| 透明化 / 缩放 | `ApngUtils.optimizeImage(img)` / `ApngUtils.resizeImage(img, w, h)` | ARGB / 平滑缩放 |
| GIF 文件 → APNG/PNG 文件 | SPI `gif2apng`（`GifToApngConvertFileSystem`） | 支持 gif→apng、gif→png |
| APNG/PNG 文件 → GIF 文件 | SPI `apng2gif`（`ApngToGifConvertFileSystem`） | 支持 apng→gif、png→gif |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-image-apng-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 工具类：检测 + 信息 + 抽帧 + 合成

```java
import com.chua.apng.support.utils.ApngUtils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

byte[] data = Files.readAllBytes(Path.of("anim.png"));

// 1) 是否 APNG
boolean apng = ApngUtils.validateApng(data);

// 2) 动画信息（帧数 / 宽高 / 是否动图）
ApngUtils.ApngInfo info = ApngUtils.getApngInfo(new ByteArrayInputStream(data));
System.out.println(info);   // ApngInfo{frames=3, size=320x240, animated=true}

// 3) 提取所有帧
List<BufferedImage> frames = ApngUtils.extractFrames(new ByteArrayInputStream(data));

// 4) 合成 APNG（每帧 100ms）
byte[] apngBytes = ApngUtils.createApng(frames, List.of(100, 100, 100));
```

> 注意：`getApngInfo` / `extractFrames` 会消费并关闭传入流，请每次传入新的流（如上例的 `ByteArrayInputStream`）；`isApngFormat` 依赖 mark/reset，`ByteArrayInputStream`、`BufferedInputStream` 均可。

### 3.3 文件转换（SPI 按名获取）

```java
import com.chua.common.support.file.converter.ConvertFileSystem;
import com.chua.common.support.spi.ServiceProvider;

import java.io.File;

// GIF → APNG
ConvertFileSystem gif2apng = ServiceProvider.of(ConvertFileSystem.class).getExtension("gif2apng");
gif2apng.convert(new File("in.gif"), new File("out.png"));

// APNG → GIF
ConvertFileSystem apng2gif = ServiceProvider.of(ConvertFileSystem.class).getExtension("apng2gif");
apng2gif.convert(new File("in.png"), new File("out.gif"));
```

转换行为要点（与源码一致）：

- GIF→APNG：逐帧读取 GIF（帧延迟取自 `GraphicControlExtension.delayTime`，1/100 秒换算毫秒，下限 10ms，缺省 100ms），统一转 ARGB 后按序列写 APNG；动画写入失败时回退写第一帧为静态 PNG。
- APNG→GIF：逐帧读取 APNG（缺省帧延迟 100ms），写 GIF 序列并配置 `delayTime`（毫秒换算 1/100 秒，下限 1）、`disposalMethod=restoreToBackgroundColor`、`transparentColorFlag=true`，非末帧写入 NETSCAPE 循环扩展。

---

## 4. 配置项

**无。** 本模块为零配置模块：`src/main/resources` 下只有 SPI 注册文件与 GraalVM native-image 配置，没有配置类、配置键或 `application*.yml/properties`。所有行为参数（如缺省帧延迟 100ms、GIF 延迟下限 10ms）均为源码内固定值，不通过配置修改。

---

## 5. SPI 扩展点

本模块消费 common-starter 的 `ConvertFileSystem` SPI（`@Spi("source2target")` 命名），并注册两个实现：

| SPI 名称 | 实现类 | `type()` | `supportedTypes()` |
| --- | --- | --- | --- |
| `gif2apng` | `com.chua.apng.support.converter.GifToApngConvertFileSystem` | `apng` | gif→apng、gif→png |
| `apng2gif` | `com.chua.apng.support.converter.ApngToGifConvertFileSystem` | `gif` | apng→gif、png→gif |

注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.file.converter.ConvertFileSystem`

```text
apng2gif=com.chua.apng.support.converter.ApngToGifConvertFileSystem
gif2apng=com.chua.apng.support.converter.GifToApngConvertFileSystem
```

扩展方式：新增「X ↔ APNG」转换时，继承 `AbstractConvertFileSystem` 实现 `doConvert(InputStream, OutputStream, File, File)` 与 `type()`，标注 `@Spi("x2apng")`，并在 SPI 文件中登记 `<名称>=<实现类全限定名>`，即可被 `ServiceProvider.of(ConvertFileSystem.class).getExtension("<名称>")` 获取。GraalVM 环境下需同步补充 `reflect-config.json`。

---

## 6. 构建与依赖

```text
utils-support-image-apng-starter
├── com.chua:utils-support-common-starter      （SPI 契约与工具）
└── com.tianscar.imageio:imageio-apng:1.0.1    （APNG ImageIO Reader/Writer）
```

- 父模块：`utils-support-filesystem-parent`（版本 `4.0.0.51`），源码编码 UTF-8。
- 编译：JDK 25（`maven.compiler.source` / `maven.compiler.target = 25`）。
- ImageIO 装配：运行期通过 `ImageIO.getImageReadersByFormatName("APNG")` / `getImageWritersByFormatName("APNG")` 获取读写器；找不到时回退 JDK 内置 `PNG`（此时多帧动画能力受限）。
- native-image：`META-INF/native-image/com.chua/utils-support-image-apng-starter/` 下的 `reflect-config.json`（两个转换器）与 `resource-config.json`（`META-INF/extensions/.*`）。
- 构建命令：在 `utils-support-parent-starter` 下执行 `mvn -o -q compile -DskipTests`，`EXIT=0` 为通过。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| 静态图片读写、缩放、水印、裁剪、旋转、灰度/模糊 | `utils-support-image-starter`、common-starter `com.chua.common.support.utils.BufferedImageUtils` |
| 二维码生成与样式 | `utils-support-zxing-starter` |
| 视频/音频转码、抽帧 | `utils-support-ffmpeg-*` 系列、`utils-support-video-processor-starter` |
| 多格式文件转换编排（docx→pdf 等）与转换流程 | common-starter `com.chua.common.support.file.converter`（`FileConvertSystemFlow` 等）+ 各格式 starter |
| WebP / AVIF 等其它动图格式 | 本模块未实现，源码未提供 |
