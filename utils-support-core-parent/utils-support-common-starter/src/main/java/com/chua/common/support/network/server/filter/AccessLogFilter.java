package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 访问日志过滤器。
 *
 * <p>记录每个请求的访问日志，包括客户端地址、方法、路径、状态码、处理耗时。
 * 适用于所有协议类型，日志输出至 SLF4J。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class AccessLogFilter implements ServerFilter {

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return 5;
    }

    /**
     * Do过滤
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        // 日志级别未开 info 时直接透传：否则每请求都要构造 remoteAddr/method/path 字符串并触发
        // slf4j 参数求值(getRemoteAddress 还会格式化地址)，是热路径上最昂贵的固定开销之一
        if (!log.isInfoEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            String remoteAddr = request.getRemoteAddress();
            String method = request.getMethod() != null ? request.getMethod().name() : "-";
            String path = request.getPath();
            int status = response.getStatus();
            log.info("{} \"{} {} {}\" {}ms", remoteAddr, method, path, status, elapsed);
        }
    }

    /**
     * SupportProtocols
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }
}
