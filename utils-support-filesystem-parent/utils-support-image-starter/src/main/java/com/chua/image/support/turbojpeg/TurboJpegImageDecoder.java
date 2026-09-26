package com.chua.image.support.turbojpeg;

import com.chua.common.support.codec.image.ImageDecoder;
import com.chua.common.support.exception.CodecException;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.nativeturbojpeg.support.TurboJpegBridge;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Locale;
import java.util.Objects;

/**
 * 基于 libjpeg-turbo 原生库的 JPEG 解码器。
 *
 * <p>彩色 JPEG 解码为 {@link BufferedImage#TYPE_3BYTE_BGR}、灰度 JPEG 解码为
 * {@link BufferedImage#TYPE_BYTE_GRAY}。彩色路径向原生库请求 RGB 通道序而非 BGR：
 * {@code WritableRaster#setDataElements} 的一维数组按<b>波段顺序</b>解释，内存字节序
 * 由 bandOffsets 决定，若直接按内存序传 BGR 会把 R 与 B 互换。</p>
 *
 * <p>原生行跨距的 4 字节填充在按行写入栅格时剥离。</p>
 *
 * <p>原生库不可用时 {@link #supports(String)} 返回 {@code false}，
 * 调用方应回落到纯 Java（ImageIO）实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"turbojpeg", "tj", "libjpeg-turbo"}, order = 100)
@SpiDescribe("libjpeg-turbo 原生 JPEG 解码器")
public class TurboJpegImageDecoder implements ImageDecoder {

    /**
     * TurboJPEG 色度二次采样：单通道灰度。
     */
    private static final int TJSAMP_GRAY = 3;

    @Override
    public boolean supports(String format) {
        if (!TurboJpegBridge.isLoaded()) {
            return false;
        }
        if (format == null) {
            return false;
        }
        String name = format.toLowerCase(Locale.ROOT);
        return "jpg".equals(name) || "jpeg".equals(name) || "image/jpeg".equals(name);
    }

    @Override
    public Dimension probeDimension(byte[] data) {
        requireData(data);
        if (!TurboJpegBridge.isLoaded()) {
            throw new CodecException("libjpeg-turbo native library not loaded: "
                    + TurboJpegBridge.getLoadError());
        }
        int[] probed = TurboJpegBridge.probe(data);
        return new Dimension(probed[0], probed[1]);
    }

    @Override
    public BufferedImage decode(byte[] data) {
        requireData(data);
        if (!TurboJpegBridge.isLoaded()) {
            throw new CodecException("libjpeg-turbo native library not loaded: "
                    + TurboJpegBridge.getLoadError());
        }
        int[] probed = TurboJpegBridge.probe(data);
        boolean grayscale = probed[2] == TJSAMP_GRAY;
        TurboJpegBridge.Decoded decoded = TurboJpegBridge.decompress(
                data, grayscale ? TurboJpegBridge.TJPF_GRAY : TurboJpegBridge.TJPF_RGB, 0);
        if (decoded.width() != probed[0] || decoded.height() != probed[1]) {
            throw new CodecException("turbojpeg decoded %dx%d but header reported %dx%d",
                    decoded.width(), decoded.height(), probed[0], probed[1]);
        }
        return toImage(decoded, grayscale);
    }

    /**
     * 把原生输出的紧凑像素装入 {@link BufferedImage}。
     *
     * @param decoded   原生解码结果
     * @param grayscale 是否灰度图
     * @return 图像对象
     */
    private static BufferedImage toImage(TurboJpegBridge.Decoded decoded, boolean grayscale) {
        int width = decoded.width();
        int height = decoded.height();
        BufferedImage image = new BufferedImage(width, height, grayscale
                ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR);
        WritableRaster raster = image.getRaster();
        int bytesPerPixel = grayscale ? 1 : 3;
        int rowBytes = width * bytesPerPixel;
        byte[] row = new byte[rowBytes];
        byte[] pixels = decoded.pixels();
        for (int y = 0; y < height; y++) {
            System.arraycopy(pixels, y * decoded.pitch(), row, 0, rowBytes);
            raster.setDataElements(0, y, width, 1, row);
        }
        return image;
    }

    /**
     * 校验输入字节非空。
     *
     * @param data 图像字节
     */
    private static void requireData(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new CodecException("empty image data");
        }
    }
}
