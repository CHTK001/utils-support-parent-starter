package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.UUID;

/**
 * 请求 ID 过滤器，为每个请求生成唯一标识。
 *
 * <p>生成 UUID 并设置到：</p>
 * <ul>
 *   <li>请求属性 {@code _requestId} — 供下游 filter/handler 使用</li>
 *   <li>响应头 {@code X-Request-Id} — 返回给客户端</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/18
 */
public class RequestIdFilter implements ServerFilter {

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MIN_VALUE + 20;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        // e[0]; // 所有协议
        return new ProtocolType[] {};
    }

    @Override
    /**
     * Do过滤
     * @param request request
     * @param response response
     * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        // 优先使用客户端传入的 X-Request-Id
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        request.setAttribute("_requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        chain.doFilter(request, response);
    }

    /**
     * 从请求中获取 requestId。
     *
     * @param request 请求对象
     * @return requestId，不存在返回 null
     */
    public static String getRequestId(ServerRequest request) {
        Object value = request.getAttribute("_requestId");
        return value instanceof String ? (String) value : null;
    }
}
