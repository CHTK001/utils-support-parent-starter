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
    private long frameNumber;

    /** 帧时间戳（毫秒） */
    private long timestampMs;

    /** 视频宽度 */
    private int width;

    /** 视频高度 */
    private int height;

    /** 视频编码器名称 */
    private String codec;

    /** 帧率 */
    private double fps;

    /** 是否为关键帧 */
    private boolean keyFrame;

    /**
     * 帧图像数据（JPEG 格式字节）。
     *
     * <p>仅在 {@code pushStreamWithFrames} / {@code pullStreamWithFrames} 方法中填充，
     * 普通回调方法中此字段为 {@code null}。</p>
     */
    private byte[] imageData;

    /** 创建 FrameInfo 实例 */
    public FrameInfo() {
    }

    /** 获取FrameNumber */
    public long getFrameNumber() { return frameNumber; }
    /** 设置FrameNumber */
    public void setFrameNumber(long frameNumber) { this.frameNumber = frameNumber; }

    /** 获取TimestampMs */
    public long getTimestampMs() { return timestampMs; }
    /** 设置TimestampMs */
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    /** 获取Width */
    public int getWidth() { return width; }
    /** 设置Width */
    public void setWidth(int width) { this.width = width; }

    /** 获取Height */
    public int getHeight() { return height; }
    /** 设置Height */
    public void setHeight(int height) { this.height = height; }

    /** 获取Codec */
    public String getCodec() { return codec; }
    /** 设置Codec */
    public void setCodec(String codec) { this.codec = codec; }

    /** 获取Fps */
    public double getFps() { return fps; }
    /** 设置Fps */
    public void setFps(double fps) { this.fps = fps; }

    /** 是否KeyFrame */
    public boolean isKeyFrame() { return keyFrame; }
    /** 设置KeyFrame */
    public void setKeyFrame(boolean keyFrame) { this.keyFrame = keyFrame; }

    /** 获取ImageData */
    public byte[] getImageData() { return imageData; }
    /** 设置ImageData */
    public void setImageData(byte[] imageData) { this.imageData = imageData; }

    @Override
    /** ToString */
    public String toString() {
        return "FrameInfo{frame=" + frameNumber + ", ts=" + timestampMs + "ms, " +
                width + "x" + height + ", key=" + keyFrame +
                (imageData != null ? ", image=" + imageData.length + "B" : "") + "}";
    }
}