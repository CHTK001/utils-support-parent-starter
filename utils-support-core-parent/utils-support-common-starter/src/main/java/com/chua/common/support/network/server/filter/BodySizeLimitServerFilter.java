package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 请求体大小限制过滤器。
 *
 * <p>对携带 Content-Length 的请求做硬限制,超过阈值直接返回
 * 413 Payload Too Large,阻止大请求体占用传输层内存与处理时间。
 * 与解析器层 {@code ServerSetting.maxRequestSize}(解析期拦截)互补,
 * 本过滤器提供业务侧可独立调整的显式上限与标准化错误响应。</p>
 *
 * <p>同时实现同步({@link ServerFilter})与响应式({@link ReactiveServerFilter})
 * 两种链接口:阻塞传输(JDK HttpServer 等)走同步链,
 * NIO/AIO 响应式传输走响应式链,逻辑保持一致。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
public class BodySizeLimitServerFilter implements ServerFilter, ReactiveServerFilter {

    /**
     * HTTP 413 状态码:请求体超限
     */
    private static final int STATUS_PAYLOAD_TOO_LARGE = 413;

    /** 允许的最大请求体尺寸(字节) */
    private final long maxBodyBytes;

    /**
     * 创建请求体大小限制过滤器。
     *
     * @param maxBodyBytes 允许的最大请求体尺寸(字节)
     */
    public BodySizeLimitServerFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MIN_VALUE + 20;
    }

    @Override
    /** SupportPath:Access Filter,每次请求都触发(显式覆写消除双接口默认方法冲突) */
    public String supportPath() {
        return null;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    @Override
    /**
     * Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        if (exceedsLimit(request)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /**
     * 响应式Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        if (exceedsLimit(request)) {
            reject(response);
            return CompletableFuture.completedStage(null);
        }
        return chain.doFilter(request, response);
    }

    /**
     * 判断请求体是否超过限制。
     *
     * @param request 请求对象
     * @return true 表示超限
     */
    private boolean exceedsLimit(ServerRequest request) {
        long contentLength = request.getContentLength();
        // Content-Length 为 -1(chunked/无体)时交由解析器层的 maxRequestSize 兜底
        return contentLength > maxBodyBytes;
    }

    /**
     * 返回 413 标准错误响应并终止链。
     *
     * @param response 响应对象
     */
    private void reject(ServerResponse response) {
        if (!response.isEnded()) {
            response.setStatus(STATUS_PAYLOAD_TOO_LARGE);
            response.setBody("{\"error\":\"Payload Too Large\",\"limit\":" + maxBodyBytes + "}");
            response.end();
        }
    }
}
