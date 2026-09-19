package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.CompletionStage;

/**
 * 安全响应头过滤器。
 *
 * <p>为所有响应统一附加浏览器安全头,防 MIME 嗅探、点击劫持与
 * Referrer 泄漏;HSTS 头仅对 TLS 部署有意义,通过构造参数控制是否附加。</p>
 *
 * <p>同时实现同步({@link ServerFilter})与响应式({@link ReactiveServerFilter})
 * 两种链接口:阻塞传输走同步链,NIO/AIO 响应式传输走响应式链。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
public class SecurityHeadersServerFilter implements ServerFilter, ReactiveServerFilter {

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
    /**
    * SupportPath:Access Filter,每次请求都触发(显式覆写消除双接口默认方法冲突)
    */
    public String supportPath() {
        return null;
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
        applyHeaders(response);
        chain.doFilter(request, response);
    }

    @Override
    /**
     * 响应式Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        // 先置响应头再放行:后续 handler 仍可覆盖同名头
        applyHeaders(response);
        return chain.doFilter(request, response);
    }

    /**
     * 向响应附加安全头集合。
     *
     * @param response 响应对象
     */
    private void applyHeaders(ServerResponse response) {
        response.setHeader(HEADER_NOSNIFF, VALUE_NOSNIFF);
        response.setHeader(HEADER_FRAME_OPTIONS, VALUE_FRAME_DENY);
        response.setHeader(HEADER_REFERRER_POLICY, VALUE_REFERRER);
        if (hstsEnabled) {
            response.setHeader(HEADER_HSTS, VALUE_HSTS);
        }
    }
}
