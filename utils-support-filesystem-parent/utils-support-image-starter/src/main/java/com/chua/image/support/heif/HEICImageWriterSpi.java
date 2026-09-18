package com.chua.image.support.heif;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.ColorModel;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.util.Locale;

/**
* HEIC/HEIF 图像写入器 SPI 声明。
*
* @author CH
* @since 4.0.0.42
 */
public class HEICImageWriterSpi extends ImageWriterSpi {

    private static final String VENDOR = "com.chua"; // 厂商
    private static final String VERSION = "1.0.0"; // 版本
    private static final String[] NAMES = {"heic", "HEIC", "heif", "HEIF"}; // 名称
    private static final String[] SUFFIXES = {"heic", "heif"}; // 后缀
    private static final String[] MIMES = {"image/heic", "image/heif"}; // MIMES

    /**
    * heic镜像writerspi。
    */
    public HEICImageWriterSpi() {
        super(VENDOR, VERSION, NAMES, SUFFIXES, MIMES,
                HEICImageWriter.class.getName(),
                new Class<?>[]{ImageOutputStream.class},
                null, false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public String getDescription(Locale locale) {
        return "HEIC/HEIF image writer (com.chua)";
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        if (type == null) {
            return false;
        }
        int bands = type.getSampleModel().getNumBands();
        return bands >= 1 && bands <= 4;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new HEICImageWriter(this);
    }
}
