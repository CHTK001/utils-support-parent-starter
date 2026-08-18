package com.chua.common.support.media.ffmpeg;

/**
 * 媒体信息，封装音视频文件的元数据。
 *
 * <p>包含文件格式、时长、大小、码率等基本信息，
 * 以及视频流和音频流的详细编码参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FFmpegMediaInfo {

    /** 文件名 */
    /** Filename */
    private String filename;

    /** 封装格式名称（如 mp4, avi, mkv） */
    /** 格式名称 */
    private String formatName;

    /** 封装格式详细描述 */
    /** 格式long名称 */
    private String formatLongName;

    /** 视频总时长（秒） */
    /**
     * 持续时间（毫秒）
     */
    private double duration;

    /** 文件大小（字节） */
    /**
     * 大小
     */
    private long size;

    /** 总码率（bps） */
    /** Bitrate */
    private long bitrate;

    /** 视频流信息 */
    /** 视频流 */
    private VideoStream videoStream;

    /** 音频流信息 */
    /** 音频流 */
    private AudioStream audioStream;

    /**
     * 视频流信息。
     *
 * @author CH
     * @since 4.0.0.42
     */
    public static class VideoStream {
        /** 流索引 */
        /**
         * 索引名
         */
        private int index;
        /** 编码器名称（如 h264, hevc） */
        /** Codec */
        private String codec;
        /** 编码器详细名称 */
        /** Codeclong名称 */
        private String codecLongName;
        /** 视频宽度（像素） */
        /** 宽度 */
        private int width;
        /** 视频高度（像素） */
        /** 高度 */
        private int height;
        /** 帧率 */
        /** FPS */
        private double fps;
        /** 视频码率（bps） */
        /** Bitrate */
        private long bitrate;
        /** 视频时长（秒） */
        /**
         * 持续时间（毫秒）
         */
        private double duration;

        public int getIndex() { return index; }
        public void setIndex(int v) { index = v; }
        public String getCodec() { return codec; }
        public void setCodec(String v) { codec = v; }
        public String getCodecLongName() { return codecLongName; }
        public void setCodecLongName(String v) { codecLongName = v; }
        public int getWidth() { return width; }
        public void setWidth(int v) { width = v; }
        public int getHeight() { return height; }
        public void setHeight(int v) { height = v; }
        public double getFps() { return fps; }
        public void setFps(double v) { fps = v; }
        public long getBitrate() { return bitrate; }
        public void setBitrate(long v) { bitrate = v; }
        public double getDuration() { return duration; }
        public void setDuration(double v) { duration = v; }
    }

    /**
     * 音频流信息。
     *
 * @author CH
     * @since 4.0.0.42
     */
    public static class AudioStream {
        /** 流索引 */
        /**
         * 索引名
         */
        private int index;
        /** 编码器名称（如 aac, mp3） */
        /** Codec */
        private String codec;
        /** 编码器详细名称 */
        /** Codeclong名称 */
        private String codecLongName;
        /** 采样率（Hz） */
        /** 示例比率 */
        private int sampleRate;
        /** 声道数 */
        /** Channels */
        private int channels;
        /** 声道布局（如 stereo, 5.1） */
        /** 通道layout */
        private String channelLayout;
        /** 音频码率（bps） */
        /** Bitrate */
        private long bitrate;
        /** 音频时长（秒） */
        /**
         * 持续时间（毫秒）
         */
        private double duration;

        public int getIndex() { return index; }
        public void setIndex(int v) { index = v; }
        public String getCodec() { return codec; }
        public void setCodec(String v) { codec = v; }
        public String getCodecLongName() { return codecLongName; }
        public void setCodecLongName(String v) { codecLongName = v; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int v) { sampleRate = v; }
        public int getChannels() { return channels; }
        public void setChannels(int v) { channels = v; }
        public String getChannelLayout() { return channelLayout; }
        public void setChannelLayout(String v) { channelLayout = v; }
        public long getBitrate() { return bitrate; }
        public void setBitrate(long v) { bitrate = v; }
        public double getDuration() { return duration; }
        public void setDuration(double v) { duration = v; }
    }

    public String getFilename() { return filename; }
    public void setFilename(String v) { filename = v; }
    public String getFormatName() { return formatName; }
    public void setFormatName(String v) { formatName = v; }
    public String getFormatLongName() { return formatLongName; }
    public void setFormatLongName(String v) { formatLongName = v; }
    public double getDuration() { return duration; }
    public void setDuration(double v) { duration = v; }
    public long getSize() { return size; }
    public void setSize(long v) { size = v; }
    public long getBitrate() { return bitrate; }
    public void setBitrate(long v) { bitrate = v; }
    public VideoStream getVideoStream() { return videoStream; }
    public void setVideoStream(VideoStream v) { videoStream = v; }
    public AudioStream getAudioStream() { return audioStream; }
    public void setAudioStream(AudioStream v) { audioStream = v; }
}
