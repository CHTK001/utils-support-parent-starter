package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 访问日志过滤器（异步）。
 *
 * <p>记录每个请求的客户端地址、方法、路径、状态码与处理耗时。
 * 适用于所有协议类型。</p>
 *
 * <p><b>默认不注册。</b>本过滤器会在每个请求上产出一条日志，即使异步写出也需要在请求线程
 * 捕获请求上下文，属于额外的热路径开销。因此服务器启动时不自动挂载，需要时显式开启：</p>
 * <ul>
 *   <li>{@code -Dserver.accesslog.enabled=true} — 启动时自动注册</li>
 *   <li>或 {@code server.addFilter(new AccessLogServerFilter())} 手动注册</li>
 * </ul>
 *
 * <p>日志的格式化与写盘全部在 {@link AsyncFilterLogger} 的守护线程完成，请求线程只做一次
 * 非阻塞入队；队列满时丢弃日志而不阻塞请求。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class AccessLogServerFilter implements ServerFilter {

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
        if (!log.isInfoEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String remoteAddr = request.getRemoteAddress();
            String method = request.getMethod() != null ? request.getMethod().name() : "-";
            String path = request.getPath();
            int status = response.getStatus();
            AsyncFilterLogger.submit(() ->
                    log.info("{} \"{} {} {}\" {}ms", remoteAddr, method, path, status, elapsedMs));
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
