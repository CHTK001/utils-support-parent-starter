package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * 请求日志过滤器，记录每个请求的方法、路径、状态码和耗时。
 *
 * <p>在请求进入时记录开始时间，过滤器链执行完毕后计算耗时并输出日志，日志走 SLF4J。
 *
 * <p>逐请求日志量较大，需配置 {@code requestlog.enabled=true} 才会参与过滤器链。
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public class LoggingServerFilter implements ServerFilter {

    /**
     * 是否已显式开启，未开启时不参与过滤器链。
     */
    private volatile boolean configured;

    /**
     * 初始化
    */
    @Override
    public void init(ServerFilterConfig config) throws Exception {
        this.configured = null != config && "true".equalsIgnoreCase(config.getInitParameter("requestlog.enabled"));
    }

    /**
     * 是否启用
    */
    @Override
    public boolean isEnabled() {
        return configured;
    }

    /**
     * 执行过滤
    */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        long startTime = System.currentTimeMillis();
        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        String path = request.getPath();
        String remoteAddr = request.getRemoteAddress();

        try {
            chain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            int status = response.getStatus();
            log.info("[{}] {} {} -> {} ({}ms)", remoteAddr, method, path, status, elapsed);
        }
    }

    /**
     * 获取订单
    */
    @Override
    public int getOrder() {
        // StaticResourceServerFilter 占 0，同序会让请求日志与静态资源短路的先后不确定
        return 7;
    }

    /**
     * 获取过滤标识
    */
    @Override
    public String getFilterId() {
        return "LoggingServerFilter";
    }
}