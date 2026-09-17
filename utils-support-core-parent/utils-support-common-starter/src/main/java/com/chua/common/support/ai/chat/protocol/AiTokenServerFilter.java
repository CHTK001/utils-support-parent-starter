package com.chua.common.support.ai.chat.protocol;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
* AI 令牌认证过滤器 — 校验请求中的 Bearer Token。
*
* <p>从 {@code Authorization: Bearer xxx} 头中提取 token，
* 通过 {@link AiTokenProvider} 校验其有效性。
* 校验通过后将 token 分组设置到请求属性，供下游 {@link AiProtocolServerFilter} 做模型分组路由。
* </p>
*
* <p>token 数据来源：由 {@link AiTokenProvider} 提供。
* 通常从 {@link com.chua.common.support.ai.chat.aggregate.AggregateChatClient#getTokenProvider()} 获取。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class AiTokenServerFilter implements ServerFilter {

    /** 请求属性名：token 分组 */
    public static final String ATTR_TOKEN_GROUP = "_ai_token_group";
    /** 请求属性名：token 值 */
    public static final String ATTR_TOKEN_VALUE = "_ai_token_value";
    /** 请求属性名：token 对象 */
    public static final String ATTR_TOKEN = "_ai_token";

    /** 令牌提供者 */
    private final AiTokenProvider tokenProvider;

    /** 未认证时的错误消息 */
    @Setter
    /** Unauthorized消息 */
    private String unauthorizedMessage = "Invalid or expired token";

    /** 是否启用 */
    @Setter
    /**
    * 是否启用
    */
    private boolean enabled = true;

    /**
    * 创建 AiTokenServerFilter 实例
    * @param tokenProvider tokenProvider
    */
    public AiTokenServerFilter(AiTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        // 未启用或无 token → 直接放行
        if (!enabled || tokenProvider == null || tokenProvider.count() == 0) {
            chain.doFilter(request, response);
            return;
        }

        // 提取 Authorization header
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            sendUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String tokenValue = auth.substring(7).trim();
        if (tokenValue.isBlank()) {
            sendUnauthorized(response, "Empty token");
            return;
        }

        // 通过 AiTokenProvider 校验
        AiToken token = tokenProvider.getValidToken(tokenValue);
        if (token == null) {
            sendUnauthorized(response, unauthorizedMessage);
            return;
        }

        // 将 token 信息设置到请求属性，供下游使用
        request.setAttribute(ATTR_TOKEN, token);
        request.setAttribute(ATTR_TOKEN_VALUE, tokenValue);
        request.setAttribute(ATTR_TOKEN_GROUP, token.getGroup());

        log.debug("[AiTokenServerFilter] token 校验通过: group={}", token.getGroup());
        chain.doFilter(request, response);
    }

    /** 发送Unauthorized */
    private void sendUnauthorized(ServerResponse response, String message) {
        response.setStatus(401);
        response.setContentType("application/json; charset=utf-8");
        response.setBody("{\"error\":{\"message\":\"" + message + "\",\"type\":\"authentication_error\"}}");
        response.end();
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 50;
    }

    @Override
    /** 获取过滤Id */
    public String getFilterId() {
        return "AiTokenServerFilter";
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }
}
