package com.chua.common.support.network.sse;

/**
 * SSE（Server-Sent Events）事件监听器
 *
 * <p>接收 {@link SseClient} 解析后的 SSE 事件回调。
 * 核心方法为 {@link #onData(String)}，接收到 {@code data:} 行时触发。
 *
 * <h3>事件顺序</h3>
 * <pre>
 *   连接成功 → [onData × N] → onComplete（正常结束）
 *                                       → onError（异常结束）
 * </pre>
 *
 * @author CH
 * @since 2026/07/21
 */
@FunctionalInterface
public interface SseListener {

    /**
     * 收到 {@code data:} 行的内容
     *
     * <p>每行 {@code data: xxx} 触发一次。空行（仅 {@code data:} 无内容）也会触发。
     *
     * @param data data 内容，不含 "data: " 前缀
     */
    void onData(String data);

    /**
     * 流式连接正常结束
     *
     * <p>服务端关闭连接或流读取完毕时调用。
     */
    default void onComplete() {
    }

    /**
     * 流式连接异常结束
     *
     * <p>发生网络异常、超时或 HTTP 错误状态码时调用。
     * 默认实现不会执行任何操作。
     *
     * @param error 异常信息
     */
    default void onError(Throwable error) {
    }
}
