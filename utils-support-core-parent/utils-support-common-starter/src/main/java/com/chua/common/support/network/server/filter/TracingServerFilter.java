package com.chua.common.support.network.server.filter;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 链路追踪过滤器（异步）。
 *
 * <p>为每个请求生成唯一 traceId，通过 {@link FilterChainListener} 记录每个 filter 的执行耗时，
 * 请求结束时输出完整的链路追踪日志（包含 filter 执行顺序和各节点耗时）。</p>
 *
 * <p><b>默认不注册。</b>追踪需要在请求线程上逐 filter 采集耗时，并额外生成 traceId，
 * 属于排障工具而非生产默认能力。需要时显式开启：</p>
 * <ul>
 *   <li>{@code -Dserver.tracing.enabled=true} — 启动时自动注册</li>
 *   <li>或 {@code server.addFilter(new TracingServerFilter())} 手动注册</li>
 * </ul>
 *
 * <p>耗时采集只写入两个基本类型数组(无装箱、无列表扩容)，日志拼接与写盘全部在
 * {@link AsyncFilterLogger} 的守护线程完成，请求线程只做一次非阻塞入队。</p>
 *
 * <p>使用方式：服务器端注册后，通过请求属性 {@code _traceId} 传递 traceId。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class TracingServerFilter implements ServerFilter {

    /**
     * traceId 的请求属性键名。
     */
    private static final String TRACE_ID_KEY = "_traceId";

    /**
     * 链监听器列表的请求属性键名。
     */
    private static final String LISTENERS_KEY = "_chainListeners";

    /**
     * 十六进制字符表。
     */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 60;
    }

    /**
     * Do过滤
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        if (!log.isInfoEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String traceId = newTraceId();
        request.setAttribute(TRACE_ID_KEY, traceId);

        TraceRecorder recorder = new TraceRecorder();
        List<FilterChainListener> listeners = Wraps.list(1);
        listeners.add(recorder);
        request.setAttribute(LISTENERS_KEY, listeners);

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long total = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            AsyncFilterLogger.submit(() ->
                    log.info("[TRACE] {} status={} total={}ms chain={}", traceId, status, total, recorder.render()));
        }
    }

    /**
     * 生成 16 位十六进制 traceId。
     *
     * <p>不用 {@link UUID#randomUUID()}：它走共享的 SecureRandom，在多事件循环线程下会
     * 变成串行点，且 {@code toString().replace("-", "").substring(0, 16)} 要额外分配
     * 3 个字符串。traceId 只需要「同一时刻基本不重复」，不需要密码学随机。</p>
     *
     * @return 16 位十六进制 traceId
     */
    private static String newTraceId() {
        long first = ThreadLocalRandom.current().nextLong();
        long second = ThreadLocalRandom.current().nextLong();
        char[] buf = new char[16];
        toHex(first, buf, 0);
        toHex(second, buf, 8);
        return new String(buf);
    }

    /**
     * 把 long 的低 32 位写成 8 位十六进制。
     *
     * @param value  数值
     * @param buf    目标缓冲
     * @param offset 写入起点
     */
    private static void toHex(long value, char[] buf, int offset) {
        for (int i = 0; i < 8; i++) {
            int digit = (int) ((value >>> ((7 - i) << 2)) & 0xF);
            buf[offset + i] = HEX_DIGITS[digit];
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

    /**
     * 链路耗时采集器。
     *
     * <p>在请求线程上只写入 {@code String[] + long[]}，不做字符串拼接与日志调用；
     * 拼接推迟到异步线程的 {@link #render()}。</p>
     */
    private static final class TraceRecorder implements FilterChainListener {

        /**
         * 初始容量。
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * 已执行的过滤器。
         */
        private ServerFilter[] filters = new ServerFilter[INITIAL_CAPACITY];

        /**
         * 各 filter 耗时(纳秒)。
         */
        private long[] elapsedNanos = new long[INITIAL_CAPACITY];

        /**
         * 已采集数量。
         */
        private int size;

        @Override
        public void afterFilter(ServerFilter filter, long elapsed, ServerRequest request, ServerResponse response) {
            if (size == filters.length) {
                int capacity = size << 1;
                filters = Arrays.copyOf(filters, capacity);
                elapsedNanos = Arrays.copyOf(elapsedNanos, capacity);
            }
            filters[size] = filter;
            elapsedNanos[size] = elapsed;
            size++;
        }

        /**
         * 拼接链路日志内容(在异步线程执行)。
         *
         * @return 形如 {@code GzipFilter start -> 0ms | Handler start -> 1ms}
         */
        String render() {
            StringBuilder sb = new StringBuilder(size * 32);
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(shortName(filters[i])).append(" start -> ")
                        .append(elapsedNanos[i] / 1_000_000).append("ms");
            }
            return sb.toString();
        }

        /**
         * 取过滤器的短类名。
         *
         * <p>用 {@link ServerFilter#getFilterId()} 而不是 {@code getClass().getSimpleName()}：
         * 响应式链上的过滤器被包成 {@code StaticReactiveFilterAdapter}，后者会把
         * filterId 透传给真实过滤器。</p>
         *
         * @param filter 过滤器
         * @return 短类名
         */
        private static String shortName(ServerFilter filter) {
            String id = filter.getFilterId();
            int dot = id.lastIndexOf('.');
            return dot >= 0 ? id.substring(dot + 1) : id;
        }
    }
}
