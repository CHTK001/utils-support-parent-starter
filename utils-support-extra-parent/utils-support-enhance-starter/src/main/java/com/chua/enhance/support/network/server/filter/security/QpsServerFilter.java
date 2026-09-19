package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * QPS 限流过滤器，基于滑动窗口的全局限流。
 *
 * <p>不区分客户端 IP，对所有请求进行统一的 QPS 限制。
 * 使用滑动窗口计数器，每秒重置一次，超过阈值返回 429。
 *
 * <h2>配置参数</h2>
 * <ul>
 *   <li>{@code qps.max} — 每秒最大请求数，默认 1000</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class QpsServerFilter implements ServerFilter {

    /**
     * 默认每秒最大请求数
     */
    private static final int DEFAULT_MAX_QPS = 1000;

    /**
     * 最大值QPS
    */
    private int maxQps = DEFAULT_MAX_QPS;

    /**
     * 当前秒的请求计数
     */
    private final AtomicLong counter = new AtomicLong(0);

    /**
     * 当前窗口起始时间戳
     */
    private volatile long windowStartTime = System.currentTimeMillis();

    @Override
    /**
     * 初始化
    */
    public void init(ServerFilterConfig config) throws Exception {
        String max = config.getInitParameter("qps.max");
        if (max != null && !max.isEmpty()) {
            this.maxQps = Integer.parseInt(max);
        }
    }

    @Override
    /**
     * 执行过滤
    */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        long now = System.currentTimeMillis();
        if (now - windowStartTime >= 1000) {
            synchronized (this) {
                if (now - windowStartTime >= 1000) {
                    counter.set(0);
                    windowStartTime = now;
                }
            }
        }
        if (counter.incrementAndGet() > maxQps) {
            response.end(429, "{\"error\":\"Too Many Requests\",\"message\":\"系统繁忙，请稍后重试\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /**
     * 获取订单
    */
    public int getOrder() {
        return 22;
    }

    @Override
    /**
     * 获取过滤标识
    */
    public String getFilterId() {
        return "QpsServerFilter";
    }
}
