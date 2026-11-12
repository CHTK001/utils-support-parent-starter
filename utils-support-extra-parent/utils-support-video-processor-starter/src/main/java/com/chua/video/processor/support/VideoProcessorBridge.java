package com.chua.video.processor.support;

import com.chua.common.support.nativeapi.NativeServices;
import com.chua.common.support.nativeapi.VideoProcessorSpi;

/**
 * 视频处理原生库门面。
 *
 * <p>实现由 {@code utils-support-native-video-processor} 通过 SPI 注册；本类只依赖
 * {@link VideoProcessorSpi} 契约，不再反射 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class VideoProcessorBridge {

    private static final String CAPABILITY = "视频处理原生能力";

    private VideoProcessorBridge() {
    }

    static boolean isLoaded() {
        VideoProcessorSpi spi = NativeServices.get(VideoProcessorSpi.class);
        return spi != null && spi.isLoaded();
    }

    static void ensureLoaded() {
        require().ensureLoaded();
    }

    static boolean transcodeToHls(String inputPath, String outputDir) {
        return require().transcodeToHls(inputPath, outputDir);
    }

    static String getVersion() {
        return require().getVersion();
    }

    private static VideoProcessorSpi require() {
        return NativeServices.require(VideoProcessorSpi.class, CAPABILITY);
    }
}
