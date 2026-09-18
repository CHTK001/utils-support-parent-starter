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
    * @author CH
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

        /**
        * 获取索引
        *
        * @return 获取索引的结果
        */
        public int getIndex() { return index; }
        /**
        * 设置索引
        *
        * @param v v
        */
        public void setIndex(int v) { index = v; }
        /**
        * 获取Codec
        *
        * @return 获取codec的结果
        */
        public String getCodec() { return codec; }
        /**
        * 设置Codec
        *
        * @param v v
        */
        public void setCodec(String v) { codec = v; }
        /**
        * 获取codeclong名称
        *
        * @return 获取codeclong名称的结果
        */
        public String getCodecLongName() { return codecLongName; }
        /**
        * 设置codeclong名称
        *
        * @param v v
        */
        public void setCodecLongName(String v) { codecLongName = v; }
        /**
        * 获取Width
        *
        * @return 获取width的结果
        */
        public int getWidth() { return width; }
        /**
        * 设置Width
        *
        * @param v v
        */
        public void setWidth(int v) { width = v; }
        /**
        * 获取Height
        *
        * @return 获取height的结果
        */
        public int getHeight() { return height; }
        /**
        * 设置Height
        *
        * @param v v
        */
        public void setHeight(int v) { height = v; }
        /**
        * 获取Fps
        *
        * @return 获取fps的结果
        */
        public double getFps() { return fps; }
        /**
        * 设置Fps
        *
        * @param v v
        */
        public void setFps(double v) { fps = v; }
        /**
        * 获取Bitrate
        *
        * @return 获取bitrate的结果
        */
        public long getBitrate() { return bitrate; }
        /**
        * 设置Bitrate
        *
        * @param v v
        */
        public void setBitrate(long v) { bitrate = v; }
        /**
        * 获取持续时间
        *
        * @return 获取持续时间的结果
        */
        public double getDuration() { return duration; }
        /**
        * 设置持续时间
        *
        * @param v v
        */
        public void setDuration(double v) { duration = v; }
    }

    /**
    * 音频流信息。
    *
    * @since 4.0.0.42
    * @author CH
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
        /** 声道布局（如 立体, 5.1） */
        private String channelLayout;
        /** 音频码率（bps） */
        private long bitrate;
        /** 音频时长（秒） */
        /**
        * 持续时间（毫秒）
        */
        private double duration;

        /**
        * 获取索引
        *
        * @return 获取索引的结果
        */
        public int getIndex() { return index; }
        /**
        * 设置索引
        *
        * @param v v
        */
        public void setIndex(int v) { index = v; }
        /**
        * 获取Codec
        *
        * @return 获取codec的结果
        */
        public String getCodec() { return codec; }
        /**
        * 设置Codec
        *
        * @param v v
        */
        public void setCodec(String v) { codec = v; }
        /**
        * 获取codeclong名称
        *
        * @return 获取codeclong名称的结果
        */
        public String getCodecLongName() { return codecLongName; }
        /**
        * 设置codeclong名称
        *
        * @param v v
        */
        public void setCodecLongName(String v) { codecLongName = v; }
        /**
        * 获取样本rate
        *
        * @return 获取样本rate的结果
        */
        public int getSampleRate() { return sampleRate; }
        /**
        * 设置样本rate
        *
        * @param v v
        */
        public void setSampleRate(int v) { sampleRate = v; }
        /**
        * 获取通道
        *
        * @return 获取通道的结果
        */
        public int getChannels() { return channels; }
        /**
        * 设置通道
        *
        * @param v v
        */
        public void setChannels(int v) { channels = v; }
        /**
        * 获取通道layout
        *
        * @return 获取通道layout的结果
        */
        public String getChannelLayout() { return channelLayout; }
        /**
        * 设置通道layout
        *
        * @param v v
        */
        public void setChannelLayout(String v) { channelLayout = v; }
        /**
        * 获取Bitrate
        *
        * @return 获取bitrate的结果
        */
        public long getBitrate() { return bitrate; }
        /**
        * 设置Bitrate
        *
        * @param v v
        */
        public void setBitrate(long v) { bitrate = v; }
        /**
        * 获取持续时间
        *
        * @return 获取持续时间的结果
        */
        public double getDuration() { return duration; }
        /**
        * 设置持续时间
        *
        * @param v v
        */
        public void setDuration(double v) { duration = v; }
    }

    /**
    * 获取文件名
    *
    * @return 获取文件名的结果
    */
    public String getFilename() { return filename; }
    /**
    * 设置文件名
    *
    * @param v v
    */
    public void setFilename(String v) { filename = v; }
    /**
    * 获取格式化名称
    *
    * @return 获取格式化名称的结果
    */
    public String getFormatName() { return formatName; }
    /**
    * 设置格式化名称
    *
    * @param v v
    */
    public void setFormatName(String v) { formatName = v; }
    /**
    * 获取格式化long名称
    *
    * @return 获取格式化long名称的结果
    */
    public String getFormatLongName() { return formatLongName; }
    /**
    * 设置格式化long名称
    *
    * @param v v
    */
    public void setFormatLongName(String v) { formatLongName = v; }
    /**
    * 获取持续时间
    *
    * @return 获取持续时间的结果
    */
    public double getDuration() { return duration; }
    /**
    * 设置持续时间
    *
    * @param v v
    */
    public void setDuration(double v) { duration = v; }
    /**
    * 获取获取大小
    *
    * @return 获取大小的结果
    */
    public long getSize() { return size; }
    /**
    * 设置获取大小
    *
    * @param v v
    */
    public void setSize(long v) { size = v; }
    /**
    * 获取Bitrate
    *
    * @return 获取bitrate的结果
    */
    public long getBitrate() { return bitrate; }
    /**
    * 设置Bitrate
    *
    * @param v v
    */
    public void setBitrate(long v) { bitrate = v; }
    /**
    * 获取视频流
    *
    * @return 获取视频流的结果
    */
    public VideoStream getVideoStream() { return videoStream; }
    /**
    * 设置视频流
    *
    * @param v v
    */
    public void setVideoStream(VideoStream v) { videoStream = v; }
    /**
    * 获取音频流
    *
    * @return 获取音频流的结果
    */
    public AudioStream getAudioStream() { return audioStream; }
    /**
    * 设置音频流
    *
    * @param v v
    */
    public void setAudioStream(AudioStream v) { audioStream = v; }
}
