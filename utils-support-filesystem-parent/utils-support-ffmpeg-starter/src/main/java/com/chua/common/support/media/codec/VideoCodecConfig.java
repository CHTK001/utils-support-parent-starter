package com.chua.common.support.media.codec;

/**
* 视频编码器配置参数。
*
* @author CH
* @since 4.0.0.42
 */
public class VideoCodecConfig {

    /**
    * 默认编码器名称
     */
    private static final String DEFAULT_CODEC_NAME = "h264";

    /**
    * 默认帧率
     */
    private static final int DEFAULT_FPS = 60;

    /**
    * 默认画质
     */
    private static final int DEFAULT_QUALITY = 80;

    /**
    * 编码器名称
     */
    private String codecName = DEFAULT_CODEC_NAME;

    /**
    * 视频宽度
     */
    private int width;

    /**
    * 视频高度
     */
    private int height;

    /**
    * 帧率
     */
    private int fps = DEFAULT_FPS;

    /**
    * 画质
     */
    private int quality = DEFAULT_QUALITY;

    /**
    * 是否启用硬件加速
     */
    private boolean hardwareAccelerated;

    /**
    * 空构造。
     */
    public VideoCodecConfig() {
    }

    /**
    * 使用宽高和帧率构造。
    *
    * @param width 视频宽度
    * @param height 视频高度
    * @param fps 帧率
     */
    public VideoCodecConfig(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    /**
    * 获取编码器名称。
    *
    * @return 编码器名称
     */
    public String getCodecName() {
        return codecName;
    }

    /**
    * 设置编码器名称。
    *
    * @param codecName 编码器名称
     */
    public void setCodecName(String codecName) {
        this.codecName = codecName;
    }

    /**
    * 获取视频宽度。
    *
    * @return 视频宽度
     */
    public int getWidth() {
        return width;
    }

    /**
    * 设置视频宽度。
    *
    * @param width 视频宽度
     */
    public void setWidth(int width) {
        this.width = width;
    }

    /**
    * 获取视频高度。
    *
    * @return 视频高度
     */
    public int getHeight() {
        return height;
    }

    /**
    * 设置视频高度。
    *
    * @param height 视频高度
     */
    public void setHeight(int height) {
        this.height = height;
    }

    /**
    * 获取帧率。
    *
    * @return 帧率
     */
    public int getFps() {
        return fps;
    }

    /**
    * 设置帧率。
    *
    * @param fps 帧率
     */
    public void setFps(int fps) {
        this.fps = fps;
    }

    /**
    * 获取画质。
    *
    * @return 画质
     */
    public int getQuality() {
        return quality;
    }

    /**
    * 设置画质。
    *
    * @param quality 画质
     */
    public void setQuality(int quality) {
        this.quality = quality;
    }

    /**
    * 是否启用硬件加速。
    *
    * @return true 表示启用硬件加速
     */
    public boolean isHardwareAccelerated() {
        return hardwareAccelerated;
    }

    /**
    * 设置是否启用硬件加速。
    *
    * @param hardwareAccelerated 是否启用硬件加速
     */
    public void setHardwareAccelerated(boolean hardwareAccelerated) {
        this.hardwareAccelerated = hardwareAccelerated;
    }
}
