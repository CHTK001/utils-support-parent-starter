package com.chua.ffmpeg.rust.support.processor;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.media.ffmpeg.FFmpegOptions;
import com.chua.common.support.media.ffmpeg.FrameInfo;
import com.chua.ffmpeg.rust.support.bridge.RustFFmpegBridge;
import com.chua.nativeffmpeg.support.NativeFFmpeg;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * 基于原生 Rust 视频编码和加密库的 FFmpeg 处理器。
 *
 * <p>本处理器专注于通过原生 JNI 进行视频编解码操作。
 * 完整的基于文件的转码请使用 javacv-starter 或 jaffree-starter。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"rust", "native"}, order = 100)
public class RustFFmpegProcessor implements FFmpegProcessor {

    /**
     * 不支持的默认错误信息
     */
    private static final String UNSUPPORTED_ERROR = "Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for file-based transcoding.";

    /**
     * 仅支持视频编解码的提示
     */
    private static final String CODEC_ONLY_ERROR = "Rust FFmpeg processor only supports video codec encode/decode via VideoEncoder/VideoDecoder";

    @Override
    public void convertVideo(File input, File output, String targetFormat) throws IOException {
        throw new UnsupportedOperationException(UNSUPPORTED_ERROR);
    }

    @Override
    public void convertVideo(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        throw new UnsupportedOperationException(UNSUPPORTED_ERROR);
    }

    @Override
    public void convertVideo(java.io.InputStream inputStream, java.io.OutputStream outputStream,
                             String inputFormat, String outputFormat) throws IOException {
        throw new UnsupportedOperationException(UNSUPPORTED_ERROR);
    }

    @Override
    public void convertAudio(File input, File output, String targetFormat) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for audio processing.");
    }

    @Override
    public void convertAudio(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for audio processing.");
    }

    @Override
    public void extractAudio(File videoInput, File audioOutput, String audioFormat) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for audio extraction.");
    }

    @Override
    public void captureFrame(File videoInput, File imageOutput, double timestamp) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for frame extraction.");
    }

    @Override
    public File[] captureFrames(File videoInput, File outputDir, double interval, String imageFormat) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for frame extraction.");
    }

    @Override
    public void generateThumbnail(File videoInput, File imageOutput, int width, int height) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for thumbnail generation.");
    }

    @Override
    public void trim(File input, File output, double startTime, double duration) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for video trimming.");
    }

    @Override
    public void concat(File[] inputs, File output) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for video concatenation.");
    }

    @Override
    public void resize(File input, File output, int width, int height) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for video resizing.");
    }

    @Override
    public void rotate(File input, File output, int angle) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for video rotation.");
    }

    @Override
    public void addWatermark(File videoInput, File watermarkFile, File output, int x, int y) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for watermarking.");
    }

    @Override
    public void videoToGif(File videoInput, File gifOutput, double startTime, double duration,
                           int width, int fps) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for GIF creation.");
    }

    @Override
    public void imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for image-to-video.");
    }

    @Override
    public void pushStream(String input, String streamUrl, FFmpegOptions options) throws IOException {
        checkStreamAvailable();
        String videoCodec = options != null ? options.getVideoCodec() : null;
        String audioCodec = options != null ? options.getAudioCodec() : null;
        int width = options != null && options.getWidth() != null ? options.getWidth() : 0;
        int height = options != null && options.getHeight() != null ? options.getHeight() : 0;
        int fps = options != null && options.getFps() != null ? options.getFps() : 0;
        int ret = RustFFmpegBridge.pushStream(input, streamUrl, videoCodec, audioCodec, width, height, fps);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg push stream failed with code: " + ret);
        }
    }

    @Override
    public void pushStream(String input, String streamUrl, FFmpegOptions options,
                           Consumer<FrameInfo> callback) throws IOException {
        checkStreamAvailable();
        String videoCodec = options != null ? options.getVideoCodec() : null;
        String audioCodec = options != null ? options.getAudioCodec() : null;
        int width = options != null && options.getWidth() != null ? options.getWidth() : 0;
        int height = options != null && options.getHeight() != null ? options.getHeight() : 0;
        int fps = options != null && options.getFps() != null ? options.getFps() : 0;
        int ret = RustFFmpegBridge.pushStreamWithCallback(input, streamUrl, videoCodec, audioCodec,
                width, height, fps, callback);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg push stream failed with code: " + ret);
        }
    }

    @Override
    public void pushStreamWithFrames(String input, String streamUrl, FFmpegOptions options,
                                     Consumer<FrameInfo> callback) throws IOException {
        // Rust 原生推流不支持帧图像数据返回，回退到普通回调
        pushStream(input, streamUrl, options, callback);
    }

    @Override
    public void pullStream(String streamUrl, File output, double duration) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.pullStream(streamUrl, output.getAbsolutePath(), duration);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg pull stream failed with code: " + ret);
        }
    }

    @Override
    public void pullStream(String streamUrl, File output, double duration,
                           Consumer<FrameInfo> callback) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.pullStreamWithCallback(streamUrl, output.getAbsolutePath(), duration, callback);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg pull stream failed with code: " + ret);
        }
    }

    @Override
    public void pullStreamWithFrames(String streamUrl, File output, double duration,
                                     Consumer<FrameInfo> callback) throws IOException {
        // Rust 原生拉流不支持帧图像数据返回，回退到普通回调
        pullStream(streamUrl, output, duration, callback);
    }

    @Override
    public com.chua.common.support.media.ffmpeg.FFmpegMediaInfo getMediaInfo(File input) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for media info.");
    }

    @Override
    public double getDuration(File input) throws IOException {
        if (!RustFFmpegBridge.isStreamLoaded()) {
            throw new UnsupportedOperationException("NativeFFmpeg library not loaded");
        }
        double duration = RustFFmpegBridge.getStreamDuration(input.getAbsolutePath());
        if (duration < 0) {
            throw new IOException("Failed to get duration for: " + input.getAbsolutePath());
        }
        return duration;
    }

    @Override
    public boolean isAvailable() {
        return RustFFmpegBridge.isLoaded() || RustFFmpegBridge.isStreamLoaded();
    }

    /**
     * 检查推流/拉流原生库是否可用。
     */
    private void checkStreamAvailable() throws IOException {
        if (!RustFFmpegBridge.isStreamLoaded()) {
            throw new IOException("Rust FFmpeg stream library not loaded. " +
                    "Ensure ffmpeg-rust native library is available. Error: " + NativeFFmpeg.getLoadError());
        }
    }

    @Override
    public String getVersion() {
        return RustFFmpegBridge.getVersion();
    }

    @Override
    public com.chua.common.support.media.ffmpeg.FFmpegResult execute(String... args) throws IOException {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("Rust native libraries not loaded");
        }
        com.chua.common.support.media.ffmpeg.FFmpegResult result = new com.chua.common.support.media.ffmpeg.FFmpegResult();
        result.setSuccess(false);
        result.setStdout("");
        result.setStderr(CODEC_ONLY_ERROR);
        return result;
    }
}
