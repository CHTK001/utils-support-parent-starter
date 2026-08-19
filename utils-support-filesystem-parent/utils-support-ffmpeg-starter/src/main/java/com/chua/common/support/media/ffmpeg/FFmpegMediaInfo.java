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
    private String filename;

    /** 封装格式名称（如 mp4, avi, mkv） */
    private String formatName;

    /** 封装格式详细描述 */
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
    private long bitrate;

    /** 视频流信息 */
    private VideoStream videoStream;

    /** 音频流信息 */
    private AudioStream audioStream;

    /**
     * 视频流信息。
     *
     * @since 4.0.0.42
     */
    public static class VideoStream {
        /** 流索引 */
        /**
         * 索引名
         */
        private int index;
        /** 编码器名称（如 h264, hevc） */
        private String codec;
        /** 编码器详细名称 */
        private String codecLongName;
        /** 视频宽度（像素） */
        private int width;
        /** 视频高度（像素） */
        private int height;
        /** 帧率 */
        private double fps;
        /** 视频码率（bps） */
        private long bitrate;
        /** 视频时长（秒） */
        /**
         * 持续时间（毫秒）
         */
        private double duration;

        /** 获取Index */
        public int getIndex() { return index; }
        /** 设置Index */
        public void setIndex(int v) { index = v; }
        /** 获取Codec */
        public String getCodec() { return codec; }
        /** 设置Codec */
        public void setCodec(String v) { codec = v; }
        /** 获取CodecLongName */
        public String getCodecLongName() { return codecLongName; }
        /** 设置CodecLongName */
        public void setCodecLongName(String v) { codecLongName = v; }
        /** 获取Width */
        public int getWidth() { return width; }
        /** 设置Width */
        public void setWidth(int v) { width = v; }
        /** 获取Height */
        public int getHeight() { return height; }
        /** 设置Height */
        public void setHeight(int v) { height = v; }
        /** 获取Fps */
        public double getFps() { return fps; }
        /** 设置Fps */
        public void setFps(double v) { fps = v; }
        /** 获取Bitrate */
        public long getBitrate() { return bitrate; }
        /** 设置Bitrate */
        public void setBitrate(long v) { bitrate = v; }
        /** 获取Duration */
        public double getDuration() { return duration; }
        /** 设置Duration */
        public void setDuration(double v) { duration = v; }
    }

    /**
     * 音频流信息。
     *
     * @since 4.0.0.42
     */
    public static class AudioStream {
        /** 流索引 */
        /**
         * 索引名
         */
        private int index;
        /** 编码器名称（如 aac, mp3） */
        private String codec;
        /** 编码器详细名称 */
        private String codecLongName;
        /** 采样率（Hz） */
        private int sampleRate;
        /** 声道数 */
        private int channels;
        /** 声道布局（如 stereo, 5.1） */
        private String channelLayout;
        /** 音频码率（bps） */
        private long bitrate;
        /** 音频时长（秒） */
        /**
         * 持续时间（毫秒）
         */
        private double duration;

        /** 获取Index */
        public int getIndex() { return index; }
        /** 设置Index */
        public void setIndex(int v) { index = v; }
        /** 获取Codec */
        public String getCodec() { return codec; }
        /** 设置Codec */
        public void setCodec(String v) { codec = v; }
        /** 获取CodecLongName */
        public String getCodecLongName() { return codecLongName; }
        /** 设置CodecLongName */
        public void setCodecLongName(String v) { codecLongName = v; }
        /** 获取SampleRate */
        public int getSampleRate() { return sampleRate; }
        /** 设置SampleRate */
        public void setSampleRate(int v) { sampleRate = v; }
        /** 获取Channels */
        public int getChannels() { return channels; }
        /** 设置Channels */
        public void setChannels(int v) { channels = v; }
        /** 获取ChannelLayout */
        public String getChannelLayout() { return channelLayout; }
        /** 设置ChannelLayout */
        public void setChannelLayout(String v) { channelLayout = v; }
        /** 获取Bitrate */
        public long getBitrate() { return bitrate; }
        /** 设置Bitrate */
        public void setBitrate(long v) { bitrate = v; }
        /** 获取Duration */
        public double getDuration() { return duration; }
        /** 设置Duration */
        public void setDuration(double v) { duration = v; }
    }

    /** 获取Filename */
    public String getFilename() { return filename; }
    /** 设置Filename */
    public void setFilename(String v) { filename = v; }
    /** 获取格式化Name */
    public String getFormatName() { return formatName; }
    /** 设置格式化Name */
    public void setFormatName(String v) { formatName = v; }
    /** 获取格式化LongName */
    public String getFormatLongName() { return formatLongName; }
    /** 设置格式化LongName */
    public void setFormatLongName(String v) { formatLongName = v; }
    /** 获取Duration */
    public double getDuration() { return duration; }
    /** 设置Duration */
    public void setDuration(double v) { duration = v; }
    /** 获取获取大小 */
    public long getSize() { return size; }
    /** 设置获取大小 */
    public void setSize(long v) { size = v; }
    /** 获取Bitrate */
    public long getBitrate() { return bitrate; }
    /** 设置Bitrate */
    public void setBitrate(long v) { bitrate = v; }
    /** 获取VideoStream */
    public VideoStream getVideoStream() { return videoStream; }
    /** 设置VideoStream */
    public void setVideoStream(VideoStream v) { videoStream = v; }
    /** 获取AudioStream */
    public AudioStream getAudioStream() { return audioStream; }
    /** 设置AudioStream */
    public void setAudioStream(AudioStream v) { audioStream = v; }
}
