package com.chua.ffmpeg.rust.support.processor;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.media.ffmpeg.FFmpegOptions;
import com.chua.common.support.media.ffmpeg.FFmpegMediaInfo;
import com.chua.common.support.media.ffmpeg.FrameInfo;
import com.chua.ffmpeg.rust.support.bridge.RustFFmpegBridge;
import com.chua.nativeffmpeg.support.NativeFFmpeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
   * 基于原生 Rust ffmpeg 库的 ffmpeg 处理器。
 *
 * <p>本处理器通过 JNI 调用 Rust 原生 FFmpeg 实现，支持推流/拉流、
 * 文件转码、截帧、拼接、媒体信息查询等功能。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"rust", "native"}, order = 100)
public class RustFFmpegProcessor implements FFmpegProcessor {

    /** JSON 对象映射器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    /** 转换视频 */
    public void convertVideo(File input, File output, String targetFormat) throws IOException {
        convertVideo(input, output, targetFormat, null);
    }

    @Override
    /** 转换视频 */
    public void convertVideo(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        checkStreamAvailable();
        String videoCodec = options != null ? options.getVideoCodec() : null;
        String audioCodec = options != null ? options.getAudioCodec() : null;
        int width = options != null && options.getWidth() != null ? options.getWidth() : 0;
        int height = options != null && options.getHeight() != null ? options.getHeight() : 0;
        int fps = options != null && options.getFps() != null ? options.getFps() : 0;
        int ret = RustFFmpegBridge.convertFile(input.getAbsolutePath(), output.getAbsolutePath(),
                videoCodec, audioCodec, width, height, fps, 0, 0, false, false);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg convert video failed with code: " + ret);
        }
    }

    @Override
    /**
      * 转换视频
     * @param inputStream 输入流
     * @param outputStream 输出流
     * @param inputFormat 输入格式化
     * @param outputFormat 输出格式化
     */
    public void convertVideo(java.io.InputStream inputStream, java.io.OutputStream outputStream,
                             String inputFormat, String outputFormat) throws IOException {
        checkStreamAvailable();
        File tempInput = File.createTempFile("ffmpeg_rust_input_", "." + inputFormat);
        File tempOutput = File.createTempFile("ffmpeg_rust_output_", "." + outputFormat);
        try {
 // 将 输入流 写入临时文件
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempInput)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
            // 调用文件转码
            int ret = RustFFmpegBridge.convertFile(tempInput.getAbsolutePath(), tempOutput.getAbsolutePath(),
                    null, null, 0, 0, 0, 0, 0, false, false);
            if (ret != 0) {
                throw new IOException("Rust FFmpeg stream conversion failed with code: " + ret);
            }
 // 将临时输出文件写入 输出流
            try (java.io.FileInputStream fis = new java.io.FileInputStream(tempOutput)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, len);
                }
            }
        } finally {
            tempInput.delete();
            tempOutput.delete();
        }
    }

    @Override
    /** 转换音频 */
    public void convertAudio(File input, File output, String targetFormat) throws IOException {
        convertAudio(input, output, targetFormat, null);
    }

    @Override
    /** 转换音频 */
    public void convertAudio(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        checkStreamAvailable();
        String audioCodec = options != null ? options.getAudioCodec() : null;
        int ret = RustFFmpegBridge.convertFile(input.getAbsolutePath(), output.getAbsolutePath(),
                null, audioCodec, 0, 0, 0, 0, 0, true, false);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg convert audio failed with code: " + ret);
        }
    }

    @Override
    /** extract音频 */
    public void extractAudio(File videoInput, File audioOutput, String audioFormat) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.convertFile(videoInput.getAbsolutePath(), audioOutput.getAbsolutePath(),
                null, null, 0, 0, 0, 0, 0, true, false);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg extract audio failed with code: " + ret);
        }
    }

    @Override
    /** capture帧 */
    public void captureFrame(File videoInput, File imageOutput, double timestamp) throws IOException {
        checkStreamAvailable();
        long timestampMs = (long) (timestamp * 1000);
        int ret = RustFFmpegBridge.captureFrame(videoInput.getAbsolutePath(), timestampMs, imageOutput.getAbsolutePath());
        if (ret != 0) {
            throw new IOException("Rust FFmpeg capture frame failed with code: " + ret);
        }
    }

    @Override
    /** capture帧 */
    public File[] captureFrames(File videoInput, File outputDir, double interval, String imageFormat) throws IOException {
        checkStreamAvailable();
        double duration = getDuration(videoInput);
        if (duration <= 0) {
            throw new IOException("Cannot determine video duration for frame capture");
        }
        java.util.List<File> frames = new java.util.ArrayList<>();
        double timestamp = 0;
        int index = 0;
        String ext = imageFormat != null ? imageFormat.toLowerCase() : "jpg";
        while (timestamp < duration) {
            File outputFile = new File(outputDir, String.format("frame_%06d.%s", index, ext));
            captureFrame(videoInput, outputFile, timestamp);
            frames.add(outputFile);
            timestamp += interval;
            index++;
        }
        return frames.toArray(new File[0]);
    }

    @Override
    /** generatethumbnail */
    public void generateThumbnail(File videoInput, File imageOutput, int width, int height) throws IOException {
        captureFrame(videoInput, imageOutput, 0);
    }

    @Override
    /** 去空格 */
    public void trim(File input, File output, double startTime, double duration) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.convertFile(input.getAbsolutePath(), output.getAbsolutePath(),
                null, null, 0, 0, 0, startTime, duration, false, false);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg trim failed with code: " + ret);
        }
    }

    @Override
    /** 连接 */
    public void concat(File[] inputs, File output) throws IOException {
        checkStreamAvailable();
        if (inputs == null || inputs.length == 0) {
            throw new IOException("No input files provided for concatenation");
        }
        StringBuilder sb = new StringBuilder();
        for (File f : inputs) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(f.getAbsolutePath());
        }
        int ret = RustFFmpegBridge.concatFiles(sb.toString(), output.getAbsolutePath());
        if (ret != 0) {
            throw new IOException("Rust FFmpeg concat failed with code: " + ret);
        }
    }

    @Override
    /** 调整大小 */
    public void resize(File input, File output, int width, int height) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.convertFile(input.getAbsolutePath(), output.getAbsolutePath(),
                null, null, width, height, 0, 0, 0, false, false);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg resize failed with code: " + ret);
        }
    }

    @Override
    /** Rotate */
    public void rotate(File input, File output, int angle) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.rotate(input.getAbsolutePath(), output.getAbsolutePath(), angle);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg rotate failed with code: " + ret);
        }
    }

    @Override
    /** 添加Watermark */
    public void addWatermark(File videoInput, File watermarkFile, File output, int x, int y) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.addWatermark(videoInput.getAbsolutePath(), watermarkFile.getAbsolutePath(),
                output.getAbsolutePath(), x, y);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg add watermark failed with code: " + ret);
        }
    }

    @Override
    /**
      * 视频转为gif
     * @param videoInput 视频输入
     * @param gifOutput gif输出
     * @param startTime 启动时间
     * @param duration 持续时间
     * @param width width
     * @param fps fps
     */
    public void videoToGif(File videoInput, File gifOutput, double startTime, double duration,
                           int width, int fps) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.convertFile(videoInput.getAbsolutePath(), gifOutput.getAbsolutePath(),
                "gif", null, width, 0, fps, startTime, duration, false, true);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg video to GIF failed with code: " + ret);
        }
    }

    @Override
    /** 镜像转为视频 */
    public void imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern) throws IOException {
        checkStreamAvailable();
        String pattern = imagePattern != null ? imagePattern : "%06d.jpg";
        int ret = RustFFmpegBridge.imagesToVideo(imageDir.getAbsolutePath(), videoOutput.getAbsolutePath(), fps, pattern);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg images to video failed with code: " + ret);
        }
    }

    @Override
    /** 推送流 */
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
    /**
     * 推送流式输出
     * @param input 输入
     * @param streamUrl 流url
     * @param options 期权
     * @param callback callback
     */
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
    /**
      * 推送流式输出设置帧
     * @param input 输入
     * @param streamUrl 流url
     * @param options 期权
     * @param callback callback
     */
    public void pushStreamWithFrames(String input, String streamUrl, FFmpegOptions options,
                                     Consumer<FrameInfo> callback) throws IOException {
        pushStream(input, streamUrl, options, callback);
    }

    @Override
    /** 拉取流 */
    public void pullStream(String streamUrl, File output, double duration) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.pullStream(streamUrl, output.getAbsolutePath(), duration);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg pull stream failed with code: " + ret);
        }
    }

    @Override
    /**
     * 拉取流式输出
     * @param streamUrl 流url
     * @param output 输出
     * @param duration 持续时间
     * @param callback callback
     */
    public void pullStream(String streamUrl, File output, double duration,
                           Consumer<FrameInfo> callback) throws IOException {
        checkStreamAvailable();
        int ret = RustFFmpegBridge.pullStreamWithCallback(streamUrl, output.getAbsolutePath(), duration, callback);
        if (ret != 0) {
            throw new IOException("Rust FFmpeg pull stream failed with code: " + ret);
        }
    }

    @Override
    /**
      * 拉取流式输出设置帧
     * @param streamUrl 流url
     * @param output 输出
     * @param duration 持续时间
     * @param callback callback
     */
    public void pullStreamWithFrames(String streamUrl, File output, double duration,
                                     Consumer<FrameInfo> callback) throws IOException {
        pullStream(streamUrl, output, duration, callback);
    }

    @Override
    /** 获取media信息 */
    public FFmpegMediaInfo getMediaInfo(File input) throws IOException {
        checkStreamAvailable();
        String json = RustFFmpegBridge.getStreamMediaInfo(input.getAbsolutePath());
        if (json == null || json.isEmpty()) {
            throw new IOException("Failed to get media info for: " + input.getAbsolutePath());
        }
        return parseMediaInfo(json);
    }

    @Override
    /** 获取持续时间 */
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
    /** 是否可用 */
    public boolean isAvailable() {
        return RustFFmpegBridge.isLoaded() || RustFFmpegBridge.isStreamLoaded();
    }

    /** 校验流可用 */
    private void checkStreamAvailable() throws IOException {
        if (!RustFFmpegBridge.isStreamLoaded()) {
            throw new IOException("Rust FFmpeg stream library not loaded. " +
                    "Ensure ffmpeg-rust native library is available. Error: " + NativeFFmpeg.getLoadError());
        }
    }

    /**
     * 解析media信息
     *
     * @param json json
     * @return 解析media信息的结果
     */
    private FFmpegMediaInfo parseMediaInfo(String json) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        FFmpegMediaInfo info = new FFmpegMediaInfo();
        if (root.has("formatName")) {
            info.setFormatName(root.get("formatName").asText());
        }
        if (root.has("formatLongName")) {
            info.setFormatLongName(root.get("formatLongName").asText());
        }
        if (root.has("duration")) {
            info.setDuration(root.get("duration").asDouble());
        }
        if (root.has("bitrate")) {
            info.setBitrate(root.get("bitrate").asLong());
        }

        if (root.has("videoStream")) {
            JsonNode vs = root.get("videoStream");
            FFmpegMediaInfo.VideoStream video = new FFmpegMediaInfo.VideoStream();
            if (vs.has("index")) {
                video.setIndex(vs.get("index").asInt());
            }
            if (vs.has("codec")) {
                video.setCodec(vs.get("codec").asText());
            }
            if (vs.has("codecLongName")) {
                video.setCodecLongName(vs.get("codecLongName").asText());
            }
            if (vs.has("width")) {
                video.setWidth(vs.get("width").asInt());
            }
            if (vs.has("height")) {
                video.setHeight(vs.get("height").asInt());
            }
            if (vs.has("fps")) {
                video.setFps(vs.get("fps").asDouble());
            }
            if (vs.has("bitrate")) {
                video.setBitrate(vs.get("bitrate").asLong());
            }
            if (vs.has("duration")) {
                video.setDuration(vs.get("duration").asDouble());
            }
            info.setVideoStream(video);
        }

        if (root.has("audioStream")) {
            JsonNode as = root.get("audioStream");
            FFmpegMediaInfo.AudioStream audio = new FFmpegMediaInfo.AudioStream();
            if (as.has("index")) {
                audio.setIndex(as.get("index").asInt());
            }
            if (as.has("codec")) {
                audio.setCodec(as.get("codec").asText());
            }
            if (as.has("codecLongName")) {
                audio.setCodecLongName(as.get("codecLongName").asText());
            }
            if (as.has("sampleRate")) {
                audio.setSampleRate(as.get("sampleRate").asInt());
            }
            if (as.has("channels")) {
                audio.setChannels(as.get("channels").asInt());
            }
            if (as.has("bitrate")) {
                audio.setBitrate(as.get("bitrate").asLong());
            }
            if (as.has("duration")) {
                audio.setDuration(as.get("duration").asDouble());
            }
            info.setAudioStream(audio);
        }

        return info;
    }

    @Override
    /** 获取版本 */
    public String getVersion() {
        return RustFFmpegBridge.getVersion();
    }

    @Override
    /** 执行 */
    public com.chua.common.support.media.ffmpeg.FFmpegResult execute(String... args) throws IOException {
        com.chua.common.support.media.ffmpeg.FFmpegResult result = new com.chua.common.support.media.ffmpeg.FFmpegResult();
        if (!isAvailable()) {
            result.setSuccess(false);
            result.setStderr("Rust native libraries not loaded: " + NativeFFmpeg.getLoadError());
            return result;
        }
        result.setSuccess(false);
        result.setStderr("Rust FFmpeg processor does not support custom command execution. " +
                "Use javacv-starter or jaffree-starter for CLI-style operations.");
        return result;
    }
}