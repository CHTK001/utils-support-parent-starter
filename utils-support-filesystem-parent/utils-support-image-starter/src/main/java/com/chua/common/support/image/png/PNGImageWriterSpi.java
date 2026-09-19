package com.chua.common.support.image.png;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.SampleModel;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * PNG 图像写入器服务提供者接口（SPI）。
 *
 * <p>Image I/O 框架的 SPI 实现，用于发现和实例化 PNGImageWriter。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PNGImageWriterSpi extends ImageWriterSpi {
    // 定义厂商名称
    /** 厂商名称 */
    private static final String vendorName = "Karstian Lee";

    // 定义版本号
    /** 版本 */
    private static final String version = "1.0";

    // 支持的图像格式名称，包括 PNG 和 APNG
    /** 名称 */
    private static final String[] names = { "png", "PNG", "apng", "APNG" };

    // 支持的文件后缀
    /** Suffixes */
    private static final String[] suffixes = { "png", "apng" };

    // 支持的 MIME 类型
    /** Mimetypes */
    private static final String[] MIMETypes = { "image/png", "image/x-png", "image/apng" };

    // 图像写入器类名
    /** 写入器类名称 */
    private static final String writerClassName =
            "com.tianscar.imageio.plugins.png.PNGImageWriter";

    // 图像读取器服务提供者名称
    /** 读取器spinames */
    private static final String[] readerSpiNames = {
            "com.tianscar.imageio.plugins.png.PNGImageReaderSpi"
    };

    /**
    * 构造函数，初始化 镜像writerspi 的基类信息。
    */
    public PNGImageWriterSpi() {
        super(vendorName,
                version,
                names,
                suffixes,
                MIMETypes,
                writerClassName,
                new Class<?>[] { ImageOutputStream.class },
                readerSpiNames,
                false,
                null, null,
                null, null,
                true,
                PNGMetadata.nativeMetadataFormatName,
                "com.tianscar.imageio.plugins.png.PNGMetadataFormat",
                null, null
        );
    }

    /**
     * 检查该写入器是否可以编码给定类型的图像。
     *
     * @param type 图像类型说明
     * @return 如果可以编码，则返回 true；否则返回 false
     */
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        SampleModel sampleModel = type.getSampleModel();
        ColorModel colorModel = type.getColorModel();

        // 找到所有通道的最大位深度
        int[] sampleSize = sampleModel.getSampleSize();
        int bitDepth = sampleSize[0];
        for (int i = 1; i < sampleSize.length; i++) {
            if (sampleSize[i] > bitDepth) {
                bitDepth = sampleSize[i];
            }
        }

        // 确保位深度在 1 到 16 之间
        if (bitDepth < 1 || bitDepth > 16) {
            return false;
        }

        // 检查波段数量和是否包含 alpha 通道
        int numBands = sampleModel.getNumBands();
        if (numBands < 1 || numBands > 4) {
            return false;
        }

        boolean hasAlpha = colorModel.hasAlpha();
        // 修复 4464413: PNG 透明度测试失败
 // 因为对于具有 alpha 通道的 索引color模型，
        // numBands == 1 && hasAlpha == true，从而导致下面的检查失败并返回 false。
        if (colorModel instanceof IndexColorModel) {
            return true;
        }
        if ((numBands == 1 || numBands == 3) && hasAlpha) {
            return false;
        }
        return (numBands != 2 && numBands != 4) || hasAlpha;
    }

    /**
     * 获取该服务提供者的描述信息。
     *
     * @param locale 本地化信息，返回相应语言的描述
     * @return 服务提供者的描述信息
     */
    public String getDescription(Locale locale) {
        return "PNG/APNG image writer";
    }

    /**
     * 创建该服务提供者的写入器实例。
     *
     * @param extension 扩展对象，创建特定的写入器实例
     * @return 创建的写入器实例
     */
    public ImageWriter createWriterInstance(Object extension) {
        return new PNGImageWriter(this);
    }
}
