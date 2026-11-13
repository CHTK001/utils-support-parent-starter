package com.chua.image.support.compression.engine;

import com.chua.common.support.nativeapi.ImageOptSpi;
import com.chua.common.support.nativeapi.NativeServices;

/**
 * 图片压缩原生库门面。
 *
 * <p>实现由 {@code utils-support-native-imageopt} 通过 SPI 注册；本类只依赖
 * {@link ImageOptSpi} 契约，不再反射 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class ImageOptBridge {

    private static final String DEPENDENCY = "com.chua:utils-support-native-imageopt";

    private ImageOptBridge() {
    }

    record Quantized(byte[] palette, int colors, byte[] indices) {
    }

    static void load() {
        ImageOptSpi spi = NativeServices.get(ImageOptSpi.class);
        if (spi != null) {
            spi.load();
        }
    }

    static boolean isLoaded() {
        ImageOptSpi spi = NativeServices.get(ImageOptSpi.class);
        return spi != null && spi.isLoaded();
    }

    static String lastError() {
        ImageOptSpi spi = NativeServices.get(ImageOptSpi.class);
        return spi == null ? "未引入依赖 " + DEPENDENCY : spi.lastError();
    }

    static byte[] zlibCompress(byte[] data, int level) {
        return require().zlibCompress(data, level);
    }

    // [P3C MJ-方法注释 豁免]
    static Quantized quantize(byte[] rgba, int width, int height, int maxColors, int ditherLevel, int speed) {
        ImageOptSpi.Quantized quantized = require().quantize(rgba, width, height, maxColors,
                ditherLevel, speed);
        return quantized == null ? null
                : new Quantized(quantized.palette(), quantized.colors(), quantized.indices());
    }

    // [P3C MJ-方法注释 豁免]
    private static ImageOptSpi require() {
        return NativeServices.require(ImageOptSpi.class, "图片压缩 native 能力");
    }
}
