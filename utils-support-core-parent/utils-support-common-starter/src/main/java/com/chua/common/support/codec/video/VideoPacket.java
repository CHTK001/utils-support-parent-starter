package com.chua.common.support.codec.video;

import java.util.Objects;

/**
 * 视频编码包 —— 一个访问单元（Access Unit）的压缩数据。
 *
 * <p>{@code data} 为带起始码的 Annex-B 字节流（H.264/H.265 场景），
 * 可直接拼接后交给裸流解析器或送入解码器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VideoPacket {

    /**
     * 压缩数据（Annex-B）。
     */
    private final byte[] data;

    /**
     * 展示时间戳，单位微秒。
     */
    private final long pts;

    /**
     * 解码时间戳，单位微秒。
     */
    private final long dts;

    /**
     * 是否为关键帧（可独立解码的随机接入点）。
     */
    private final boolean keyFrame;

    /**
     * 构造编码包。
     *
     * @param data     压缩数据
     * @param pts      展示时间戳（微秒）
     * @param dts      解码时间戳（微秒）
     * @param keyFrame 是否关键帧
     */
    private VideoPacket(byte[] data, long pts, long dts, boolean keyFrame) {
        this.data = data;
        this.pts = pts;
        this.dts = dts;
        this.keyFrame = keyFrame;
    }

    /**
     * 组装编码包。
     *
     * @param data     压缩数据，不允许为 {@code null}
     * @param pts      展示时间戳（微秒）
     * @param dts      解码时间戳（微秒）
     * @param keyFrame 是否关键帧
     * @return 编码包
     */
    public static VideoPacket of(byte[] data, long pts, long dts, boolean keyFrame) {
        return new VideoPacket(Objects.requireNonNull(data, "data"), pts, dts, keyFrame);
    }

    /**
     * 获取压缩数据。
     *
     * @return Annex-B 压缩数据
     */
    public byte[] data() {
        return data;
    }

    /**
     * 获取展示时间戳。
     *
     * @return 时间戳，单位微秒
     */
    public long pts() {
        return pts;
    }

    /**
     * 获取解码时间戳。
     *
     * @return 时间戳，单位微秒
     */
    public long dts() {
        return dts;
    }

    /**
     * 是否关键帧。
     *
     * @return 关键帧返回 {@code true}
     */
    public boolean keyFrame() {
        return keyFrame;
    }

    /**
     * 数据长度。
     *
     * @return 字节数
     */
    public int size() {
        return data.length;
    }

    @Override
    public String toString() {
        return "VideoPacket{" + data.length + " bytes, pts=" + pts
                + ", dts=" + dts + ", key=" + keyFrame + "}";
    }
}
