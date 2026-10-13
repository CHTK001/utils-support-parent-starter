# Utils Support Image Starter

图像处理模块，提供图片格式转换、缩放、旋转、裁剪、滤镜等能力，基于 SPI 机制自动发现实现。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| 格式转换 | WebP/JPEG/PNG/BMP/GIF/TIFF/ICO 等格式互转 |
| 缩放 | 指定尺寸、按比例、按宽度缩放 |
| 旋转 | 任意角度旋转 |
| 裁剪 | 指定区域裁剪 |
| 灰度化/二值化 | 图像预处理 |
| 滤镜 | SPI 扩展自定义滤镜 |
| Base64 | 编码/解码 |
| 图片信息 | 获取宽高、类型等元数据 |
| 压缩 | TinyPNG 式有损量化压缩、Zipic 式多格式重编码，SPI 可插拔 |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-image-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 图片格式转换

```java
import com.chua.common.support.io.file.system.ConvertFileSystem;
import java.io.File;

// 自动检测转换器
File sourceFile = new File("input.webp");
File targetFile = new File("output.png");
ConvertFileSystem convertSystem = ConvertFileSystem.autoDetect(sourceFile, "png");
if (convertSystem != null) {
    convertSystem.convertTo(targetFile);
}
```

### 3. 图片处理

```java
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.image.Imaging;
import java.io.File;

Imaging imaging = ServiceProvider.of(Imaging.class).getNewExtension();

// 缩放
imaging.image(new File("input.jpg"))
    .scale(800, 600)
    .toFile(new File("output.jpg"));

// 按比例缩放
imaging.image(new File("input.jpg"))
    .scale(0.5f)
    .toFile(new File("output.jpg"));

// 旋转
imaging.image(new File("input.jpg"))
    .rotate(90)
    .toFile(new File("output.jpg"));

// 裁剪
imaging.image(new File("input.jpg"))
    .getSubImage(100, 100, 400, 300)
    .toFile(new File("output.jpg"));

// 灰度化
imaging.image(new File("input.jpg"))
    .gray()
    .toFile(new File("output.jpg"));

// 二值化
imaging.image(new File("input.jpg"))
    .bin()
    .toFile(new File("output.jpg"));
```

### 4. 链式操作

```java
// 组合多个操作：缩放 + 旋转 + 灰度化
imaging.image(new File("input.jpg"))
    .scale(800, 600)
    .rotate(90)
    .gray()
    .toFile(new File("output.jpg"));
```

### 5. 格式转换（使用 Imaging）

```java
imaging.image(new File("input.jpg"))
    .outputFormat("png")
    .toFile(new File("output.png"));
```

### 6. 获取图片信息

```java
Imaging image = imaging.image(new File("input.jpg"));
int width = image.getWidth();
int height = image.getHeight();
var mediaType = image.mediaType();
```

### 7. 转换为 Base64

```java
String base64 = imaging.image(new File("input.jpg")).toBase64();
```

### 8. 输出到流

```java
try (FileOutputStream out = new FileOutputStream("output.jpg")) {
    imaging.image(new File("input.jpg")).toStream(out);
}
```

---

## 图片压缩

`ImageCompressor` SPI 提供两套压缩引擎，通过引擎名而非格式名查找。

```java
import com.chua.common.support.image.compression.*;
import com.chua.common.support.spi.ServiceProvider;

// TinyPNG 风格：有损量化 + 无损 deflate 优化
ImageCompressor tinyPng = ServiceProvider.of(ImageCompressor.class).getNewExtension("tinypng");

CompressResult result = tinyPng.compress(sourceBytes, CompressOptions.of("png"));
System.out.println(result.describe());
// tinypng png: 31.0KB -> 19.0KB (-39.2%)
```

### 两套引擎

| 引擎别名 | 类 | 路线 | 支持格式 |
|----------|-----|------|----------|
| `tinypng` / `tinypng-style` | `TinyPngImageCompressor` | 先有损失色，再无损优化 | png、jpeg、webp、gif、bmp |
| `zipic` / `zippic` | `ZipicImageCompressor` | 整图重编码 + 格式转换 | jpeg、png、webp、heic、avif、jxl、tiff、gif、bmp、icns、apng |

`TinyPngImageCompressor` 的两段式管线对应 TinyPNG 的做法：
1. **有损量化** —— 24-bit RGB(A) 降到 8-bit 索引色调色板，体积直接降到 1/3~1/4
2. **无损 deflate 优化** —— 扫描线过滤器策略选择、deflate 参数穷举、剥离元数据块

`ZipicImageCompressor` 面向「HEIC / 大尺寸 TIFF 转成网页可用的 JPEG 或 WebP」这类需求，
目标格式没有可用写出器时自动降级（HEIC 走本模块的 `HEICImageWriterSpi`，
avif / jxl 需要应用侧自行引入 ImageIO 插件）。

### 压缩等级

`CompressLevel` L0~L6，L0 为纯无损（像素逐点一致），等级越高量化越狠、耗时越长。

| 等级 | PNG 色数上限 | 抖动 | deflate 迭代 | 有损格式质量 |
|------|-------------|------|-------------|-------------|
| L0 | 不量化 | 关 | 1 | 100 |
| L1 | 256 | 关 | 1 | 92 |
| L2 | 256 | 开 | 3 | 85 |
| L3 | 256 | 开 | 8 | 78 |
| L4 | 128 | 开 | 20 | 72 |
| L5 | 64 | 开 | 50 | 65 |
| L6 | 32 | 开 | 150 | 60 |

### 压缩参数

`CompressOptions` 是不可变记录类，所有派生字段在构造时按等级补全为具体值。

```java
CompressOptions options = CompressOptions.of("png")
        .level(CompressLevel.L4)
        .targetFormat("webp")     // 顺带转格式
        .keepMetadata(true)       // 保留元数据块
        .size(1280, -1)           // 等比缩到宽 1280
        .discardWhenLarger(true); // 压不小就返回原始字节（默认开启）

CompressResult result = tinyPng.compress(sourceBytes, options);
result.savedRatio();   // 压缩率
result.toMap();        // 统计信息
```

### 可插拔的 native 钩子

压缩管线里真正需要原生库的两点被抽成独立 SPI，接入 native 实现只需新增一个类并注册，
压缩引擎与调用方都不用改动：

| 接口 | 纯 Java 兜底（`order = -100`） | native 实现（`order = 100`，已内置） |
|------|------------------------------|-----------------------------------|
| `DeflateEngine` | `JdkDeflateEngine`（zlib 绑定 + 级别×策略矩阵试压） | `LibDeflateEngine`（libdeflate） |
| `PaletteQuantizer` | `MedianCutQuantizer`（Lab 空间中位切分 + Floyd-Steinberg） | `ImageQuantQuantizer`（libimagequant，即 pngquant 内核） |

native 实现在 `utils-support-native-imageopt` 模块（Rust cdylib，静态链接 libdeflate
与 libimagequant，Panama FFM 调用，目标机无需安装任何 C 库）。

`CompressionEngines` 通过 `ServiceProvider` 解析这两个接口，**始终带有内置兜底**：
动态库缺失时 `available()` 返回 `false`，自动回退纯 Java 版本，功能不受影响。
需要完全排除时把 `utils-support-native-imageopt` 从依赖树里 `<exclusions>` 掉即可。

#### 实测收益

同一张 1200×900 合成照片（ImageIO 写出的 PNG 为 434,104 字节），`zipic` 引擎，Windows x64。
体积是确定性输出，可复现；耗时受 JIT 预热影响，只给量级：

| 等级 | 色数 | 纯 Java | libdeflate + libimagequant | 体积收益 | 耗时 |
|------|------|--------|---------------------------|---------|------|
| L0 | 不量化 | 395,922 | 337,008 | −14.9% | 322ms → 2.2s |
| L1 | 256 | 162,588 | 112,311 | −30.9% | 545ms → 707ms |
| L2 | 256 | 160,386 | 112,311 | −30.0% | 426ms → 601ms |
| L3 | 256 | 150,390 | 112,124 | −25.4% | 1.4s → 726ms |
| L4 | 128 | 140,890 | 96,280 | −31.7% | 1.3s → 830ms |
| L5 | 64 | 125,438 | 81,573 | −35.0% | 1.0s → 779ms |
| L6 | 32 | 107,988 | 66,585 | **−38.3%** | ~持平 |

**大头来自量化器，不是 deflate。** 单看 deflate 内核，在各等级真实的 IDAT 上只有 −7%~−8%：

| 等级 | 滤波后索引扫描线 | zlib 最优 | libdeflate | IDAT 收益 |
|------|----------------|---------|-----------|----------|
| L1/L2（256 色） | 1,080,900 | 220,562 | 202,672 | −8.1% |
| L3（256 色） | 1,080,900 | 220,562 | 202,430 | −8.2% |
| L4（128 色） | 1,080,900 | 298,888 | 275,694 | −7.8% |
| L6（32 色） | 1,080,900 | 290,814 | 270,376 | −7.0% |

剩下的 20%~30% 来自 libimagequant 生成**更好的调色板**——调色板质量直接决定索引的
局部连贯性：中位切分在照片上会切出色带，色带把索引变成高频噪声，deflate 的游程与匹配
全被破坏，于是**又大又难看**。量化质量差距有两个数量级（均方误差，越低越好）：

| 色数上限 | median-cut MSE | libimagequant MSE | 误差降低 |
|---------|----------------|-------------------|---------|
| 32 | 3,933 | 240 | 16 倍 |
| 128 | 4,749 | 80 | 60 倍 |
| 256 | 5,021 | 38 | **131 倍** |

在大色块图上差距更极端：median-cut 的 MSE 是 2,026（把平涂区域量化出了色带），
libimagequant 是 **0.0000**（完全无损）。

#### 三处必须知道的代价

1. **L0 明显变慢，L1/L2 略慢。** 为了不进「比 zlib 差」的区间，deflate 一律用 libdeflate
   的 11/12 档，单这一项就比 zlib level 9 慢 2.6 倍。L0 是纯无损档（不量化，
   deflate 独占耗时），因此从 ~320ms 涨到 ~2.2s，**慢约 7 倍**；
   L1/L2 慢 1.3~1.4 倍，L3 起 libimagequant 省下的时间把这项抵平，净持平或更快。
   **对延迟敏感的场景用 L3，或把 `utils-support-native-imageopt` 从依赖树里排除。**
2. **deflate 的级别刻度不可直通。** libdeflate 与 zlib 的级别不同向——在 PNG-8 索引数据上，
   libdeflate 的 **1~9 档无一例外比 zlib 差**（+0.08% ~ +13%），交叉点在 10~11 档。
   直通映射会让 L1/L2 **倒亏 2.5%**。因此 `LibDeflateEngine` 里有显式映射：
   SPI 0~8 → native 11，`iterations >= 8`（即 L3 起）→ native 12。
   完整实测数据表写在 `LibDeflateEngine` 的 Javadoc 里。

   > ⚠️ 该映射是**经验测量**的产物，不是从上游文档抄的。
   > **升级 libdeflate 版本时必须重测这张表**，否则会静默倒亏。
3. **映射出来的档位不再做多档试压。** 档位是按上表算出来的，不是猜出来的；
   而 `chua_df_zlib_compress_best` 会向上展开一档，必然跨过映射刻意设的上限，
   结果永远等于最高档——多花一倍 CPU 换不到任何字节。

---

## 支持的格式

| 源格式 | 可转换目标格式 |
|--------|----------------|
| WebP | JPEG、PNG、BMP、GIF、ICO |
| JPEG | PNG、BMP、GIF、WEBP、TIFF、ICO |
| PNG | JPEG、BMP、GIF、WEBP、TIFF、ICO |

---

## 配置说明

本模块通过 SPI 机制自动注册，无需额外配置。

### SPI 扩展点

| 接口 | 说明 |
|------|------|
| `ConvertFileSystem` | 文件格式转换系统 |
| `Imaging` | 图片处理接口 |
| `ImageFilter` | 图片滤镜接口 |
| `ImageCompressor` | 图片压缩引擎，按引擎名注册（`tinypng` / `zipic`） |
| `DeflateEngine` | deflate 压缩引擎钩子，可替换为 native 实现 |
| `PaletteQuantizer` | 调色板量化器钩子，可替换为 native 实现 |

### 自定义滤镜

```java
@Spi("custom-filter")
public class CustomImageFilter implements ImageFilter {

    @Override
    public String getImageFormat() {
        return "jpeg";
    }

    @Override
    public BufferedImage converter(BufferedImage image) throws IOException {
        // 自定义滤镜逻辑
        return image;
    }
}
```

### 自定义转换器

```java
@Spi("custom-converter")
public class CustomConvertFileSystem extends AbstractConvertFileSystem {

    @Override
    protected void doConvert(InputStream inputStream, OutputStream outputStream,
                             File sourceFile, File targetFile) throws IOException {
        // 自定义转换逻辑
    }

    @Override
    protected boolean isSupportFormat(String sourceFormat, String targetFormat) {
        return "custom1".equals(sourceFormat) && "custom2".equals(targetFormat);
    }

    @Override
    public ConvertSupport[] supportedTypes() {
        return new ConvertSupport[]{new ConvertSupport("custom1", "custom2")};
    }
}
```

---

## 依赖关系

```
utils-support-image-starter
├── utils-support-common-starter       # 核心基础（SPI、Imaging 接口）
├── utils-support-native-libjpeg-turbo # JPEG 压缩走 libjpeg-turbo 原生库
├── utils-support-native-imageopt      # PNG 压缩走 libdeflate + libimagequant 原生库
├── com.github.animeazing:webp-imageio-core  # WebP 支持（可选）
└── com.github.jai-imageio:jai-imageio-core  # TIFF 支持（可选）
```

---

## 注意事项

1. **环境要求**：Java 21+
2. **大图片处理**：建议使用流式处理，避免内存溢出
3. **格式支持**：部分格式转换需要额外依赖库（如 WebP 需要 `webp-imageio-core`）
4. **线程安全**：`Imaging` 和 `ConvertFileSystem` 实例不是线程安全的，多线程使用时请创建新实例
5. **压缩耗时**：`tinypng` L6（32 色 + 150 次 deflate 迭代）在 4000×3000 照片上需要数秒到数十秒，
   批量流水线建议用 L2~L3；L0 为纯无损，可安全用于任何场景。
   接入 `utils-support-native-imageopt` 后 **L0 会慢约 7 倍、L1/L2 慢 1.3 倍**
   （deflate 一律走 libdeflate 11/12 档，换来 15%~31% 体积）；L3 起更快。
   对延迟敏感的场景用 L3，或把该依赖从依赖树里排除走纯 Java 路径。
