package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

/**
 * CORS 跨域过滤器，为 HTTP 响应添加跨域资源共享头。
 *
 * <p>处理 OPTIONS 预检请求直接返回 204，为普通请求添加 Access-Control-* 头。
 * 支持动态配置允许的源、方法、头等。
 *
 * <h2>配置参数</h2>
 * <ul>
 *   <li>{@code cors.allowOrigin} — 允许的源，默认 {@code *}</li>
 *   <li>{@code cors.allowMethods} — 允许的方法，默认 {@code GET,POST,PUT,DELETE,PATCH,OPTIONS}</li>
 *   <li>{@code cors.allowHeaders} — 允许的请求头，默认 {@code *}</li>
 *   <li>{@code cors.maxAge} — 预检缓存时间（秒），默认 3600</li>
 *   <li>{@code cors.allowCredentials} — 是否允许凭证，默认 false</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class CorsServerFilter implements ServerFilter {

    /** Alloworigin */
    private String allowOrigin = "*";
    /** Allowmethods */
    private String allowMethods = "GET,POST,PUT,DELETE,PATCH,OPTIONS";
    /** Allowheaders */
    private String allowHeaders = "*";
    /** 最大值AGE */
    private String maxAge = "3600";
    /** Allowcredentials */
    private boolean allowCredentials;

    @Override
    public void init(ServerFilterConfig config) throws Exception {
        String origin = config.getInitParameter("cors.allowOrigin");
        if (origin != null && !origin.isEmpty()) {
            this.allowOrigin = origin;
        }
        String methods = config.getInitParameter("cors.allowMethods");
        if (methods != null && !methods.isEmpty()) {
            this.allowMethods = methods;
        }
        String headers = config.getInitParameter("cors.allowHeaders");
        if (headers != null && !headers.isEmpty()) {
            this.allowHeaders = headers;
        }
        String age = config.getInitParameter("cors.maxAge");
        if (age != null && !age.isEmpty()) {
            this.maxAge = age;
        }
        String credentials = config.getInitParameter("cors.allowCredentials");
        if ("true".equalsIgnoreCase(credentials)) {
            this.allowCredentials = true;
        }
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        response.setHeader("Access-Control-Allow-Origin", allowOrigin);
        response.setHeader("Access-Control-Allow-Methods", allowMethods);
        response.setHeader("Access-Control-Allow-Headers", allowHeaders);
        response.setHeader("Access-Control-Max-Age", maxAge);
        if (allowCredentials) {
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod().name())) {
            response.setStatus(204);
            response.end();
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public String getFilterId() {
        return "CorsServerFilter";
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }
}