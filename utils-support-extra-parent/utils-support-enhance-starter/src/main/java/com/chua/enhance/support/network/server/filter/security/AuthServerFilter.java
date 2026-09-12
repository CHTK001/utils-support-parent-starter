package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

import java.util.HashSet;
import java.util.Set;

/**
* 认证过滤器，验证请求是否携带有效的认证凭证。
*
* <p>检查请求头中的 {@code Authorization} 字段，未携带或 token 不在白名单中时
* 返回 401 并终止过滤器链。默认不校验具体 令牌 值，仅检查是否携带。
*
* <h2>配置参数</h2>
* <ul>
*   <li>{@code auth.header} — 认证头名称，默认 {@code Authorization}</li>
*   <li>{@code auth.tokens} — 逗号分隔的有效 token 白名单，为空则跳过 token 校验</li>
*   <li>{@code auth.excludePaths} — 逗号分隔的排除路径，不校验</li>
* </ul>
*
* @author CH
* @since 2026/07/16
 */
public class AuthServerFilter implements ServerFilter {

    /**
    * 默认认证头名称
     */
    private static final String DEFAULT_AUTH_HEADER = "Authorization";

    /**
    * 认证头名称
     */
    private String authHeader = DEFAULT_AUTH_HEADER;

    /**
    * 有效 令牌 白名单
     */
    private final Set<String> validTokens = new HashSet<>();

    /**
    * 排除路径集合
     */
    private final Set<String> excludePaths = new HashSet<>();

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        String header = config.getInitParameter("auth.header");
        if (header != null && !header.isEmpty()) {
            this.authHeader = header;
        }
        String tokens = config.getInitParameter("auth.tokens");
        if (tokens != null) {
            for (String token : tokens.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    validTokens.add(trimmed);
                }
            }
        }
        String paths = config.getInitParameter("auth.excludePaths");
        if (paths != null) {
            for (String path : paths.split(",")) {
                String trimmed = path.trim();
                if (!trimmed.isEmpty()) {
                    excludePaths.add(trimmed);
                }
            }
        }
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        if (isExcluded(request.getPath())) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getHeader(authHeader);
        if (token == null || token.isEmpty()) {
            response.end(401, "{\"error\":\"Unauthorized\",\"message\":\"缺少认证凭证\"}");
            return;
        }
        if (!validTokens.isEmpty() && !validTokens.contains(token)) {
            response.end(403, "{\"error\":\"Forbidden\",\"message\":\"无效的认证凭证\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 10;
    }

    @Override
    /** 获取过滤标识 */
    public String getFilterId() {
        return "AuthServerFilter";
    }

    /**
    * 是否Excluded
    *
    * @param path 路径
    * @return 是否excluded的结果
     */
    private boolean isExcluded(String path) {
        if (path == null) {
            return false;
        }
        for (String exclude : excludePaths) {
            if (path.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }
}