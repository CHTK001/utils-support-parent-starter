package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.ServletFilterConfig;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * CORS（跨域资源共享）Servlet过滤器
 * <p>
 * 处理跨域请求，支持：
 * 1. 配置允许的源域名
 * 2. 配置允许的HTTP方法
 * 3. 配置允许的请求头
 * 4. 配置是否允许携带凭证
 * 5. 配置预检请求的缓存时间
 * 6. 支持通配符和动态域名匹配
 *
 * @author CH
 * @since 2024/7/9
 */
@Slf4j
@Spi("cors")
@SpiDescribe("CORS跨域资源共享")
@SpiSupport("http")
public class CorsServletFilter  extends AbstractServletFilter implements ServletFilter {

    /**
     * 是否启用
     */
    private volatile boolean enabled = true;

    /**
     * 允许的源域名
     */
    private final Set<String> allowedOrigins = new HashSet<>();

    /**
     * 允许的HTTP方法
     */
    private final Set<String> allowedMethods = new HashSet<>();

    /**
     * 允许的请求头
     */
    private final Set<String> allowedHeaders = new HashSet<>();

    /**
     * 暴露的响应头
     */
    private final Set<String> exposedHeaders = new HashSet<>();

    /**
     * 是否允许携带凭证
     */
    private boolean allowCredentials = false;

    /**
     * 预检请求的最大缓存时间（秒）
     */
    private int maxAge = 3600;

    /**
     * 是否允许所有源域名
     */
    private boolean allowAllOrigins = false;

    /**
     * 是否允许所有方法
     */
    private boolean allowAllMethods = false;

    /**
     * 是否允许所有请求头
     */
    private boolean allowAllHeaders = false;

    @Override
    public void init(ServletFilterConfig config) throws Exception {
        if (config != null) {
            enabled = config.getBooleanParameter("enabled", true);
            allowCredentials = config.getBooleanParameter("allowCredentials", false);
            maxAge = config.getIntParameter("maxAge", 3600);

            // 解析允许的源域名
            String originsParam = config.getStringParameter("allowedOrigins", "*");
            parseAllowedOrigins(originsParam);

            // 解析允许的HTTP方法
            String methodsParam = config.getStringParameter("allowedMethods", "GET,POST,PUT,DELETE,OPTIONS,HEAD,PATCH");
            parseAllowedMethods(methodsParam);

            // 解析允许的请求头
            String headersParam = config.getStringParameter("allowedHeaders", "*");
            parseAllowedHeaders(headersParam);

            // 解析暴露的响应头
            String exposedParam = config.getStringParameter("exposedHeaders", "");
            parseExposedHeaders(exposedParam);
        }

        log.info("CORS过滤器初始化完成，允许所有源: {}, 允许凭证: {}, 最大缓存时间: {}s",
                allowAllOrigins, allowCredentials, maxAge);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        String method = request.getMethod();

        // 检查是否为CORS请求
        if (StringUtils.isBlank(origin)) {
            // 非CORS请求，直接继续
            chain.doFilter(request, response);
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("处理CORS请求: Origin={}, Method={}", origin, method);
        }

        // 检查源域名是否被允许
        if (!isOriginAllowed(origin)) {
            log.warn("CORS请求被拒绝，不允许的源域名: {}", origin);
            response.setStatusCode(403);
            response.setStatusMessage("Forbidden");
            response.setContentType("application/json");
            response.setBodyString("{\"error\":\"CORS request denied\",\"reason\":\"Origin not allowed\"}");
            return;
        }

        // 处理预检请求
        if ("OPTIONS".equalsIgnoreCase(method)) {
            handlePreflightRequest(request, response, origin);
            return;
        }

        // 处理简单请求
        handleSimpleRequest(request, response, origin);

        // 继续执行过滤器链
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        allowedOrigins.clear();
        allowedMethods.clear();
        allowedHeaders.clear();
        exposedHeaders.clear();
        log.info("CORS过滤器已销毁");
    }

    @Override
    public String getFilterName() {
        return "CorsServletFilter";
    }

    @Override
    public int getPriority() {
        return 15; // 较高优先级，在认证之前处理CORS
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 解析允许的源域名
     */
    private void parseAllowedOrigins(String originsParam) {
        if ("*".equals(originsParam)) {
            allowAllOrigins = true;
            log.info("CORS配置：允许所有源域名");
        } else {
            String[] origins = originsParam.split(",");
            for (String origin : origins) {
                String trimmed = origin.trim();
                if (StringUtils.isNotBlank(trimmed)) {
                    allowedOrigins.add(trimmed);
                }
            }
            log.info("CORS配置：允许的源域名: {}", allowedOrigins);
        }
    }

    /**
     * 解析允许的HTTP方法
     */
    private void parseAllowedMethods(String methodsParam) {
        if ("*".equals(methodsParam)) {
            allowAllMethods = true;
            log.info("CORS配置：允许所有HTTP方法");
        } else {
            String[] methods = methodsParam.split(",");
            for (String method : methods) {
                String trimmed = method.trim().toUpperCase();
                if (StringUtils.isNotBlank(trimmed)) {
                    allowedMethods.add(trimmed);
                }
            }
            log.info("CORS配置：允许的HTTP方法: {}", allowedMethods);
        }
    }

    /**
     * 解析允许的请求头
     */
    private void parseAllowedHeaders(String headersParam) {
        if ("*".equals(headersParam)) {
            allowAllHeaders = true;
            log.info("CORS配置：允许所有请求头");
        } else {
            String[] headers = headersParam.split(",");
            for (String header : headers) {
                String trimmed = header.trim().toLowerCase();
                if (StringUtils.isNotBlank(trimmed)) {
                    allowedHeaders.add(trimmed);
                }
            }
            log.info("CORS配置：允许的请求头: {}", allowedHeaders);
        }
    }

    /**
     * 解析暴露的响应头
     */
    private void parseExposedHeaders(String exposedParam) {
        if (StringUtils.isNotBlank(exposedParam)) {
            String[] headers = exposedParam.split(",");
            for (String header : headers) {
                String trimmed = header.trim();
                if (StringUtils.isNotBlank(trimmed)) {
                    exposedHeaders.add(trimmed);
                }
            }
            log.info("CORS配置：暴露的响应头: {}", exposedHeaders);
        }
    }

    /**
     * 检查源域名是否被允许
     */
    private boolean isOriginAllowed(String origin) {
        if (allowAllOrigins) {
            return true;
        }

        if (allowedOrigins.contains(origin)) {
            return true;
        }

        // 检查通配符匹配
        for (String allowedOrigin : allowedOrigins) {
            if (allowedOrigin.contains("*")) {
                if (matchesWildcard(origin, allowedOrigin)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 通配符匹配
     */
    private boolean matchesWildcard(String origin, String pattern) {
        // 简单的通配符匹配实现
        String regex = pattern.replace("*", ".*");
        return origin.matches(regex);
    }

    /**
     * 处理预检请求
     */
    private void handlePreflightRequest(ServletRequest request, ServletResponse response, String origin) {
        if (log.isDebugEnabled()) {
            log.debug("处理CORS预检请求: {}", origin);
        }

        String requestMethod = request.getHeader("Access-Control-Request-Method");
        String requestHeaders = request.getHeader("Access-Control-Request-Headers");

        // 检查请求方法是否被允许
        if (StringUtils.isNotBlank(requestMethod) && !isMethodAllowed(requestMethod)) {
            log.warn("CORS预检请求被拒绝，不允许的方法: {}", requestMethod);
            response.terminateEarly(403, "Forbidden", 
                "{\"error\":\"CORS preflight denied\",\"reason\":\"Method not allowed\"}");
            return;
        }

        // 检查请求头是否被允许
        if (StringUtils.isNotBlank(requestHeaders) && !areHeadersAllowed(requestHeaders)) {
            log.warn("CORS预检请求被拒绝，不允许的请求头: {}", requestHeaders);
            response.terminateEarly(403, "Forbidden",
                "{\"error\":\"CORS preflight denied\",\"reason\":\"Headers not allowed\"}");
            return;
        }

        // 设置CORS响应头
        setCorsHeaders(response, origin);

        // 设置预检请求特有的响应头
        if (allowAllMethods) {
            response.addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,HEAD,PATCH");
        } else {
            response.addHeader("Access-Control-Allow-Methods", String.join(",", allowedMethods));
        }

        if (allowAllHeaders) {
            if (StringUtils.isNotBlank(requestHeaders)) {
                response.addHeader("Access-Control-Allow-Headers", requestHeaders);
            }
        } else {
            response.addHeader("Access-Control-Allow-Headers", String.join(",", allowedHeaders));
        }

        response.addHeader("Access-Control-Max-Age", String.valueOf(maxAge));

        // 预检请求直接返回200
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.terminateEarly();

        if (log.isDebugEnabled()) {
            log.debug("CORS预检请求处理完成: {}", origin);
        }
    }

    /**
     * 处理简单请求
     */
    private void handleSimpleRequest(ServletRequest request, ServletResponse response, String origin) {
        if (log.isDebugEnabled()) {
            log.debug("处理CORS简单请求: {}", origin);
        }
        setCorsHeaders(response, origin);
    }

    /**
     * 设置CORS响应头
     */
    private void setCorsHeaders(ServletResponse response, String origin) {
        // 设置允许的源域名
        if (allowAllOrigins && !allowCredentials) {
            response.addHeader("Access-Control-Allow-Origin", "*");
        } else {
            response.addHeader("Access-Control-Allow-Origin", origin);
        }

        // 设置是否允许携带凭证
        if (allowCredentials) {
            response.addHeader("Access-Control-Allow-Credentials", "true");
        }

        // 设置暴露的响应头
        if (!exposedHeaders.isEmpty()) {
            response.addHeader("Access-Control-Expose-Headers", String.join(",", exposedHeaders));
        }

        // 添加Vary头，确保缓存正确处理
        response.addHeader("Vary", "Origin");
    }

    /**
     * 检查HTTP方法是否被允许
     */
    private boolean isMethodAllowed(String method) {
        if (allowAllMethods) {
            return true;
        }
        return allowedMethods.contains(method.toUpperCase());
    }

    /**
     * 检查请求头是否被允许
     */
    private boolean areHeadersAllowed(String requestHeaders) {
        if (allowAllHeaders) {
            return true;
        }

        String[] headers = requestHeaders.split(",");
        for (String header : headers) {
            String trimmed = header.trim().toLowerCase();
            if (!allowedHeaders.contains(trimmed)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.HTTPS};
    }
}