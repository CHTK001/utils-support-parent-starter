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
├── com.github.animeazing:webp-imageio-core  # WebP 支持（可选）
└── com.github.jai-imageio:jai-imageio-core  # TIFF 支持（可选）
```

---

## 注意事项

1. **环境要求**：Java 21+
2. **大图片处理**：建议使用流式处理，避免内存溢出
3. **格式支持**：部分格式转换需要额外依赖库（如 WebP 需要 `webp-imageio-core`）
4. **线程安全**：`Imaging` 和 `ConvertFileSystem` 实例不是线程安全的，多线程使用时请创建新实例
