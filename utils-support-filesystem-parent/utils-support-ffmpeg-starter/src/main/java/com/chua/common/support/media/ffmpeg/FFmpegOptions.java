package com.chua.common.support.media.ffmpeg;

/**
 * ffmpeg 音视频处理选项。
 *
 * <p>控制编解码器、码率、分辨率、帧率、质量等参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FFmpegOptions {

    /**
     * 视频编码器（libx264, libx265, h264_nvenc）
    */
    private String videoCodec;

    /**
     * 音频编码器（aac, mp3, libopus）
    */
    private String audioCodec;

    /**
     * 视频码率（"2M", "5000k"）
    */
    private String videoBitrate;

    /**
     * 音频码率（"128k", "320k"）
    */
    private String audioBitrate;

    /**
     * 帧率
    */
    private Integer fps;

    /**
     * 视频宽度
    */
    private Integer width;

    /**
     * 视频高度
    */
    private Integer height;

    /**
     * 音频采样率（44100, 48000）
    */
    private Integer audioSampleRate;

    /**
     * 音频声道数
    */
    private Integer audioChannels;

    /**
     * 质量 CRF 0-51，越小质量越高
    */
    private Integer quality;

    /**
     * 编码预设（ultrafast, fast, medium, slow）
    */
    private String preset;

    /**
     * 像素格式（yuv420p, yuv444p）
    */
    private String pixelFormat;

    /**
     * 硬件加速（cuda, vaapi, videotoolbox）
    */
    private String hwaccel;

    /**
     * 是否静音
    */
    private boolean mute;

    /**
     * 是否移除视频流
    */
    private boolean removeVideo;

    /**
     * 是否移除音频流
    */
    private boolean removeAudio;

    /**
     * 是否覆盖输出文件
    */
    private boolean overwrite = true;

    /**
     * 超时时间（毫秒）
    */
    private long timeout;

    /**
     * 默认期权
     *
     * @return 默认期权的结果
     */
    public static FFmpegOptions defaultOptions() { return new FFmpegOptions(); }

    /**
     * highquality
     *
     * @return highQuality的结果
     */
    public static FFmpegOptions highQuality() {
        FFmpegOptions o = new FFmpegOptions();
        o.videoCodec = "libx264";
        o.quality = 18;
        o.preset = "slow";
        o.audioBitrate = "320k";
        return o;
    }

    /**
     * web优化
     *
     * @return web优化的结果
     */
    public static FFmpegOptions webOptimized() {
        FFmpegOptions o = new FFmpegOptions();
        o.videoCodec = "libx264";
        o.audioCodec = "aac";
        o.quality = 23;
        o.preset = "fast";
        o.pixelFormat = "yuv420p";
        return o;
    }

    /**
     * 获取视频codec
     *
     * @return 获取视频codec的结果
     */
    public String getVideoCodec() { return videoCodec; }
    /**
     * 设置视频codec
     *
     * @param videoCodec 视频codec
     */
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    /**
     * 获取音频codec
     *
     * @return 获取音频codec的结果
     */
    public String getAudioCodec() { return audioCodec; }
    /**
     * 设置音频codec
     *
     * @param audioCodec 音频codec
     */
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    /**
     * 获取视频bitrate
     *
     * @return 获取视频bitrate的结果
     */
    public String getVideoBitrate() { return videoBitrate; }
    /**
     * 设置视频bitrate
     *
     * @param videoBitrate 视频bitrate
     */
    public void setVideoBitrate(String videoBitrate) { this.videoBitrate = videoBitrate; }
    /**
     * 获取音频bitrate
     *
     * @return 获取音频bitrate的结果
     */
    public String getAudioBitrate() { return audioBitrate; }
    /**
     * 设置音频bitrate
     *
     * @param audioBitrate 音频bitrate
     */
    public void setAudioBitrate(String audioBitrate) { this.audioBitrate = audioBitrate; }
    /**
     * 获取Fps
     *
     * @return 获取fps的结果
     */
    public Integer getFps() { return fps; }
    /**
     * 设置Fps
     *
     * @param fps fps
     */
    public void setFps(Integer fps) { this.fps = fps; }
    /**
     * 获取Width
     *
     * @return 获取width的结果
     */
    public Integer getWidth() { return width; }
    /**
     * 设置Width
     *
     * @param width width
     */
    public void setWidth(Integer width) { this.width = width; }
    /**
     * 获取Height
     *
     * @return 获取height的结果
     */
    public Integer getHeight() { return height; }
    /**
     * 设置Height
     *
     * @param height height
     */
    public void setHeight(Integer height) { this.height = height; }
    /**
     * 获取音频样本rate
     *
     * @return 获取音频样本rate的结果
     */
    public Integer getAudioSampleRate() { return audioSampleRate; }
    /**
     * 设置音频样本rate
     *
     * @param audioSampleRate 音频样本rate
     */
    public void setAudioSampleRate(Integer audioSampleRate) { this.audioSampleRate = audioSampleRate; }
    /**
     * 获取音频通道
     *
     * @return 获取音频通道的结果
     */
    public Integer getAudioChannels() { return audioChannels; }
    /**
     * 设置音频通道
     *
     * @param audioChannels 音频通道
     */
    public void setAudioChannels(Integer audioChannels) { this.audioChannels = audioChannels; }
    /**
     * 获取Quality
     *
     * @return 获取quality的结果
     */
    public Integer getQuality() { return quality; }
    /**
     * 设置Quality
     *
     * @param quality quality
     */
    public void setQuality(Integer quality) { this.quality = quality; }
    /**
     * 获取Preset
     *
     * @return 获取preset的结果
     */
    public String getPreset() { return preset; }
    /**
     * 设置Preset
     *
     * @param preset preset
     */
    public void setPreset(String preset) { this.preset = preset; }
    /**
     * 获取Pixel格式化
     *
     * @return 获取pixel格式化的结果
     */
    public String getPixelFormat() { return pixelFormat; }
    /**
     * 是否Mute
     *
     * @return 是否mute的结果
     */
    public boolean isMute() { return mute; }
    /**
     * 设置Mute
     *
     * @param mute mute
     */
    public void setMute(boolean mute) { this.mute = mute; }
    /**
     * 是否移除视频
     *
     * @return 是否移除视频的结果
     */
    public boolean isRemoveVideo() { return removeVideo; }
    /**
     * 设置移除视频
     *
     * @param removeVideo 移除视频
     */
    public void setRemoveVideo(boolean removeVideo) { this.removeVideo = removeVideo; }
    /**
     * 是否移除音频
     *
     * @return 是否移除音频的结果
     */
    public boolean isRemoveAudio() { return removeAudio; }
    /**
     * 设置移除音频
     *
     * @param removeAudio 移除音频
     */
    public void setRemoveAudio(boolean removeAudio) { this.removeAudio = removeAudio; }
    /**
     * 是否Overwrite
     *
     * @return 是否overwrite的结果
     */
    public boolean isOverwrite() { return overwrite; }
    /**
     * 设置Overwrite
     *
     * @param overwrite overwrite
     */
    public void setOverwrite(boolean overwrite) { this.overwrite = overwrite; }
    /**
     * 获取超时
     *
     * @return 获取超时的结果
     */
    public long getTimeout() { return timeout; }
    /**
     * 设置超时
     *
     * @param timeout 超时
     */
    public void setTimeout(long timeout) { this.timeout = timeout; }
}
