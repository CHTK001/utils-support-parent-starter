package com.chua.image.support.file;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.image.support.utils.ImageSupportUtils;
import com.chua.common.support.file.converter.AbstractConvertFileSystem;
import com.chua.common.support.file.converter.ConvertFileSystem;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;


/**
 * WebP 格式转换器（Java 实现）
 * <p>
 * 使用 webp-imageio-core 库实现 WebP 格式的转换。
 * 支持 WebP 与其他常见图片格式之间的相互转换：
 * - WebP ↔ JPEG (.jpg, .jpeg)
 * - WebP ↔ PNG (.png)
 * - WebP ↔ BMP (.bmp)
 * - WebP ↔ GIF (.gif)
 * - WebP ↔ ICO (.ico)
 * </p>
 * <p>
 * 注意：此实现优先级较低（默认优先级 0），如果存在 Rust 实现（优先级 100），
 * 系统会优先使用 Rust 实现以获得更好的性能。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "java", order = 0)
public class WebpConvertFileSystem extends AbstractConvertFileSystem {

    /**
     * 支持的图片格式（除 WebP 外）
     */
    private static final String[] SUPPORTED_FORMATS = {"jpeg", "jpg", "png", "bmp", "gif", "ico"};

    /**
     * 默认构造函数
     */
    public WebpConvertFileSystem() {
        super();
    }

    @Override
    public String type() {
        
        return "webp";
    
    }

    /**
     * 构造函数
     *
     * @param file 文件对象
     */
    public WebpConvertFileSystem(File file) {
        super(file);
    }

    /**
     * 构造函数
     *
     * @param filePath 文件路径
     */
    public WebpConvertFileSystem(String filePath) {
        super(filePath);
    }

    @Override
    protected void doConvert(InputStream inputStream, OutputStream outputStream, File sourceFile, File targetFile) throws IOException {
        try {
            // 读取图片
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("无法读取图片文件");
            }

            // 获取目标格式
            String targetFormat = ImageSupportUtils.getFormatFromFile(targetFile);
            ImageSupportUtils.saveToStream(image, targetFormat, outputStream);
        } catch (Exception e) {
            log.error("[WebP转换][Java实现]转换失败: {} -> {}", 
                sourceFile != null ? sourceFile.getName() : "流", 
                targetFile != null ? targetFile.getName() : "流", e);
            throw new IOException("WebP 格式转换失败", e);
        }
    }

    protected boolean isSupportFormat(String sourceFormat, String targetFormat) {
        // 相同格式不需要转换
        if (sourceFormat != null && sourceFormat.equalsIgnoreCase(targetFormat)) {
            return false;
        }

        // 必须有一个是 webp
        boolean sourceIsWebp = "webp".equalsIgnoreCase(sourceFormat);
        boolean targetIsWebp = "webp".equalsIgnoreCase(targetFormat);
        
        if (!sourceIsWebp && !targetIsWebp) {
            return false;
        }

        // 检查另一个格式是否可读写
        String other = sourceIsWebp ? targetFormat : sourceFormat;
        String normalized = ImageSupportUtils.normalizeFormat(other);
        return ImageSupportUtils.isWritable(normalized) || ImageSupportUtils.isReadable(normalized);
    }

    @Override
    public ConvertFileSystem.ConvertSupport[] supportedTypes() {
        List<ConvertFileSystem.ConvertSupport> supports = new ArrayList<>();
        
        // WebP 转其他格式
        for (String format : SUPPORTED_FORMATS) {
            supports.add(new ConvertFileSystem.ConvertSupport("webp", format));
        }
        
        // 其他格式转 WebP
        for (String format : SUPPORTED_FORMATS) {
            supports.add(new ConvertFileSystem.ConvertSupport(format, "webp"));
        }
        
        return supports.toArray(new ConvertFileSystem.ConvertSupport[0]);
    }

}


