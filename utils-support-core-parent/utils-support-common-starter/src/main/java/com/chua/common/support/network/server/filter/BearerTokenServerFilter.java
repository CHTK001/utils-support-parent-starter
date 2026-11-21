// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.utils.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer Token 认证过滤器。
 *
 * <p>校验 {@code Authorization: Bearer <token>} 头。
 * 参数经 {@link #init(ServerFilterConfig)} 注入：{@code tokens}（允许的 token 列表）、{@code realm}。</p>
 *
 * <p>{@code tokens} 未配置时放行（避免误启用导致全量 401）；
 * 校验失败返回 401 + {@code WWW-Authenticate: Bearer realm="..."}。
 * token 比较使用常量时间算法，避免时序侧信道。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi("bearer-token")
@SpiDescribe(value = "Bearer Token 认证过滤器",
        desc = "校验 Authorization: Bearer 头；tokens 未配置时不拦截，失败返回 401 + WWW-Authenticate",
        optional = {
                @SpiParam(value = "tokens", defaultValue = "", desc = "允许的 token，逗号或空格分隔", type = "string"),
                @SpiParam(value = "realm", defaultValue = "restricted", desc = "WWW-Authenticate realm", type = "string")
        })
public class BearerTokenServerFilter implements ServerFilter {

    /**
     * 允许的 token 集合；为空表示不启用拦截
     */
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    /**
     * WWW-Authenticate realm
     */
    private String realm = "restricted";

    /**
     * 无参构造：SPI 反射装配走这里。
     */
    public BearerTokenServerFilter() {
    }

    /**
     * 创建 Bearer Token 认证过滤器。
     *
     * @param tokens 允许的 token 列表
     */
    public BearerTokenServerFilter(String... tokens) {
        addTokens(tokens);
    }

    /**
     * 按配置初始化：{@code tokens} / {@code realm}。
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        String value = config.getInitParameter("tokens");
        if (StringUtils.isNotBlank(value)) {
            tokens.clear();
            addTokens(value.split("[,;\\s]+"));
        }
        String r = config.getInitParameter("realm");
        if (StringUtils.isNotBlank(r)) {
            this.realm = r.trim();
        }
    }

    /**
     * 添加允许的 token。
     *
     * @param values token 列表，空白项忽略
     */
    public void addTokens(String... values) {
        if (values == null) {
            return;
        }
        for (String raw : values) {
            String token = raw == null ? "" : raw.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
    }

    /**
     * 获取Order
     *
     * <p>排在 IP 黑白名单（MIN+40）之后、默认 100 序过滤器之前。</p>
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 50;
    }

    /**
     * SupportProtocols
     *
     * <p>Authorization 头只存在于 HTTP 家族协议（含 WebSocket 升级请求）。</p>
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.WS};
    }

    /**
     * Do过滤
     *
     * @param request  request
     * @param response response
     * @param chain    chain
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        if (tokens.isEmpty()) {
            // 未配置 token 时不拦截，避免误启用导致全量 401
            chain.doFilter(request, response);
            return;
        }
        String token = parse(request.getHeader("Authorization"));
        if (token != null && matches(token)) {
            chain.doFilter(request, response);
            return;
        }
        deny(response);
    }

    /**
     * 从 Authorization 头解析 Bearer token。
     *
     * @param authorization Authorization 头原值，可能为 null
     * @return token，不是 Bearer 方案或为空返回 null
     */
    private String parse(String authorization) {
        if (StringUtils.isEmpty(authorization) || authorization.length() <= 7) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 常量时间比对 token 是否在允许集合内。
     *
     * @param token 请求呈现的 token
     * @return true 表示命中
     */
    private boolean matches(String token) {
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        for (String expected : tokens) {
            if (MessageDigest.isEqual(presented, expected.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deny：401 + WWW-Authenticate。
     *
     * @param response 响应，不允许为 null
     */
    private void deny(ServerResponse response) {
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"" + realm + "\"");
        response.setBody("{\"error\":\"Unauthorized\",\"scheme\":\"bearer\"}");
        response.end();
    }
}
