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
import java.util.Base64;

/**
 * HTTP Basic 认证过滤器。
 *
 * <p>校验 {@code Authorization: Basic base64(user:pass)} 头。
 * 参数经 {@link #init(ServerFilterConfig)} 注入：{@code username}、{@code password}、{@code realm}。</p>
 *
 * <p>{@code username} 未配置时放行（避免误启用导致全量 401）；
 * 校验失败返回 401 + {@code WWW-Authenticate: Basic realm="..."}。
 * 凭据比较使用常量时间算法，避免时序侧信道。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi("basic-auth")
@SpiDescribe(value = "HTTP Basic 认证过滤器",
        desc = "校验 Authorization: Basic 头；username 未配置时不拦截，失败返回 401 + WWW-Authenticate",
        optional = {
                @SpiParam(value = "username", defaultValue = "", desc = "用户名", type = "string"),
                @SpiParam(value = "password", defaultValue = "", desc = "密码", type = "string"),
                @SpiParam(value = "realm", defaultValue = "restricted", desc = "WWW-Authenticate realm", type = "string")
        })
public class BasicAuthServerFilter implements ServerFilter {

    /**
     * 配置的用户名；为空表示不启用拦截
     */
    private String username;
    /**
     * 配置的密码
     */
    private String password;
    /**
     * WWW-Authenticate realm
     */
    private String realm = "restricted";

    /**
     * 无参构造：SPI 反射装配走这里。
     */
    public BasicAuthServerFilter() {
    }

    /**
     * 创建 Basic 认证过滤器。
     *
     * @param username 用户名
     * @param password 密码
     */
    public BasicAuthServerFilter(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * 按配置初始化：{@code username} / {@code password} / {@code realm}。
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        String user = config.getInitParameter("username");
        if (StringUtils.isNotBlank(user)) {
            this.username = user.trim();
        }
        String pass = config.getInitParameter("password");
        if (StringUtils.isNotBlank(pass)) {
            this.password = pass;
        }
        String r = config.getInitParameter("realm");
        if (StringUtils.isNotBlank(r)) {
            this.realm = r.trim();
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
        if (StringUtils.isEmpty(username)) {
            // 未配置凭据时不拦截，避免误启用导致全量 401
            chain.doFilter(request, response);
            return;
        }
        if (parseAndCheck(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        deny(response);
    }

    /**
     * 校验 Authorization 头是否为合法的 Basic 凭据。
     *
     * @param authorization Authorization 头原值，可能为 null
     * @return true 表示凭据正确
     */
    private boolean parseAndCheck(String authorization) {
        if (StringUtils.isEmpty(authorization) || authorization.length() <= 6) {
            return false;
        }
        if (!authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx < 0) {
                return false;
            }
            String user = decoded.substring(0, idx);
            String pass = decoded.substring(idx + 1);
            return equalsConstantTime(user, username)
                    && equalsConstantTime(pass, password == null ? "" : password);
        } catch (IllegalArgumentException e) {
            // Base64 非法
            return false;
        }
    }

    /**
     * 常量时间字符串比较，避免时序侧信道。
     *
     * @param a 左值
     * @param b 右值
     * @return true 表示相等
     */
    private static boolean equalsConstantTime(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Deny：401 + WWW-Authenticate。
     *
     * @param response 响应，不允许为 null
     */
    private void deny(ServerResponse response) {
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
        response.setBody("{\"error\":\"Unauthorized\",\"scheme\":\"basic\"}");
        response.end();
    }
}
