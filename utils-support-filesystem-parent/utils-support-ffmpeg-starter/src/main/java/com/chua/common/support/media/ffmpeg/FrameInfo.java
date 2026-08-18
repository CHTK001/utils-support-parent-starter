package com.chua.common.support.media.ffmpeg;

/**
 * FFmpeg 帧信息，用于推流/拉流回调。
 *
 * <p>在回调模式下，每处理一帧都会回调此对象，包含帧序号、时间戳、分辨率等信息。
 * 当使用 {@code WithFrames} 变体方法时，{@link #imageData} 会填充当前帧的 JPEG 图像数据，
 * 可用于实时渲染预览。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FrameInfo {

    /** 帧序号（从 0 开始） */
    /** Frame数字 */
    private long frameNumber;

    /** 帧时间戳（毫秒） */
    /** 时间戳MS */
    private long timestampMs;

    /** 视频宽度 */
    /** 宽度 */
    private int width;

    /** 视频高度 */
    /** 高度 */
    private int height;

    /** 视频编码器名称 */
    /** Codec */
    private String codec;

    /** 帧率 */
    /** FPS */
    private double fps;

    /** 是否为关键帧 */
    /** 密钥frame */
    private boolean keyFrame;

    /**
     * 帧图像数据（JPEG 格式字节）。
     *
     * <p>仅在 {@code pushStreamWithFrames} / {@code pullStreamWithFrames} 方法中填充，
     * 普通回调方法中此字段为 {@code null}。</p>
     */
    private byte[] imageData;

    public FrameInfo() {
    }

    public long getFrameNumber() { return frameNumber; }
    public void setFrameNumber(long frameNumber) { this.frameNumber = frameNumber; }

    public long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public String getCodec() { return codec; }
    public void setCodec(String codec) { this.codec = codec; }

    public double getFps() { return fps; }
    public void setFps(double fps) { this.fps = fps; }

    public boolean isKeyFrame() { return keyFrame; }
    public void setKeyFrame(boolean keyFrame) { this.keyFrame = keyFrame; }

    public byte[] getImageData() { return imageData; }
    public void setImageData(byte[] imageData) { this.imageData = imageData; }

    @Override
    public String toString() {
        return "FrameInfo{frame=" + frameNumber + ", ts=" + timestampMs + "ms, " +
                width + "x" + height + ", key=" + keyFrame +
                (imageData != null ? ", image=" + imageData.length + "B" : "") + "}";
    }
}