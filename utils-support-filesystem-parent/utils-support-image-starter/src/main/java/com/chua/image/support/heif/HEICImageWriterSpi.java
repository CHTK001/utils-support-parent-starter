package com.chua.image.support.heif;

import io.github.lukas81298.imageio.heif.HeifImageWriterSpi;
import io.github.lukas81298.imageio.heif.HeifImageWriter;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.Locale;

/**
 * HEIC/HEIF 图片写入 SPI 实现。
 *
 * <p>封装 {@link HeifImageWriterSpi}，使 {@link javax.imageio.ImageIO} 能够将
 * {@link BufferedImage} 写入 HEIC/HEIF 格式文件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageWriterSpi extends ImageWriterSpi {

    private static final String VENDOR = "com.chua";
    private static final String VERSION = "1.0.0";
    private static final String[] NAMES = {"heic", "HEIC", "heif", "HEIF"};
    private static final String[] SUFFIXES = {"heic", "heif"};
    private static final String[] MIMES = {"image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence"};

    public HEICImageWriterSpi() {
        super(
                VENDOR, VERSION, NAMES, SUFFIXES, MIMES,
                HEICImageWriter.class.getName(),
                new Class<?>[]{ImageOutputStream.class},
                null, false,
                null, null, null, null,
                true, null, null, null, null
        );
    }

    @Override
    public String getDescription(Locale locale) {
        return "HEIC/HEIF image writer";
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        ColorModel cm = type.getColorModel();
        if (cm == null) {
            return false;
        }
        int numBands = type.getSampleModel().getNumBands();
        // Support RGB and RGBA (with alpha)
        return numBands >= 3 && numBands <= 4;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new HEICImageWriter(this);
    }
}
