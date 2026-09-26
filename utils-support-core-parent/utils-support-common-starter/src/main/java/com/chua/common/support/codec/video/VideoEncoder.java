package com.chua.common.support.codec.video;

import com.chua.common.support.codec.Encoder;

import java.util.List;

/**
 * 视频编码器 SPI —— 逐帧送入 {@link VideoFrame}，增量产出 {@link VideoPacket}。
 *
 * <p>会话型契约：先 {@link #configure(VideoCodecOptions)} 打开编码器，
 * 反复 {@code encode(frame)}，最后 {@link #flush()} 排空流水线并 {@link #close()} 释放。
 * 因此实例持有原生上下文，<b>非线程安全</b>，一个实例只服务一路编码；
 * 应通过 {@code ServiceProvider.of(VideoEncoder.class).getNewExtension(name)} 取独立实例，
 * 不要复用 {@code list()} 返回的共享实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VideoEncoder extends Encoder<VideoFrame, List<VideoPacket>>, AutoCloseable {

    /**
     * 当前环境是否具备该编码能力（硬件编码器会探测设备与驱动）。
     *
     * @return 可用返回 {@code true}
     */
    boolean isAvailable();

    /**
     * 打开编码会话。
     *
     * @param options 编码参数，{@code null} 表示全部使用实现默认值
     */
    void configure(VideoCodecOptions options);

    /**
     * 编码一帧。
     *
     * <p>编码器存在流水线延迟时，本方法允许返回空集合，数据在后续调用或
     * {@link #flush()} 时产出。</p>
     *
     * @param input 待编码帧
     * @return 该帧产出的编码包，可能为空
     */
    @Override
    List<VideoPacket> encode(VideoFrame input);

    /**
     * 送入结束信号并排空编码器流水线。
     *
     * @return 残余编码包，可能为空
     */
    List<VideoPacket> flush();

    /**
     * 释放原生编码上下文。
     */
    @Override
    default void close() {
    }
}
