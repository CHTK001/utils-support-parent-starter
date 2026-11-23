package com.chua.common.support.network.frp;

/**
 * FRP 零拷贝字节消费者：直接在数据面读缓冲切片上回调，避免逐次分配中间数组。
 *
 * <p>与 {@code Consumer<byte[]>} 的区别：回调收到的是底层缓冲的 {@code (buf, off, len)}
 * 切片视图，仅在本次同步回调内有效——回调返回后底层缓冲会被后续读取复用。
 * 需要在回调外保留数据时，必须在回调内自行拷贝；只做转发（如写往另一个流）时可零拷贝直写。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@FunctionalInterface
public interface FrpByteConsumer {

    /**
     * 接收一段数据切片。
     *
     * @param buf 底层读缓冲
     * @param off 数据起始偏移
     * @param len 数据长度
     * @throws Exception 回调内部异常（由分发方捕获并忽略，不中断数据面）
     */
    void accept(byte[] buf, int off, int len) throws Exception;
}
