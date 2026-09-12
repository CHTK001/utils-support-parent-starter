package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
   * 执行 防护过滤器，基于滑动窗口的请求频率限制。
 *
 * <p>按客户端 IP 统计固定时间窗口内的请求次数，超过阈值返回 429。
 * 使用 {@link ConcurrentHashMap} + {@link AtomicInteger} 实现线程安全的计数。
 *
 * <h2>配置参数</h2>
 * <ul>
 *   <li>{@code dos.maxRequests} — 时间窗口内最大请求数，默认 100</li>
 *   <li>{@code dos.windowSeconds} — 时间窗口大小（秒），默认 60</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class DosServerFilter implements ServerFilter {

    /**
     * 默认最大请求数
     */
    private static final int DEFAULT_MAX_REQUESTS = 100;
    /**
     * 默认时间窗口（秒）
     */
    private static final int DEFAULT_WINDOW_SECONDS = 60;

    /** 最大值Requests */
    private int maxRequests = DEFAULT_MAX_REQUESTS;
    /** 窗口秒 */
    private int windowSeconds = DEFAULT_WINDOW_SECONDS;

    /**
     * IP → 请求计数
     */
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    /**
     * IP → 窗口起始时间戳
     */
    private final Map<String, Long> windowStartTimes = new ConcurrentHashMap<>();

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        String max = config.getInitParameter("dos.maxRequests");
        if (max != null && !max.isEmpty()) {
            this.maxRequests = Integer.parseInt(max);
        }
        String window = config.getInitParameter("dos.windowSeconds");
        if (window != null && !window.isEmpty()) {
            this.windowSeconds = Integer.parseInt(window);
        }
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String ip = resolveClientIp(request);
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;

        AtomicInteger counter = requestCounts.computeIfAbsent(ip, k -> new AtomicInteger(0));
        windowStartTimes.putIfAbsent(ip, now);

        long windowStart = windowStartTimes.get(ip);
        if (now - windowStart > windowMillis) {
            counter.set(0);
            windowStartTimes.put(ip, now);
        }

        if (counter.incrementAndGet() > maxRequests) {
            response.end(429, "{\"error\":\"Too Many Requests\",\"message\":\"请求过于频繁，请稍后重试\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 20;
    }

    @Override
    /** 获取过滤标识 */
    public String getFilterId() {
        return "DosServerFilter";
    }

    /**
      * 解析客户端真实 IP，优先从 X-远期-For 头获取。
     * @param request 请求
     * @return resolve客户端ip的结果
     */
    private String resolveClientIp(ServerRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            int idx = forwarded.indexOf(',');
            return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddress();
    }
}