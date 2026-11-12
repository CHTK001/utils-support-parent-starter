package com.chua.ffmpeg.rust.support.processor;

import com.chua.common.support.nativeapi.FFmpegNativeSpi;
import com.chua.common.support.nativeapi.NativeServices;

/**
 * FFmpeg Rust native 库门面。
 *
 * <p>实现由 {@code utils-support-native-ffmpeg} 通过 SPI 注册；本类只依赖
 * {@link FFmpegNativeSpi} 契约，不再反射 native 类与回调接口。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class NativeFFmpeg {

    private static final String CAPABILITY = "ffmpeg 原生能力";
    private static final String DEPENDENCY = "com.chua:utils-support-native-ffmpeg";

    private NativeFFmpeg() {
    }

    @FunctionalInterface
    interface FrameCallback {

        void onFrame(long frameNumber, long timestampMs, int width, int height,
                     String codec, double fps, boolean keyFrame);
    }

    static boolean isLoaded() {
        FFmpegNativeSpi spi = NativeServices.get(FFmpegNativeSpi.class);
        return spi != null && spi.available();
    }

    static Throwable getLoadError() {
        FFmpegNativeSpi spi = NativeServices.get(FFmpegNativeSpi.class);
        return spi == null ? new IllegalStateException("请自行添加依赖 " + DEPENDENCY) : spi.getLoadError();
    }

    static String getVersion() {
        return require().getVersion();
    }

    static int pushStream(String inputUrl, String streamUrl, String videoCodec, String audioCodec,
                          int width, int height, int fps) {
        return require().pushStream(inputUrl, streamUrl, videoCodec, audioCodec, width, height, fps);
    }

    static int pushStreamWithCallback(String inputUrl, String streamUrl, String videoCodec,
                                      String audioCodec, int width, int height, int fps,
                                      FrameCallback callback) {
        return require().pushStreamWithCallback(inputUrl, streamUrl, videoCodec, audioCodec,
                width, height, fps, toNative(callback));
    }

    static int pullStream(String streamUrl, String outputPath, double duration) {
        return require().pullStream(streamUrl, outputPath, duration);
    }

    static int pullStreamWithCallback(String streamUrl, String outputPath, double duration,
                                      FrameCallback callback) {
        return require().pullStreamWithCallback(streamUrl, outputPath, duration, toNative(callback));
    }

    static double getDuration(String inputUrl) {
        return require().getDuration(inputUrl);
    }

    static int convertFile(String inputUrl, String outputPath, String videoCodec, String audioCodec,
                           int width, int height, int fps, double startTime, double duration,
                           boolean removeVideo, boolean removeAudio) {
        return require().convertFile(inputUrl, outputPath, videoCodec, audioCodec, width, height,
                fps, startTime, duration, removeVideo, removeAudio);
    }

    static int captureFrame(String inputUrl, long timestampMs, String outputPath) {
        return require().captureFrame(inputUrl, timestampMs, outputPath);
    }

    static int concatFiles(String inputPaths, String outputPath) {
        return require().concatFiles(inputPaths, outputPath);
    }

    static String getMediaInfo(String inputUrl) {
        return require().getMediaInfo(inputUrl);
    }

    static int rotate(String inputUrl, String outputPath, int angle) {
        return require().rotate(inputUrl, outputPath, angle);
    }

    static int addWatermark(String inputUrl, String watermarkPath, String outputPath, int x, int y) {
        return require().addWatermark(inputUrl, watermarkPath, outputPath, x, y);
    }

    static int imagesToVideo(String imageDir, String outputPath, int fps, String imagePattern) {
        return require().imagesToVideo(imageDir, outputPath, fps, imagePattern);
    }

    private static FFmpegNativeSpi require() {
        return NativeServices.require(FFmpegNativeSpi.class, CAPABILITY);
    }

    private static FFmpegNativeSpi.FrameCallback toNative(FrameCallback callback) {
        if (callback == null) {
            return null;
        }
        return (frameNumber, timestampMs, width, height, codec, fps, keyFrame) ->
                callback.onFrame(frameNumber, timestampMs, width, height, codec, fps, keyFrame);
    }
}
