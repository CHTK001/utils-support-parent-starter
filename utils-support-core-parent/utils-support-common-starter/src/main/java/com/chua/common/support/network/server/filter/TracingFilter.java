package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 链路追踪过滤器。
 *
 * <p>为每个请求生成唯一 traceId，通过 {@link FilterChainListener} 记录每个 filter 的执行耗时，
 * 请求结束时输出完整的链路追踪日志（包含 filter 执行顺序和各节点耗时）。</p>
 *
 * <p>使用方式：服务器端自动注册，通过 {@code _traceId} 请求属性传递。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class TracingFilter implements ServerFilter {

    /**
     * traceId 的请求属性键名。
     */
    private static final String TRACE_ID_KEY = "_traceId";

    /**
     * 链监听器列表的请求属性键名。
     */
    private static final String LISTENERS_KEY = "_chainListeners";

    /**
     * 链路日志列表的请求属性键名。
     */
    private static final String TRACE_LOG_KEY = "_traceLog";

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 60;
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        request.setAttribute(TRACE_ID_KEY, traceId);

        List<FilterChainListener> listeners = new ArrayList<>();
        List<String> traceLog = new ArrayList<>();
        request.setAttribute(LISTENERS_KEY, listeners);
        request.setAttribute(TRACE_LOG_KEY, traceLog);

        listeners.add(new FilterChainListener() {
            @Override
            public void beforeFilter(ServerFilter filter, ServerRequest req, ServerResponse res) {
                traceLog.add(filter.getClass().getSimpleName() + " start");
            }

            @Override
            public void afterFilter(ServerFilter filter, long elapsed, ServerRequest req, ServerResponse res) {
                String last = traceLog.remove(traceLog.size() - 1);
                traceLog.add(last + " -> " + (elapsed / 1_000_000) + "ms");
            }
        });

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long total = (System.nanoTime() - start) / 1_000_000;
            log.info("[TRACE] {} status={} total={}ms chain={}",
                    traceId, response.getStatus(), total, String.join(" | ", traceLog));
        }
    }

    /**
     * 从请求中获取 traceId。
     *
     * @param request 当前请求
     * @return traceId，不存在时返回 null
     */
    public static String getTraceId(ServerRequest request) {
        Object value = request.getAttribute(TRACE_ID_KEY);
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }
}
