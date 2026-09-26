package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

/**
 * 跨域资源共享 跨域过滤器，为 HTTP 响应添加跨域资源共享头。
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

    /**
     * Alloworigin
    */
    private String allowOrigin = "*";
    /**
     * Allowmethods
    */
    private String allowMethods = "GET,POST,PUT,DELETE,PATCH,OPTIONS";
    /**
     * Allowheaders
    */
    private String allowHeaders = "*";
    /**
     * 最大值AGE
    */
    private String maxAge = "3600";
    /**
     * Allowcredentials
    */
    private boolean allowCredentials;

    /**
     * 是否已收到 cors.* 配置，未收到时不参与过滤器链。
     */
    private volatile boolean configured;

    @Override
    /**
     * 初始化
    */
    public void init(ServerFilterConfig config) throws Exception {
        String origin = config.getInitParameter("cors.allowOrigin");
        String methods = config.getInitParameter("cors.allowMethods");
        String headers = config.getInitParameter("cors.allowHeaders");
        String age = config.getInitParameter("cors.maxAge");
        String credentials = config.getInitParameter("cors.allowCredentials");
        // 默认值是通配 *，未显式配置时不挂载，避免对所有响应广播跨域头
        this.configured = present(origin) || present(methods) || present(headers) || present(age) || present(credentials);
        if (present(origin)) {
            this.allowOrigin = origin;
        }
        if (present(methods)) {
            this.allowMethods = methods;
        }
        if (present(headers)) {
            this.allowHeaders = headers;
        }
        if (present(age)) {
            this.maxAge = age;
        }
        if ("true".equalsIgnoreCase(credentials)) {
            this.allowCredentials = true;
        }
    }

    @Override
    /**
     * 是否启用
    */
    public boolean isEnabled() {
        return configured;
    }

    /**
     * 判断配置项是否显式给出。
     *
     * @param value 配置值
     * @return 非空时返回 {@code true}
     */
    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    @Override
    /**
     * 执行过滤
    */
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
    /**
     * 获取订单
    */
    public int getOrder() {
        return 5;
    }

    @Override
    /**
     * 获取过滤标识
    */
    public String getFilterId() {
        return "CorsServerFilter";
    }

    @Override
    /**
     * 支持协议
    */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }
}