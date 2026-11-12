package com.chua.image.support.turbojpeg;

import com.chua.common.support.nativeapi.NativeServices;
import com.chua.common.support.nativeapi.TurboJpegSpi;

/**
 * libjpeg-turbo 原生库门面。
 *
 * <p>实现由 {@code utils-support-native-libjpeg-turbo} 通过 SPI 注册；本类只依赖
 * {@link TurboJpegSpi} 契约，不再反射 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class TurboJpegBridge {

    private static final String DEPENDENCY = "com.chua:utils-support-native-libjpeg-turbo";

    static final int TJPF_RGB = TurboJpegSpi.TJPF_RGB;
    static final int TJPF_GRAY = TurboJpegSpi.TJPF_GRAY;
    static final int TJSAMP_420 = TurboJpegSpi.TJSAMP_420;
    static final int TJSAMP_GRAY = TurboJpegSpi.TJSAMP_GRAY;

    private TurboJpegBridge() {
    }

    record Decoded(byte[] pixels, int width, int height, int pitch) {
    }

    static void load() {
        TurboJpegSpi spi = NativeServices.get(TurboJpegSpi.class);
        if (spi != null) {
            spi.load();
        }
    }

    static boolean isLoaded() {
        TurboJpegSpi spi = NativeServices.get(TurboJpegSpi.class);
        return spi != null && spi.isLoaded();
    }

    static Throwable getLoadError() {
        TurboJpegSpi spi = NativeServices.get(TurboJpegSpi.class);
        return spi == null ? new IllegalStateException("请自行添加依赖 " + DEPENDENCY) : spi.getLoadError();
    }

    static int[] probe(byte[] jpeg) {
        return require().probe(jpeg);
    }

    static byte[] compress(byte[] pixels, int width, int height, int pitch, int pixelFormat,
                           int quality, int subsamp, int flags) {
        return require().compress(pixels, width, height, pitch, pixelFormat, quality, subsamp, flags);
    }

    static Decoded decompress(byte[] jpeg, int pixelFormat, int flags) {
        TurboJpegSpi.Decoded decoded = require().decompress(jpeg, pixelFormat, flags);
        return decoded == null ? null : new Decoded(decoded.pixels(), decoded.width(),
                decoded.height(), decoded.pitch());
    }

    private static TurboJpegSpi require() {
        return NativeServices.require(TurboJpegSpi.class, "libjpeg-turbo native 能力");
    }
}
