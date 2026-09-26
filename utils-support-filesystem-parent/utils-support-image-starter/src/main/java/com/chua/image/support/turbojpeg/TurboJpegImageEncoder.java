package com.chua.image.support.turbojpeg;

import com.chua.common.support.codec.image.ImageEncoder;
import com.chua.common.support.exception.CodecException;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.nativeturbojpeg.support.TurboJpegBridge;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.Locale;
import java.util.Objects;

/**
 * 基于 libjpeg-turbo 原生库的 JPEG 编码器。
 *
 * <p>像素以 24 位 RGB（彩色）或 8 位灰度（源图为 {@link BufferedImage#TYPE_BYTE_GRAY} 时）
 * 送入 {@code tjCompress2}，由原生侧的 SIMD 汇编核完成 DCT 与霍夫曼编码。
 * 带透明通道的源图会先合成到白底，再交给原生库。</p>
 *
 * <p>原生库不可用时 {@link #supports(String)} 返回 {@code false}，
 * 调用方应回落到纯 Java（ImageIO）实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"turbojpeg", "tj", "libjpeg-turbo"}, order = 100)
@SpiDescribe("libjpeg-turbo 原生 JPEG 编码器")
public class TurboJpegImageEncoder implements ImageEncoder {

    /**
     * 默认编码质量。
     */
    private static final float DEFAULT_QUALITY = 0.75f;

    @Override
    public float quality() {
        return DEFAULT_QUALITY;
    }

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
    public byte[] encode(BufferedImage image, float quality) {
        Objects.requireNonNull(image, "image");
        if (!TurboJpegBridge.isLoaded()) {
            throw new CodecException("libjpeg-turbo native library not loaded: "
                    + TurboJpegBridge.getLoadError());
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            throw new CodecException("cannot encode %dx%d image as JPEG", width, height);
        }
        boolean grayscale = image.getType() == BufferedImage.TYPE_BYTE_GRAY;
        byte[] pixels = grayscale ? packGray(image, width, height) : packRgb(image, width, height);
        int pixelFormat = grayscale ? TurboJpegBridge.TJPF_GRAY : TurboJpegBridge.TJPF_RGB;
        int subsamp = grayscale ? TurboJpegBridge.TJSAMP_GRAY : TurboJpegBridge.TJSAMP_420;
        return TurboJpegBridge.compress(pixels, width, height, 0, pixelFormat,
                toTurboQuality(quality), subsamp, 0);
    }

    /**
     * 把 {@code (0, 1]} 的质量档位换算成 TurboJPEG 的 1-100 整数档。
     *
     * @param quality 质量档位
     * @return TurboJPEG 质量参数
     */
    private static int toTurboQuality(float quality) {
        if (Float.isNaN(quality) || quality <= 0F) {
            return 1;
        }
        if (quality > 1F) {
            return Math.min(100, Math.round(quality));
        }
        return Math.max(1, Math.round(quality * 100F));
    }

    /**
     * 打包为紧凑的 24 位 RGB 缓冲（每行 {@code width * 3} 字节，无填充）。
     *
     * @param image  源图
     * @param width  宽
     * @param height 高
     * @return RGB 字节
     */
    private static byte[] packRgb(BufferedImage image, int width, int height) {
        byte[] dst = new byte[width * height * 3];
        int[] row = new int[width];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int value : row) {
                int alpha = (value >>> 24) & 0xFF;
                int red = (value >>> 16) & 0xFF;
                int green = (value >>> 8) & 0xFF;
                int blue = value & 0xFF;
                if (alpha != 0xFF) {
                    int opaque = 255 - alpha;
                    red = (red * alpha + 255 * opaque) / 255;
                    green = (green * alpha + 255 * opaque) / 255;
                    blue = (blue * alpha + 255 * opaque) / 255;
                }
                dst[offset++] = (byte) red;
                dst[offset++] = (byte) green;
                dst[offset++] = (byte) blue;
            }
        }
        return dst;
    }

    /**
     * 打包为 8 位灰度缓冲。
     *
     * @param image  源图，须为灰度类型
     * @param width  宽
     * @param height 高
     * @return 灰度字节
     */
    private static byte[] packGray(BufferedImage image, int width, int height) {
        byte[] dst = new byte[width * height];
        Raster raster = image.getRaster();
        int[] samples = new int[width];
        for (int y = 0; y < height; y++) {
            raster.getSamples(0, y, width, 1, 0, samples);
            int base = y * width;
            for (int x = 0; x < width; x++) {
                dst[base + x] = (byte) samples[x];
            }
        }
        return dst;
    }
}
