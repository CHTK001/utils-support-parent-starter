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

    @Override
    /** 获取Order */
    public int getOrder() {
        return 5;
    }

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
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

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }
}
