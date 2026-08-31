package com.chua.image.support.heif;

import io.github.lukas81298.imageio.heif.HeifImageReader;
import io.github.lukas81298.imageio.heif.HeifImageReaderSpi;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * HEIC/HEIF 图片读取 SPI 实现。
 *
 * <p>封装 {@link HeifImageReader} 并遵循 {@link ImageReaderSpi} SPI 规范，
 * 使 {@link javax.imageio.ImageIO} 能够自动发现并读取 HEIC/HEIF 格式图片。</p>
 *
 * <h3>支持的格式</h3>
 * <ul>
 *   <li>{@code heic} / {@code HEIC} — High Efficiency Image Coding</li>
 *   <li>{@code heif} / {@code HEIF} — High Efficiency Image Format</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR = "com.chua";
    private static final String VERSION = "1.0.0";
    private static final String[] NAMES = {"heic", "HEIC", "heif", "HEIF"};
    private static final String[] SUFFIXES = {"heic", "heif"};
    private static final String[] MIMES = {"image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence"};

    public HEICImageReaderSpi() {
        super(
                VENDOR, VERSION, NAMES, SUFFIXES, MIMES,
                HEICImageReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                null, false,
                null, null, null, null,
                true, null, null, null, null
        );
    }

    @Override
    public String getDescription(Locale locale) {
        return "HEIC/HEIF image reader";
    }

    @Override
    public boolean canDecodeInput(Object input) throws IOException {
        if (!(input instanceof ImageInputStream stream)) {
            return false;
        }
        byte[] b = new byte[16];
        stream.mark();
        int n = stream.read(b);
        stream.reset();
        if (n < 12) {
            return false;
        }
        // ftyp box: bytes[4..7] == "ftyp", bytes[8..11] starts with heic/heix/heim/hevc/mif1
        if (!"ftyp".equals(new String(b, 4, 4))) {
            return false;
        }
        String brand = new String(b, 8, 4);
        return brand.startsWith("heic") || brand.startsWith("heix")
                || brand.startsWith("heim") || brand.startsWith("hevc")
                || brand.startsWith("mif1") || brand.startsWith("msf1");
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new HEICImageReader(this);
    }
}
