package com.chua.image.support.filesystem;

import com.chua.common.support.file.converter.AbstractConvertFileSystem;
import com.chua.common.support.file.converter.ConvertFileSystem;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.image.support.utils.ImageSupportUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


/**
 * 图片格式互转转换器。
 *
 * <p>支持常见图片格式之间的相互转换，通过 {@link ImageSupportUtils} 动态发现
 * 当前 JVM 中 ImageIO 注册的所有读写格式，无需硬编码格式列表。
 *
 * <h3>支持的格式</h3>
 * <ul>
 *   <li><b>JPEG</b> (.jpg, .jpeg) — 自动处理 Alpha 通道移除</li>
 *   <li><b>PNG</b> (.png) — 无损压缩</li>
 *   <li><b>BMP</b> (.bmp) — 无压缩位图</li>
 *   <li><b>GIF</b> (.gif) — 索引色格式</li>
 *   <li><b>WBMP</b> (.wbmp) — 无线位图</li>
 *   <li><b>TIFF</b> (.tiff, .tif) — 需 JAI ImageIO 插件</li>
 * </ul>
 *
 * <p>以下格式由专用转换器处理，本转换器不处理：</p>
 * <ul>
 *   <li><b>WEBP</b> (.webp) — 由 {@code WebpConvertFileSystem} 处理</li>
 *   <li><b>RAW</b> (.cr2, .nef 等) — 由 Rust 转换器处理</li>
 *   <li><b>HEIC/HEIF</b> (.heic, .heif) — 由 Rust 转换器处理</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi
public class ImageFormatConvertFileSystem extends AbstractConvertFileSystem {

    /**
     * 默认构造函数
     */
    public ImageFormatConvertFileSystem() {
        super();
    }

    /**
     * 构造函数
     *
     * @param file 文件对象
     */
    public ImageFormatConvertFileSystem(File file) {
        super(file);
    }

    /**
     * 构造函数
     *
     * @param filePath 文件路径
     */
    public ImageFormatConvertFileSystem(String filePath) {
        super(filePath);
    }

    @Override
    /** Type */
    public String type() {
        return "image";
    }

    @Override
    /**
     * Do转换
     * @param inputStream inputStream
     * @param outputStream outputStream
     * @param sourceFile sourceFile
     * @param targetFile targetFile
     */
    protected void doConvert(InputStream inputStream, OutputStream outputStream,
                             File sourceFile, File targetFile) throws IOException {
        try {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("无法读取图片文件");
            }

            String targetFormat = ImageSupportUtils.getFormatFromFile(targetFile);
            ImageSupportUtils.saveToStream(image, targetFormat, outputStream);
        } catch (Exception e) {
            log.error("[图片格式转换][转换]转换失败: {} -> {}",
                    sourceFile != null ? sourceFile.getName() : "流",
                    targetFile != null ? targetFile.getName() : "流", e);
            throw new IOException("图片格式转换失败", e);
        }
    }

    /** 是否Support格式化 */
    protected boolean isSupportFormat(String sourceFormat, String targetFormat) {
        // 相同格式不需要转换
        if (sourceFormat != null && sourceFormat.equalsIgnoreCase(targetFormat)) {
            return false;
        }

        // 排除由专用转换器处理的格式
        if (ImageSupportUtils.isExcluded(sourceFormat)
                || ImageSupportUtils.isExcluded(targetFormat)) {
            return false;
        }

        // 通过 ImageIO 动态检测
        String normalizedSource = ImageSupportUtils.normalizeFormat(sourceFormat);
        String normalizedTarget = ImageSupportUtils.normalizeFormat(targetFormat);

        return ImageSupportUtils.isReadable(normalizedSource)
                && ImageSupportUtils.isWritable(normalizedTarget);
    }

    @Override
    /** SupportedTypes */
    public ConvertFileSystem.ConvertSupport[] supportedTypes() {
        Set<String> formats = ImageSupportUtils.getAllSupportedFormats();
        List<String> formatList = new ArrayList<>(formats);

        List<ConvertFileSystem.ConvertSupport> supports = new ArrayList<>();

        for (String source : formatList) {
            if (ImageSupportUtils.isExcluded(source)) {
                continue;
            }
            for (String target : formatList) {
                if (!ImageSupportUtils.isExcluded(target)
                        && !source.equalsIgnoreCase(target)) {
                    supports.add(new ConvertFileSystem.ConvertSupport(source, target));
                }
            }
        }

        return supports.toArray(new ConvertFileSystem.ConvertSupport[0]);
    }
}
