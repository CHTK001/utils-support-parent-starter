package com.chua.common.support.ai.chat.route;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由目标：一次请求要打到的上游与凭据。
 *
 * <p>由 {@link RouterChatClient#route(String)} 按模型解析出来，交给实现类拼装上游请求，
 * 或经 {@link #toSetting(ChatClientSetting)} 转成配置后直接创建上游 {@link ChatClient}。</p>
 *
 * <p>字段均可为空，表示「沿用客户端设置」：</p>
 * <ul>
 *   <li>{@code provider} 为空时按 OpenAI 兼容协议（{@link ChatClient#OPENAI_EXTENSION}）处理；</li>
 *   <li>{@code model} 为空时沿用请求里的模型标识；</li>
 *   <li>{@code authScheme} / {@code authHeader} / {@code authPrefix} 为空时沿用客户端设置；</li>
 *   <li>{@code apiKey} 为空串表示对端免鉴权，{@code null} 表示沿用客户端设置。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RouteTarget {

    /**
     * 上游 provider 标识（ChatClient SPI 扩展名），为空时按 OpenAI 兼容协议处理
     */
    private String provider;

    /**
     * 上游基础地址，如 {@code https://api.kilo.ai/api/gateway}
     */
    private String baseUrl;

    /**
     * 上游 API Key；空串表示免鉴权，{@code null} 表示沿用客户端设置
     */
    private String apiKey;

    /**
     * 多账号 Key 列表（可选）。
     *
     * <p>非空时本路由持多个可用账号，客户端按 {@link #loadStrategy} 组
     * failover / round_robin 聚合：触发上游限流（429）时自动切到下一个账号。
     * 与 {@link #apiKey} 并存时以 {@code apiKeys} 为准（后者是集合形态）。</p>
     */
    private List<String> apiKeys;

    /**
     * 是否在触发限流（429）时自动切换账号；默认 {@code true}，仅在 {@code apiKeys} 多账号时生效。
     */
    private Boolean autoSwitchOnRateLimit;

    /**
     * 多账号负载策略：{@code failover}（按序取第一个可用）/ {@code round_robin}（轮询）；默认 failover。
     */
    private String loadStrategy;

    /**
     * 对端模型标识；为空时沿用请求里的模型
     */
    private String model;

    /**
     * 认证方案：{@code api_key} / {@code bearer} / {@code oauth} / {@code none}；
     * 为空时沿用客户端设置
     */
    private String authScheme;

    /**
     * 认证头名称，如 Dots Studio 的 {@code api-key}；为空时使用标准 {@code Authorization}
     */
    private String authHeader;

    /**
     * 认证值前缀，如 {@code "Bearer "} 或空串；{@code null} 表示沿用客户端设置
     */
    private String authPrefix;

    /**
     * 额外请求头，与客户端级请求头合并（同名时以本目标为准）
     */
    private Map<String, String> headers;

    /**
     * 创建路由目标（只给上游与凭据）
     *
     * @param provider 上游 provider 标识
     * @param baseUrl  上游基础地址
     * @param apiKey   上游 API Key
     * @return 路由目标
     */
    public static RouteTarget of(String provider, String baseUrl, String apiKey) {
        return of(provider, baseUrl, apiKey, null);
    }

    /**
     * 创建路由目标（带对端模型）
     *
     * @param provider 上游 provider 标识
     * @param baseUrl  上游基础地址
     * @param apiKey   上游 API Key
     * @param model    对端模型标识
     * @return 路由目标
     */
    public static RouteTarget of(String provider, String baseUrl, String apiKey, String model) {
        return RouteTarget.builder()
                .provider(provider)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .build();
    }

    /**
     * 目标是否可用
     *
     * <p>只有给出 {@code baseUrl} 才认为可以发请求，避免沿用缺省端点把请求打到错误的上游。</p>
     *
     * @return 有上游地址时返回 {@code true}
     */
    public boolean available() {
        return StringUtils.isNotBlank(baseUrl);
    }

    /**
     * 复制并替换对端模型标识
     *
     * @param model 对端模型标识
     * @return 新的路由目标
     */
    public RouteTarget withModel(String model) {
        return toBuilder().model(model).build();
    }

    /**
     * 复制并替换上游 API Key
     *
     * @param apiKey 上游 API Key
     * @return 新的路由目标
     */
    public RouteTarget withApiKey(String apiKey) {
        return toBuilder().apiKey(apiKey).build();
    }

    /**
     * 复制并替换额外请求头
     *
     * @param headers 额外请求头
     * @return 新的路由目标
     */
    public RouteTarget withHeaders(Map<String, String> headers) {
        return toBuilder().headers(headers).build();
    }

    /**
     * 本路由的账号 Key 集合（{@code apiKeys} 优先，否则退回单个 {@code apiKey}）。
     *
     * @return 账号 Key 列表；无任何 key 时为空列表
     */
    public List<String> accountKeys() {
        if (apiKeys != null && !apiKeys.isEmpty()) {
            List<String> cleaned = new java.util.ArrayList<>(apiKeys.size());
            for (String key : apiKeys) {
                if (key != null) {
                    cleaned.add(key);
                }
            }
            return cleaned;
        }
        return apiKey == null ? List.of() : List.of(apiKey);
    }

    /**
     * 是否启用限流自动切换（缺省 {@code true}，仅在多账号时生效）。
     *
     * @return 是否自动切换
     */
    public boolean autoSwitchEnabled() {
        return autoSwitchOnRateLimit == null || Boolean.TRUE.equals(autoSwitchOnRateLimit);
    }

    /**
     * 多账号负载策略（缺省 failover）。
     *
     * @return 策略名
     */
    public String resolvedStrategy() {
        return StringUtils.isBlank(loadStrategy) ? "failover" : loadStrategy.trim();
    }

    /**
     * 转成上游客户端配置（不带底稿）
     *
     * @return 客户端配置
     * @see #toSetting(ChatClientSetting)
     */
    public ChatClientSetting toSetting() {
        return toSetting(null);
    }

    /**
     * 转成上游客户端配置
     *
     * <p>以 {@code base} 为底稿复制公共参数（温度、工具、超时等），再用本目标的
     * provider、baseUrl、apiKey、model 与认证字段覆盖。未给出 provider 时按 OpenAI 兼容
     * 协议处理；额外请求头与底稿合并，同名时以本目标为准。</p>
     *
     * @param base 底稿设置，可为空
     * @return 客户端配置
     */
    public ChatClientSetting toSetting(ChatClientSetting base) {
        ChatClientSetting setting = ChatClientSetting.builder().build();
        if (base != null) {
            BeanUtils.copyProperties(base, setting);
        }
        String effectiveProvider = StringUtils.isBlank(provider)
                ? ChatClient.OPENAI_EXTENSION : provider;
        setting.setProvider(effectiveProvider);
        if (StringUtils.isNotBlank(baseUrl)) {
            setting.setBaseUrl(baseUrl);
        }
        if (apiKey != null) {
            setting.setAppKey(apiKey);
        }
        if (StringUtils.isNotBlank(model)) {
            setting.setModel(model);
        }
        if (StringUtils.isNotBlank(authScheme)) {
            setting.setAuthScheme(authScheme);
        }
        if (StringUtils.isNotBlank(authHeader)) {
            setting.setAuthHeader(authHeader);
        }
        if (authPrefix != null) {
            setting.setAuthPrefix(authPrefix);
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (base != null && base.getExtraHeaders() != null) {
            merged.putAll(base.getExtraHeaders());
        }
        if (headers != null) {
            merged.putAll(headers);
        }
        if (!merged.isEmpty()) {
            setting.setExtraHeaders(merged);
        }
        return setting;
    }
}