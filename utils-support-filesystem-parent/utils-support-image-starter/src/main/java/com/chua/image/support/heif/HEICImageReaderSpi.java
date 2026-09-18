package com.chua.image.support.heif;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

/**
* HEIC/HEIF 图像读取器 SPI 声明。
*
* <p>独立于 nightmonkeys/imageio-heif 实现，避免对其共享库的依赖。
* 当系统有 libheif 时由 heifNAT解码器 提供实际解码能力。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class HEICImageReaderSpi extends ImageReaderSpi {

    private static final String VENDOR = "com.chua"; // 厂商
    private static final String VERSION = "1.0.0"; // 版本
    private static final String[] NAMES = {"heic", "HEIC", "heif", "HEIF"}; // 名称
    private static final String[] SUFFIXES = {"heic", "heif"}; // 后缀
    private static final String[] MIMES = {"image/heic", "image/heif"}; // MIMES

    /**
    * heic镜像读取spi。
    */
    public HEICImageReaderSpi() {
        super(VENDOR, VERSION, NAMES, SUFFIXES, MIMES,
                HEICImageReader.class.getName(),
                new Class<?>[]{ImageInputStream.class},
                null, false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public String getDescription(Locale locale) {
        return "HEIC/HEIF image reader (com.chua)";
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
 // ftyp box 检查
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
