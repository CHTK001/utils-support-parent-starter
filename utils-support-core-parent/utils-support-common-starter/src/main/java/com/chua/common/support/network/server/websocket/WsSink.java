package com.chua.common.support.network.server.websocket;

/**
 * 扇出器的写出一端。
 *
 * <p>独立成顶层接口而不是 {@code AsyncBroadcaster} 的内部接口：类声明的类型参数段
 * 在类体作用域之外，{@code class AsyncBroadcaster<T extends Sink>} 里的 {@code Sink}
 * 无法解析到类自身的成员（javac: 找不到符号）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface WsSink {

    /**
     * 写出一整帧。
     *
     * <p>实现方必须保证同一连接内串行（内部加锁），因为分片只保证同一片串行，
     * 不能保证调用方不会另有路径写同一连接。</p>
     *
     * @param frame 已编码的帧字节
     * @throws Exception 写失败
     */
    void writeFrame(byte[] frame) throws Exception;
}
