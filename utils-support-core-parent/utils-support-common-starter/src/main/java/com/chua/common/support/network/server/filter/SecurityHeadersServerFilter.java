package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

/**
 * 安全响应头过滤器。
 *
 * <p>为所有响应统一附加浏览器安全头,防 MIME 嗅探、点击劫持与
 * Referrer 泄漏;HSTS 头仅对 TLS 部署有意义,通过构造参数控制是否附加。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
public class SecurityHeadersServerFilter implements ServerFilter {

    /**
     * 防 MIME 嗅探
     */
    private static final String HEADER_NOSNIFF = "X-Content-Type-Options";

    /**
     * 防 MIME 嗅探取值
     */
    private static final String VALUE_NOSNIFF = "nosniff";

    /**
     * 防点击劫持(禁止 iframe 嵌入)
     */
    private static final String HEADER_FRAME_OPTIONS = "X-Frame-Options";

    /**
     * 禁止 iframe 嵌入取值
     */
    private static final String VALUE_FRAME_DENY = "DENY";

    /**
     * Referrer 泄漏控制
     */
    private static final String HEADER_REFERRER_POLICY = "Referrer-Policy";

    /**
     * 跨源仅发送 origin,降源不发送
     */
    private static final String VALUE_REFERRER = "strict-origin-when-cross-origin";

    /**
     * HSTS 头(强制 HTTPS)
     */
    private static final String HEADER_HSTS = "Strict-Transport-Security";

    /**
     * HSTS 一年 + 包含子域
     */
    private static final String VALUE_HSTS = "max-age=31536000; includeSubDomains";

    /** 是否附加 HSTS 头(TLS 部署时开启) */
    private final boolean hstsEnabled;

    /**
     * 创建安全响应头过滤器(默认不启用 HSTS)。
     */
    public SecurityHeadersServerFilter() {
        this.hstsEnabled = false;
    }

    /**
     * 创建安全响应头过滤器。
     *
     * @param hstsEnabled true 表示附加 Strict-Transport-Security 头(TLS 部署)
     */
    public SecurityHeadersServerFilter(boolean hstsEnabled) {
        this.hstsEnabled = hstsEnabled;
    }

    @Override
    /** 获取Order:早于业务链执行,保证所有响应携带安全头 */
    public int getOrder() {
        return Integer.MIN_VALUE + 30;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    @Override
    /**
     * Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        // 先置响应头再放行:后续 handler 仍可覆盖同名头
        response.setHeader(HEADER_NOSNIFF, VALUE_NOSNIFF);
        response.setHeader(HEADER_FRAME_OPTIONS, VALUE_FRAME_DENY);
        response.setHeader(HEADER_REFERRER_POLICY, VALUE_REFERRER);
        if (hstsEnabled) {
            response.setHeader(HEADER_HSTS, VALUE_HSTS);
        }
        chain.doFilter(request, response);
    }
}
