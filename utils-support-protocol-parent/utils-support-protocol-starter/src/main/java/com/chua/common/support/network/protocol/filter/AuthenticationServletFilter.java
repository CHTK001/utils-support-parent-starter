package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.network.protocol.request.HttpServletResponse;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.ServletFilterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 认证Servlet过滤器
 * <p>
 * 提供基础的 HTTP 认证能力，支持 Basic Authentication 和 Token 认证。
 * 这是一个示例过滤器，展示了如何实现请求拦截和认证逻辑。
 *
 * @author CH
 * @since 2024/7/8
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
public class AuthenticationServletFilter extends AbstractServletFilter implements ServletFilter {

    /**
     * 不参与认证的路径列表
     */
    private Set<String> excludePaths = new HashSet<>();

    /**
     * 有效 Token 集合
     */
    private Set<String> validTokens = new HashSet<>();

    /**
     * 有效账号集合（username:password）
     */
    private Set<String> validUsers = new HashSet<>();

    /**
     * 是否启用 Basic 认证
     */
    private boolean enableBasicAuth = true;

    /**
     * 是否启用 Token 认证
     */
    private boolean enableTokenAuth = true;

    /**
     * 执行过滤逻辑
     *
     * @param request  请求
     * @param response 响应
     * @param chain    过滤器链
     * @throws Exception 过滤执行异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        String path = request.getPath();

        // 检查是否需要认证
        if (isExcludePath(path)) {
            if (log.isDebugEnabled()) {
                log.debug("路径 {} 无需认证，跳过认证检查", path);
            }
            chain.doFilter(request, response);
            return;
        }

        try {
            // 执行认证检查
            if (!authenticate(request)) {
                log.warn("[协议][认证]认证失败 - {} {} 来自 {}", request.getMethod(), path, request.getClientIp());
                sendUnauthorizedResponse(response);
                return;
            }
        } catch (Exception e) {
            // 认证链路自身异常时放行请求，避免影响整体可用性
            log.error("[协议][认证]认证过程异常，放行请求: {} {}", request.getMethod(), path, e);
            chain.doFilter(request, response);
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("[协议][认证]认证成功 - {} {}", request.getMethod(), path);
        }

        // 认证成功，继续执行过滤器链
        chain.doFilter(request, response);
    }

    /**
     * 初始化过滤器配置
     *
     * @param config 过滤器配置
     * @throws Exception 初始化异常
     */
    @Override
    public void init(ServletFilterConfig config) throws Exception {
        if (config != null) {
            String excludePathsConfig = config.getInitParameter("excludePaths");
            if (excludePathsConfig != null) {
                String[] paths = excludePathsConfig.split(",");
                for (String path : paths) {
                    excludePaths.add(path.trim());
                }
            }
            
            String tokensConfig = config.getInitParameter("validTokens");
            if (tokensConfig != null) {
                String[] tokens = tokensConfig.split(",");
                for (String token : tokens) {
                    validTokens.add(token.trim());
                }
            }
            
            String usersConfig = config.getInitParameter("validUsers");
            if (usersConfig != null) {
                String[] users = usersConfig.split(",");
                for (String user : users) {
                    validUsers.add(user.trim());
                }
            }
            
            String basicAuthConfig = config.getInitParameter("enableBasicAuth");
            if (basicAuthConfig != null) {
                enableBasicAuth = Boolean.parseBoolean(basicAuthConfig);
            }
            
            String tokenAuthConfig = config.getInitParameter("enableTokenAuth");
            if (tokenAuthConfig != null) {
                enableTokenAuth = Boolean.parseBoolean(tokenAuthConfig);
            }
        }
        if (excludePaths.isEmpty()) {
            excludePaths.add("/health");
            excludePaths.add("/status");
            excludePaths.add("/ping");
        }
        log.info("[协议][认证]AuthenticationServletFilter 初始化完成, excludePaths: {}, enableBasicAuth: {}, enableTokenAuth: {}",
                excludePaths, enableBasicAuth, enableTokenAuth);
    }

    /**
     * 销毁过滤器
     */
    @Override
    public void destroy() {
        log.info("[协议][认证]AuthenticationServletFilter 销毁");
    }

    /**
     * 过滤器名称
     *
     * @return 名称
     */
    @Override
    public String getFilterName() {
        return "AuthenticationServletFilter";
    }

    /**
     * 执行优先级
     *
     * @return 数值越小越先执行
     */
    @Override
    public int getPriority() {
        return 10;
    }

    /**
     * 检查路径是否排除认证
     *
     * @param path 请求路径
     * @return true 表示无需认证
     */
    private boolean isExcludePath(String path) {
        if (path == null) {
            return false;
        }
        
        for (String excludePath : excludePaths) {
            if (path.equals(excludePath) || path.startsWith(excludePath + "/")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 执行认证检查
     *
     * @param request 请求
     * @return true 表示认证通过
     */
    private boolean authenticate(ServletRequest request) {
        // 尝试Token认证
        if (enableTokenAuth && authenticateByToken(request)) {
            return true;
        }
        
        // 尝试Basic认证
        return enableBasicAuth && authenticateByBasic(request);
    }

    /**
     * Token 认证
     *
     * @param request 请求
     * @return true 表示认证通过
     */
    private boolean authenticateByToken(ServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            return false;
        }
        
        if (authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            return validTokens.contains(token);
        }
        
        String tokenParam = request.getParameter("token");
        if (tokenParam != null) {
            return validTokens.contains(tokenParam);
        }
        
        return false;
    }

    /**
     * Basic 认证
     *
     * @param request 请求
     * @return true 表示认证通过
     */
    private boolean authenticateByBasic(ServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return false;
        }
        
        String credentials = authorization.substring(6);
        byte[] decodedBytes = Base64.getDecoder().decode(credentials);
        String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
        return validUsers.contains(decoded);
    }

    /**
     * 发送未授权响应
     *
     * @param response 响应
     */
    private void sendUnauthorizedResponse(ServletResponse response) {
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setStatusCode(401);
            httpResponse.setStatusMessage("Unauthorized");
            httpResponse.setHeader("WWW-Authenticate", "Basic realm=\"V2Protocol Server\"");
            httpResponse.setContentType("application/json");
            httpResponse.setBodyString("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
        } else {
            response.setStatusCode(401);
            response.setStatusMessage("Unauthorized");
            response.addHeader("WWW-Authenticate", "Basic realm=\"V2Protocol Server\"");
        }

        response.setTerminateEarly(true);
    }

    // ========== 业务方法 ==========

    public void addExcludePath(String path) {
        this.excludePaths.add(path);
    }

    public void removeExcludePath(String path) {
        this.excludePaths.remove(path);
    }

    public void addValidToken(String token) {
        this.validTokens.add(token);
    }

    public void removeValidToken(String token) {
        this.validTokens.remove(token);
    }

    public void addValidUser(String username, String password) {
        this.validUsers.add(username + ":" + password);
    }

    public void removeValidUser(String username, String password) {
        this.validUsers.remove(username + ":" + password);
    }
}