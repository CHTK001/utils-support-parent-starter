package com.chua.runtime.protocol;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import java.util.HashMap;
import java.util.Map;

/**
 * 跨进程追踪上下文传播器 — 简化版 W3C Trace Context Inject/Extract 工具。
 *
 * <p>使用方式：</p>
 * <pre>
 * Map&lt;String, String&gt; headers = new HashMap&lt;&gt;();
 * TraceContextPropagator.inject(headers);
 * // 把 headers 注入 HTTP / RPC 客户端
 * </pre>
 *
 * <p>服务端入口：</p>
 * <pre>
 * Map&lt;String, String&gt; incomingHeaders = ...;
 * TraceContextPropagator.extract(incomingHeaders);
 * // 后续 RuntimeSpy 拦截的事件都作为该 trace 的子 Span
 * </pre>
 *
 * @since 4.0.0.42
 */
public final class TraceContextPropagator {

    private TraceContextPropagator() {
    }

    /**
     * 把当前线程追踪上下文以 W3C traceparent 注入到 headers。
     *
     * @param headers header 集合（修改入参）
     */
    public static void inject(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        String traceparent = W3CTraceContext.inject();
        if (traceparent != null) {
            headers.put(W3CTraceContext.HEADER_TRACEPARENT, traceparent);
        }
    }

    /**
     * 把当前线程追踪上下文以 W3C traceparent 注入到 headers（case-insensitive key）。
     *
     * @param headers header 集合
     */
    public static void injectIgnoreCase(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        String traceparent = W3CTraceContext.inject();
        if (traceparent == null) {
            return;
        }
        // 找到现有 traceparent key（任意大小写），覆盖
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(W3CTraceContext.HEADER_TRACEPARENT)) {
                headers.put(key, traceparent);
                return;
            }
        }
        headers.put(W3CTraceContext.HEADER_TRACEPARENT, traceparent);
    }

    /**
     * 从 headers 中提取 traceparent 并应用到当前线程追踪栈。
     *
     * @param headers header 集合
     * @return 是否成功提取（true 表示恢复成功）
     */
    public static boolean extract(Map<String, String> headers) {
        if (CollectionUtils.isEmpty(headers)) {
            return false;
        }
        String traceparent = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null
                    && e.getKey().equalsIgnoreCase(W3CTraceContext.HEADER_TRACEPARENT)
                    && StringUtils.isNotEmpty(e.getValue())) {
                traceparent = e.getValue();
                break;
            }
        }
        if (traceparent == null) {
            return false;
        }
        return W3CTraceContext.extractAndRestore(traceparent);
    }

    /**
     * 仅提取，不修改当前线程上下文 — 用于显式拿到 traceId/spanId。
     *
     * @param headers header 集合
     * @return 解析后的 W3CTraceContext，无有效 header 时返回 null
     */
    public static W3CTraceContext peek(Map<String, String> headers) {
        if (CollectionUtils.isEmpty(headers)) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null
                    && e.getKey().equalsIgnoreCase(W3CTraceContext.HEADER_TRACEPARENT)
                    && StringUtils.isNotEmpty(e.getValue())) {
                return W3CTraceContext.extract(e.getValue());
            }
        }
        return null;
    }

    /**
     * 创建空的 header 集合并注入当前上下文（便利方法）。
     *
     * @return 新建的 header Map
     */
    public static Map<String, String> newOutgoingHeaders() {
        Map<String, String> headers = new HashMap<>();
        inject(headers);
        return headers;
    }
}