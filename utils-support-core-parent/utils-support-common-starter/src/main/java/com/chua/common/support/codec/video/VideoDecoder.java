package com.chua.common.support.codec.video;

import com.chua.common.support.codec.Decoder;

import java.util.List;

/**
 * 视频解码器 SPI —— 逐个送入 {@link VideoPacket}，增量产出 {@link VideoFrame}。
 *
 * <p>会话型契约与线程约束同 {@link VideoEncoder}：
 * {@code configure} → 反复 {@code decode} → {@code drain} → {@code close}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VideoDecoder extends Decoder<VideoPacket, List<VideoFrame>>, AutoCloseable {

    /**
     * 当前环境是否具备该解码能力。
     *
     * @return 可用返回 {@code true}
     */
    boolean isAvailable();

    /**
     * 打开解码会话。
     *
     * @param options 解码参数，{@code null} 表示全部使用实现默认值
     */
    void configure(VideoCodecOptions options);

    /**
     * 解码一个编码包。
     *
     * <p>解码器存在重排序延迟时，本方法允许返回空集合，帧在后续调用或 {@link #drain()} 时产出。</p>
     *
     * @param input 待解码包
     * @return 该包产出的视频帧，可能为空
     */
    @Override
    List<VideoFrame> decode(VideoPacket input);

    /**
     * 送入结束信号并排空解码器缓冲。
     *
     * @return 残余视频帧，可能为空
     */
    List<VideoFrame> drain();

    /**
     * 释放原生解码上下文。
     */
    @Override
    default void close() {
    }
}
