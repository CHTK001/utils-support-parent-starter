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

    public static FFmpegOptions defaultOptions() { return new FFmpegOptions(); }

    public static FFmpegOptions highQuality() {
        FFmpegOptions o = new FFmpegOptions();
        o.videoCodec = "libx264"; o.quality = 18; o.preset = "slow"; o.audioBitrate = "320k";
        return o;
    }

    public static FFmpegOptions webOptimized() {
        FFmpegOptions o = new FFmpegOptions();
        o.videoCodec = "libx264"; o.audioCodec = "aac"; o.quality = 23; o.preset = "fast"; o.pixelFormat = "yuv420p";
        return o;
    }

    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public String getVideoBitrate() { return videoBitrate; }
    public void setVideoBitrate(String videoBitrate) { this.videoBitrate = videoBitrate; }
    public String getAudioBitrate() { return audioBitrate; }
    public void setAudioBitrate(String audioBitrate) { this.audioBitrate = audioBitrate; }
    public Integer getFps() { return fps; }
    public void setFps(Integer fps) { this.fps = fps; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Integer getAudioSampleRate() { return audioSampleRate; }
    public void setAudioSampleRate(Integer audioSampleRate) { this.audioSampleRate = audioSampleRate; }
    public Integer getAudioChannels() { return audioChannels; }
    public void setAudioChannels(Integer audioChannels) { this.audioChannels = audioChannels; }
    public Integer getQuality() { return quality; }
    public void setQuality(Integer quality) { this.quality = quality; }
    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }
    public String getPixelFormat() { return pixelFormat; }
    public boolean isMute() { return mute; }
    public void setMute(boolean mute) { this.mute = mute; }
    public boolean isRemoveVideo() { return removeVideo; }
    public void setRemoveVideo(boolean removeVideo) { this.removeVideo = removeVideo; }
    public boolean isRemoveAudio() { return removeAudio; }
    public void setRemoveAudio(boolean removeAudio) { this.removeAudio = removeAudio; }
    public boolean isOverwrite() { return overwrite; }
    public void setOverwrite(boolean overwrite) { this.overwrite = overwrite; }
    public long getTimeout() { return timeout; }
    public void setTimeout(long timeout) { this.timeout = timeout; }
}
