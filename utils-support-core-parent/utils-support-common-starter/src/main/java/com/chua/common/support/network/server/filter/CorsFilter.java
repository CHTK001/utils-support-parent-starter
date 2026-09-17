package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

/**
* CORS 跨域过滤器。
*
* <p>根据 {@link ServerSetting} 中的 CORS 配置自动添加跨域响应头。
* 仅在 {@link ServerSetting.CorsConfig#isAllowOrigin()} 为 true 时生效。</p>
*
* @author CH
* @since 2024/12/20
 */
public class CorsFilter implements ServerFilter {

    /** setting */
    private volatile ServerSetting setting;

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MIN_VALUE + 50;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        if (config != null) {
            this.setting = config.getServerSetting();
        } else {
            this.setting = ServerSetting.defaults();
        }
    }

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        if (setting == null) {
            chain.doFilter(request, response);
            return;
        }
        ServerSetting.CorsConfig cors = setting.getCors();
        if (cors == null || !cors.isAllowOrigin()) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String allowedOrigins = cors.getAllowedOrigins();
        if (!isOriginAllowed(origin, allowedOrigins)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Access-Control-Allow-Origin", "*".equals(allowedOrigins) ? "*" : origin);
        response.setHeader("Vary", "Origin");
        String allowedMethods = cors.getAllowedMethods();
        if (allowedMethods != null && !allowedMethods.isEmpty()) {
            response.setHeader("Access-Control-Allow-Methods", allowedMethods);
        }
        String allowedHeaders = cors.getAllowedHeaders();
        if (allowedHeaders != null && !allowedHeaders.isEmpty()) {
            response.setHeader("Access-Control-Allow-Headers", allowedHeaders);
        }
        if (request.getMethod() == com.chua.common.support.network.http.HttpMethod.OPTIONS
                && request.getHeader("Access-Control-Request-Method") != null) {
            response.setStatus(204);
            response.end();
            return;
        }
        chain.doFilter(request, response);
    }

    /**
    * 判断请求源是否在配置白名单中。
    *
    * @param origin         请求源
    * @param allowedOrigins 允许的源列表
    * @return true 表示允许
    */
    private boolean isOriginAllowed(String origin, String allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return false;
        }
        if ("*".equals(allowedOrigins.trim())) {
            return true;
        }
        for (String allowedOrigin : allowedOrigins.split(",")) {
            if (origin.equals(allowedOrigin.trim())) {
                return true;
            }
        }
        return false;
    }
}
