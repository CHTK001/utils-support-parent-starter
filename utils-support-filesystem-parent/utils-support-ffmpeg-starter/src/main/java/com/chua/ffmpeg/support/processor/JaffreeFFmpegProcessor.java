package com.chua.ffmpeg.support.processor;

import com.chua.common.support.media.ffmpeg.*;
import com.chua.common.support.spi.annotations.Spi;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import com.github.kokorin.jaffree.ffprobe.Stream;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jaffree 实现的 FFmpeg 处理器。
 *
 * <p>基于 jaffree 库封装 FFmpeg 命令行调用，提供完整的音视频处理功能。
 * SPI 名称 {@code "jaffree"}，优先级高于其他实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("jaffree")
public class JaffreeFFmpegProcessor extends AbstractFFmpegProcessor {

    private Path getBinDir() {
        return ffmpegFile.getParentFile().toPath();
    }

    @Override
    public void convertVideo(File input, File output, String targetFormat) throws IOException {
        convertVideo(input, output, targetFormat, FFmpegOptions.defaultOptions());
    }

    @Override
    public void convertVideo(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        output.getParentFile().mkdirs();
        String[] codecArgs = buildCodecArgs(options);
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(input.toPath()))
                .addOutput(buildOutput(output.toPath(), null, codecArgs))
                .execute();
    }

    @Override
    public void convertVideo(InputStream inputStream, OutputStream outputStream,
                             String inputFormat, String outputFormat) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        FFmpeg.atPath(getBinDir())
                .addInput(com.github.kokorin.jaffree.ffmpeg.PipeInput.pumpFrom(inputStream).setFormat(inputFormat))
                .addOutput(com.github.kokorin.jaffree.ffmpeg.PipeOutput.pumpTo(outputStream).setFormat(outputFormat))
                .execute();
    }

    @Override
    public void convertAudio(File input, File output, String targetFormat) throws IOException {
        convertAudio(input, output, targetFormat, FFmpegOptions.defaultOptions());
    }

    @Override
    public void convertAudio(File input, File output, String targetFormat, FFmpegOptions options) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        output.getParentFile().mkdirs();
        String[] codecArgs = buildCodecArgs(options);
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(input.toPath()))
                .addOutput(buildOutput(output.toPath(), resolvFormat(targetFormat, options), codecArgs))
                .execute();
    }

    @Override
    public void extractAudio(File videoInput, File audioOutput, String audioFormat) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        audioOutput.getParentFile().mkdirs();
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(videoInput.toPath()))
                .addOutput(buildOutput(audioOutput.toPath(), audioFormat, "-vn"))
                .execute();
    }

    @Override
    public void captureFrame(File videoInput, File imageOutput, double timestamp) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        imageOutput.getParentFile().mkdirs();
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(videoInput.toPath()).setPosition((long)(timestamp * 1000)))
                .addOutput(buildOutput(imageOutput.toPath(), resolvFormat(imageOutput), "-vframes", "1"))
                .execute();
    }

    @Override
    public File[] captureFrames(File videoInput, File outputDir, double interval, String imageFormat) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        outputDir.mkdirs();
        String pattern = new File(outputDir, "frame_%05d." + imageFormat).getAbsolutePath();
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(videoInput.toPath()))
                .addOutput(UrlOutput.toUrl(pattern).addArguments("-vf", "fps=1/" + interval))
                .execute();
        return outputDir.listFiles((d, n) -> n.startsWith("frame_") && n.endsWith("." + imageFormat));
    }

    @Override
    public void generateThumbnail(File videoInput, File imageOutput, int width, int height) throws IOException {
        double duration = getDuration(videoInput);
        captureFrame(videoInput, imageOutput, duration / 2);
    }

    @Override
    public void trim(File input, File output, double startTime, double duration) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        output.getParentFile().mkdirs();
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(input.toPath())
                        .setPosition((long)(startTime * 1000))
                        .setDuration((long)(duration * 1000)))
                .addOutput(buildOutput(output.toPath(), resolvFormat(output), "-c", "copy"))
                .execute();
    }

    @Override
    public void concat(File[] inputs, File output) throws IOException {
        if (!available || inputs == null || inputs.length == 0) {
            return;
        }
        output.getParentFile().mkdirs();
        File listFile = File.createTempFile("concat_", ".txt");
        try (PrintWriter w = new PrintWriter(listFile)) {
            for (File f : inputs) {
                w.println("file '" + f.getAbsolutePath().replace("'", "'\\''") + "'");
            }
        }
        try {
            FFmpeg.atPath(getBinDir())
                    .addInput(UrlInput.fromPath(listFile.toPath()).addArguments("-f", "concat").addArguments("-safe", "0"))
                    .addOutput(buildOutput(output.toPath(), resolvFormat(output), "-c", "copy"))
                    .execute();
        } finally { listFile.delete(); }
    }

    @Override
    public void resize(File input, File output, int width, int height) throws IOException {
        FFmpegOptions opts = new FFmpegOptions();
        opts.setWidth(width); opts.setHeight(height);
        convertVideo(input, output, resolvFormat(output), opts);
    }

    @Override
    public void rotate(File input, File output, int angle) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        String filter;
        switch (angle) {
            case 90: filter = "transpose=1"; break;
            case 180: filter = "transpose=1,transpose=1"; break;
            case 270: filter = "transpose=2"; break;
            default: throw new IllegalArgumentException("Angle must be 90/180/270");
        }
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(input.toPath()))
                .addOutput(buildOutput(output.toPath(), resolvFormat(output), "-vf", filter))
                .execute();
    }

    @Override
    public void addWatermark(File videoInput, File watermarkFile, File output, int x, int y) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        output.getParentFile().mkdirs();
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(videoInput.toPath()))
                .addInput(UrlInput.fromPath(watermarkFile.toPath()))
                .addOutput(buildOutput(output.toPath(), resolvFormat(output),
                        "-filter_complex", "overlay=" + x + ":" + y))
                .execute();
    }

    @Override
    public void videoToGif(File videoInput, File gifOutput, double startTime, double duration,
                           int width, int fps) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        gifOutput.getParentFile().mkdirs();
        String filter = "fps=" + fps + ",scale=" + width + ":-1:flags=lanczos";
        FFmpeg.atPath(getBinDir())
                .addInput(UrlInput.fromPath(videoInput.toPath())
                        .setPosition((long)(startTime * 1000))
                        .setDuration((long)(duration * 1000)))
                .addOutput(buildOutput(gifOutput.toPath(), resolvFormat(gifOutput), "-vf", filter))
                .execute();
    }

    @Override
    public void imagesToVideo(File imageDir, File videoOutput, int fps, String imagePattern) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        videoOutput.getParentFile().mkdirs();
        String pattern = new File(imageDir, imagePattern).getAbsolutePath();
        FFmpeg.atPath(getBinDir())
                .addInput(com.github.kokorin.jaffree.ffmpeg.UrlInput.fromUrl(pattern)
                        .addArguments("-framerate", String.valueOf(fps)))
                .addOutput(buildOutput(videoOutput.toPath(), resolvFormat(videoOutput),
                        "-c:v", "libx264", "-pix_fmt", "yuv420p"))
                .execute();
    }

    @Override
    public void pushStream(String input, String streamUrl, FFmpegOptions options) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        FFmpeg ffmpeg = FFmpeg.atPath(getBinDir()).addInput(UrlInput.fromUrl(input));
        com.github.kokorin.jaffree.ffmpeg.UrlOutput out = com.github.kokorin.jaffree.ffmpeg.UrlOutput.toUrl(streamUrl);
        if (options != null && options.getVideoCodec() != null) {
            out.addArguments("-c:v", options.getVideoCodec());
        }
        if (options != null && options.getAudioCodec() != null) {
            out.addArguments("-c:a", options.getAudioCodec());
        }
        if (streamUrl.startsWith("rtmp://")) {
            out.setFormat("flv");
        }
        ffmpeg.addOutput(out).execute();
    }

    @Override
    public void pushStream(String input, String streamUrl, FFmpegOptions options,
                           Consumer<FrameInfo> callback) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        FFmpeg ffmpeg = FFmpeg.atPath(getBinDir()).addInput(UrlInput.fromUrl(input));
        com.github.kokorin.jaffree.ffmpeg.UrlOutput out = com.github.kokorin.jaffree.ffmpeg.UrlOutput.toUrl(streamUrl);
        if (options != null && options.getVideoCodec() != null) {
            out.addArguments("-c:v", options.getVideoCodec());
        }
        if (options != null && options.getAudioCodec() != null) {
            out.addArguments("-c:a", options.getAudioCodec());
        }
        if (streamUrl.startsWith("rtmp://")) {
            out.setFormat("flv");
        }
        if (callback != null) {
            ffmpeg.setProgressListener(progress -> {
                FrameInfo info = new FrameInfo();
                info.setFrameNumber(progress.getFrame());
                info.setTimestampMs(progress.getTimeMillis());
                callback.accept(info);
            });
        }
        ffmpeg.addOutput(out).execute();
    }

    @Override
    public void pullStream(String streamUrl, File output, double duration) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        output.getParentFile().mkdirs();
        com.github.kokorin.jaffree.ffmpeg.UrlInput input = UrlInput.fromUrl(streamUrl);
        if (duration > 0) {
            input.setDuration((long) (duration * 1000));
        }
        FFmpeg.atPath(getBinDir())
                .addInput(input)
                .addOutput(buildOutput(output.toPath(), resolvFormat(output), "-c", "copy"))
                .execute();
    }

    @Override
    public FFmpegMediaInfo getMediaInfo(File input) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        FFprobeResult result = FFprobe.atPath(getBinDir())
                .setShowStreams(true).setShowFormat(true).setInput(input.toPath()).execute();

        FFmpegMediaInfo info = new FFmpegMediaInfo();
        info.setFilename(input.getName());
        info.setDuration(result.getFormat().getDuration() != null ? result.getFormat().getDuration().doubleValue() : 0);
        info.setSize(result.getFormat().getSize() != null ? result.getFormat().getSize() : input.length());
        info.setBitrate(result.getFormat().getBitRate() != null ? result.getFormat().getBitRate() : 0);
        info.setFormatName(result.getFormat().getFormatName());
        info.setFormatLongName(result.getFormat().getFormatLongName());

        for (Stream s : result.getStreams()) {
            if ("video".equals(s.getCodecType())) {
                FFmpegMediaInfo.VideoStream vs = new FFmpegMediaInfo.VideoStream();
                vs.setIndex(s.getIndex()); vs.setCodec(s.getCodecName());
                vs.setWidth(s.getWidth()); vs.setHeight(s.getHeight());
                vs.setFps(s.getAvgFrameRate() != null ? s.getAvgFrameRate().doubleValue() : 0);
                vs.setBitrate(s.getBitRate() != null ? s.getBitRate() : 0);
                vs.setDuration(s.getDuration() != null ? s.getDuration().doubleValue() : 0);
                info.setVideoStream(vs);
            } else if ("audio".equals(s.getCodecType())) {
                FFmpegMediaInfo.AudioStream as = new FFmpegMediaInfo.AudioStream();
                as.setIndex(s.getIndex()); as.setCodec(s.getCodecName());
                as.setSampleRate(s.getSampleRate() != null ? s.getSampleRate() : 0);
                as.setChannels(s.getChannels() != null ? s.getChannels() : 0);
                as.setChannelLayout(s.getChannelLayout());
                as.setBitrate(s.getBitRate() != null ? s.getBitRate() : 0);
                as.setDuration(s.getDuration() != null ? s.getDuration().doubleValue() : 0);
                info.setAudioStream(as);
            }
        }
        return info;
    }

    @Override
    public double getDuration(File input) throws IOException {
        return getMediaInfo(input).getDuration();
    }

    @Override
    public String getVersion() {
        return "Jaffree FFmpeg Processor";
    }

    @Override
    public FFmpegResult execute(String... args) throws IOException {
        if (!available) {
            throw new IllegalStateException("FFmpeg unavailable: " + loadError);
        }
        long start = System.currentTimeMillis();
        AtomicBoolean ok = new AtomicBoolean(true);
        try {
            FFmpeg ffmpeg = FFmpeg.atPath(getBinDir());
            for (String a : args) {
                ffmpeg.addArgument(a);
            }
            ffmpeg.execute();
        } catch (Exception e) { ok.set(false); }
        FFmpegResult r = new FFmpegResult();
        r.setSuccess(ok.get());
        r.setExitCode(ok.get() ? 0 : -1);
        r.setExecutionTime(System.currentTimeMillis() - start);
        return r;
    }

    private UrlOutput buildOutput(java.nio.file.Path path, String format, String... extra) {
        UrlOutput out = UrlOutput.toPath(path);
        if (format != null) {
            out.setFormat(format);
        }
        for (String e : extra) {
            out.addArgument(e);
        }
        return out;
    }

    private String resolvFormat(File output) {
        String n = output.getName(); int d = n.lastIndexOf('.'); return d > 0 ? n.substring(d + 1) : null;
    }

    private String resolvFormat(String targetFormat, FFmpegOptions opts) {
        return targetFormat != null ? targetFormat : "mp3";
    }
}
