package com.chua.common.support.network.sse;

/**
 * SSE 连接句柄
 *
 * <p>用于主动关闭 SSE 连接。由 {@link SseClient#connect(SseRequest, SseListener)} 返回。
 *
 * @author CH
 * @since 2026/07/21
 */
public interface SseConnection extends AutoCloseable {

    /**
     * 主动关闭 SSE 连接
     *
     * <p>调用后底层 HTTP 连接将被释放，不再接收事件。
     * 多次调用是安全的，只有第一次调用会执行关闭操作。
     */
    @Override
    void close();

    /**
     * 判断连接是否仍处于活跃状态
     *
     * @return true 表示连接尚未关闭
     */
    default boolean isConnected() {
        return true;
    }
}
