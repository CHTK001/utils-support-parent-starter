package com.chua.ffmpeg.rust.support.processor;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.media.ffmpeg.FFmpegOptions;
import com.chua.ffmpeg.rust.support.bridge.RustFFmpegBridge;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

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
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for streaming.");
    }

    @Override
    public void pullStream(String streamUrl, File output, double duration) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for stream pulling.");
    }

    @Override
    public com.chua.common.support.media.ffmpeg.FFmpegMediaInfo getMediaInfo(File input) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for media info.");
    }

    @Override
    public double getDuration(File input) throws IOException {
        throw new UnsupportedOperationException("Rust FFmpeg processor focuses on video codec encode/decode. Use javacv-starter for duration.");
    }

    @Override
    public boolean isAvailable() {
        return RustFFmpegBridge.isLoaded();
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
