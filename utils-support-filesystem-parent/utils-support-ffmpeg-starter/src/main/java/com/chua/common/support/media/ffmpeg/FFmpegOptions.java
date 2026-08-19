package com.chua.common.support.media.ffmpeg;

/**
 * FFmpeg 音视频处理选项。
 *
 * <p>控制编解码器、码率、分辨率、帧率、质量等参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FFmpegOptions {

    /** 视频编码器（libx264, libx265, h264_nvenc） */
    private String videoCodec;

    /** 音频编码器（aac, mp3, libopus） */
    private String audioCodec;

    /** 视频码率（"2M", "5000k"） */
    private String videoBitrate;

    /** 音频码率（"128k", "320k"） */
    private String audioBitrate;

    /** 帧率 */
    private Integer fps;

    /** 视频宽度 */
    private Integer width;

    /** 视频高度 */
    private Integer height;

    /** 音频采样率（44100, 48000） */
    private Integer audioSampleRate;

    /** 音频声道数 */
    private Integer audioChannels;

    /** 质量 CRF 0-51，越小质量越高 */
    private Integer quality;

    /** 编码预设（ultrafast, fast, medium, slow） */
    private String preset;

    /** 像素格式（yuv420p, yuv444p） */
    private String pixelFormat;

    /** 硬件加速（cuda, vaapi, videotoolbox） */
    private String hwaccel;

    /** 是否静音 */
    private boolean mute;

    /** 是否移除视频流 */
    private boolean removeVideo;

    /** 是否移除音频流 */
    private boolean removeAudio;

    /** 是否覆盖输出文件 */
    private boolean overwrite = true;

    /** 超时时间（毫秒） */
    /**
     * 超时时间（毫秒）
     */
    private long timeout;

    /** DefaultOptions */
    public static FFmpegOptions defaultOptions() { return new FFmpegOptions(); }

    /** HighQuality */
    public static FFmpegOptions highQuality() {
        FFmpegOptions o = new FFmpegOptions();
        o.videoCodec = "libx264"; o.quality = 18; o.preset = "slow"; o.audioBitrate = "320k";
        return o;
    }

    /** WebOptimized */
    public static FFmpegOptions webOptimized() {
        FFmpegOptions o = new FFmpegOptions();
        o.videoCodec = "libx264"; o.audioCodec = "aac"; o.quality = 23; o.preset = "fast"; o.pixelFormat = "yuv420p";
        return o;
    }

    /** 获取VideoCodec */
    public String getVideoCodec() { return videoCodec; }
    /** 设置VideoCodec */
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    /** 获取AudioCodec */
    public String getAudioCodec() { return audioCodec; }
    /** 设置AudioCodec */
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    /** 获取VideoBitrate */
    public String getVideoBitrate() { return videoBitrate; }
    /** 设置VideoBitrate */
    public void setVideoBitrate(String videoBitrate) { this.videoBitrate = videoBitrate; }
    /** 获取AudioBitrate */
    public String getAudioBitrate() { return audioBitrate; }
    /** 设置AudioBitrate */
    public void setAudioBitrate(String audioBitrate) { this.audioBitrate = audioBitrate; }
    /** 获取Fps */
    public Integer getFps() { return fps; }
    /** 设置Fps */
    public void setFps(Integer fps) { this.fps = fps; }
    /** 获取Width */
    public Integer getWidth() { return width; }
    /** 设置Width */
    public void setWidth(Integer width) { this.width = width; }
    /** 获取Height */
    public Integer getHeight() { return height; }
    /** 设置Height */
    public void setHeight(Integer height) { this.height = height; }
    /** 获取AudioSampleRate */
    public Integer getAudioSampleRate() { return audioSampleRate; }
    /** 设置AudioSampleRate */
    public void setAudioSampleRate(Integer audioSampleRate) { this.audioSampleRate = audioSampleRate; }
    /** 获取AudioChannels */
    public Integer getAudioChannels() { return audioChannels; }
    /** 设置AudioChannels */
    public void setAudioChannels(Integer audioChannels) { this.audioChannels = audioChannels; }
    /** 获取Quality */
    public Integer getQuality() { return quality; }
    /** 设置Quality */
    public void setQuality(Integer quality) { this.quality = quality; }
    /** 获取Preset */
    public String getPreset() { return preset; }
    /** 设置Preset */
    public void setPreset(String preset) { this.preset = preset; }
    /** 获取Pixel格式化 */
    public String getPixelFormat() { return pixelFormat; }
    /** 是否Mute */
    public boolean isMute() { return mute; }
    /** 设置Mute */
    public void setMute(boolean mute) { this.mute = mute; }
    /** 是否移除Video */
    public boolean isRemoveVideo() { return removeVideo; }
    /** 设置移除Video */
    public void setRemoveVideo(boolean removeVideo) { this.removeVideo = removeVideo; }
    /** 是否移除Audio */
    public boolean isRemoveAudio() { return removeAudio; }
    /** 设置移除Audio */
    public void setRemoveAudio(boolean removeAudio) { this.removeAudio = removeAudio; }
    /** 是否Overwrite */
    public boolean isOverwrite() { return overwrite; }
    /** 设置Overwrite */
    public void setOverwrite(boolean overwrite) { this.overwrite = overwrite; }
    /** 获取Timeout */
    public long getTimeout() { return timeout; }
    /** 设置Timeout */
    public void setTimeout(long timeout) { this.timeout = timeout; }
}
