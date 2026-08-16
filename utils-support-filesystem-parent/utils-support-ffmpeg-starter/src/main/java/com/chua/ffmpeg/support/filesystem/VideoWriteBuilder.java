package com.chua.ffmpeg.support.filesystem;

import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.media.ffmpeg.FFmpegOptions;
import com.chua.common.support.media.ffmpeg.FFmpegProcessor;

import java.io.File;

/**
 * 视频文件写入构建器。
 *
 * <p>基于 FFmpeg 实现视频格式转换，支持编码器、码率、分辨率、帧率等参数配置。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoWriteBuilder extends WriteBuilder {

    private final FFmpegProcessor processor;
    private String videoCodec;
    private String audioCodec;
    private String videoBitrate;
    private String audioBitrate;
    private Integer fps;
    private Integer width;
    private Integer height;
    private Integer quality;

    public VideoWriteBuilder(File file, FFmpegProcessor processor) {
        super(file);
        this.processor = processor;
    }

    /**
     * 设置视频编码器（libx264, libx265, h264_nvenc）
     */
    public VideoWriteBuilder videoCodec(String codec) {
        this.videoCodec = codec;
        return this;
    }

    /**
     * 设置音频编码器（aac, mp3, libopus）
     */
    public VideoWriteBuilder audioCodec(String codec) {
        this.audioCodec = codec;
        return this;
    }

    /**
     * 设置视频码率（如 "2M", "5000k"）
     */
    public VideoWriteBuilder videoBitrate(String bitrate) {
        this.videoBitrate = bitrate;
        return this;
    }

    /**
     * 设置音频码率（如 "128k", "320k"）
     */
    public VideoWriteBuilder audioBitrate(String bitrate) {
        this.audioBitrate = bitrate;
        return this;
    }

    /**
     * 设置帧率
     */
    public VideoWriteBuilder fps(int fps) {
        this.fps = fps;
        return this;
    }

    /**
     * 设置分辨率
     */
    public VideoWriteBuilder resolution(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * 设置 CRF 质量（0-51，越小质量越高）
     */
    public VideoWriteBuilder quality(int quality) {
        this.quality = quality;
        return this;
    }

    /**
     * 执行视频格式转换。
     *
     * @param inputFile 源视频文件
     */
    public void write(File inputFile) {
        try {
            if (processor == null) {
                throw new IllegalStateException("FFmpeg processor not available");
            }
            FFmpegOptions opts = FFmpegOptions.defaultOptions();
            opts.setVideoCodec(videoCodec);
            opts.setAudioCodec(audioCodec);
            opts.setVideoBitrate(videoBitrate);
            opts.setAudioBitrate(audioBitrate);
            opts.setFps(fps);
            opts.setWidth(width);
            opts.setHeight(height);
            opts.setQuality(quality);
            processor.convertVideo(inputFile, file, null, opts);
        } catch (Exception e) {
            throw new RuntimeException("Video conversion failed", e);
        }
    }
}
