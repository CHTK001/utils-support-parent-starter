package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;

/**
 * 请求日志过滤器，记录每个请求的方法、路径、状态码和耗时。
 *
 * <p>在请求进入时记录开始时间，过滤器链执行完毕后计算耗时并输出日志。
 * 默认使用 STDOUT 输出，可替换为 SLF4J 等日志框架。
 *
 * @author CH
 * @since 2026/07/16
 */
public class LoggingServerFilter implements ServerFilter {

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
            System.out.printf("[%s] %s %s → %d (%dms)%n", remoteAddr, method, path, status, elapsed);
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getFilterId() {
        return "LoggingServerFilter";
    }
}