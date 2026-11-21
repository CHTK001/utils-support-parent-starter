// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * CORS 跨域过滤器。
 *
 * <p>根据 {@link ServerSetting} 中的 CORS 配置自动添加跨域响应头。
 * 仅在 {@link ServerSetting.CorsConfig#isAllowOrigin()} 为 true 时生效。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
public class CorsServerFilter implements ServerFilter {

    /**
     * setting
     */
    private volatile ServerSetting setting;

    /**
     * 已解析的 Origin 白名单缓存：以配置串为键，配置串不变时复用解析结果，
     * 避免每个请求重复 split + trim。
     */
    private volatile OriginMatcher originMatcher;

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 50;
    }

    /**
     * SupportProtocols
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    /**
     * 初始化
     */
    @Override
    public void init(ServerFilterConfig config) throws Exception {
        if (config != null) {
            this.setting = config.getServerSetting();
        } else {
            this.setting = ServerSetting.defaults();
        }
    }

    /**
     * Do过滤
     */
    @Override
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
        if (StringUtils.isEmpty(origin)) {
            chain.doFilter(request, response);
            return;
        }

        OriginMatcher matcher = originMatcherFor(cors.getAllowedOrigins());
        if (!matcher.allows(origin)) {
            chain.doFilter(request, response);
            return;
        }
        // 允许携带凭据时必须回显实际来源：规范禁止「凭据 + 通配来源」并用，
        // 同时出现会让浏览器判定非法并拒绝响应。因此配置为通配时也只回显 origin。
        boolean credentials = cors.isAllowCredentials();
        response.setHeader("Access-Control-Allow-Origin", (matcher.isWildcard() && !credentials) ? "*" : origin);
        response.setHeader("Vary", "Origin");
        if (credentials) {
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
        String allowedMethods = cors.getAllowedMethods();
        if (StringUtils.isNotEmpty(allowedMethods)) {
            response.setHeader("Access-Control-Allow-Methods", allowedMethods);
        }
        String allowedHeaders = cors.getAllowedHeaders();
        if (StringUtils.isNotEmpty(allowedHeaders)) {
            response.setHeader("Access-Control-Allow-Headers", allowedHeaders);
        }
        String exposeHeaders = cors.getExposeHeaders();
        if (StringUtils.isNotBlank(exposeHeaders)) {
            response.setHeader("Access-Control-Expose-Headers", exposeHeaders);
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
     * 获取（并按需解析）Origin 白名单。
     *
     * <p>缓存键为配置原始串；null/blank 与具体串分别缓存，键不一致即重算，
     * 语义与原「每请求解析」完全一致。</p>
     *
     * @param allowedOrigins 配置的允许来源串，可为 null
     * @return 解析后的白名单
     */
    private OriginMatcher originMatcherFor(String allowedOrigins) {
        OriginMatcher matcher = originMatcher;
        if (matcher == null || !matcher.sourceEquals(allowedOrigins)) {
            matcher = parseOrigins(allowedOrigins);
            originMatcher = matcher;
        }
        return matcher;
    }

    /**
     * 解析允许来源串为不可变白名单。
     *
     * @param allowedOrigins 配置的允许来源串，可为 null
     * @return 解析结果
     */
    private static OriginMatcher parseOrigins(String allowedOrigins) {
        if (StringUtils.isBlank(allowedOrigins)) {
            return new OriginMatcher(allowedOrigins, false, Collections.emptySet());
        }
        if ("*".equals(allowedOrigins.trim())) {
            return new OriginMatcher(allowedOrigins, true, Collections.emptySet());
        }
        Set<String> origins = new HashSet<>();
        for (String part : allowedOrigins.split(",")) {
            origins.add(part.trim());
        }
        return new OriginMatcher(allowedOrigins, false, Collections.unmodifiableSet(origins));
    }

    /**
     * Origin 白名单的不可变解析结果。
     */
    private static final class OriginMatcher {
        private final String source;
        private final boolean wildcard;
        private final Set<String> allowed;

        OriginMatcher(String source, boolean wildcard, Set<String> allowed) {
            this.source = source;
            this.wildcard = wildcard;
            this.allowed = allowed;
        }

        boolean sourceEquals(String other) {
            return source == null ? other == null : source.equals(other);
        }

        boolean isWildcard() {
            return wildcard;
        }

        /**
         * 判断请求源是否放行：通配直接放行，否则按精确匹配。
         *
         * @param origin 请求源，调用方保证非空
         * @return true 表示允许
         */
        boolean allows(String origin) {
            return wildcard || allowed.contains(origin);
        }
    }
}