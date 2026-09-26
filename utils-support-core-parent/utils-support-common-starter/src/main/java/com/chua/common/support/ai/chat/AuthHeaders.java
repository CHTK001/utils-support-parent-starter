package com.chua.common.support.ai.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证头解析工具
 *
 * <p>统一解析「API Key 应该以哪个 HTTP 头、带什么前缀发出去」。
 * 背景：部分服务商的接口不使用标准的 {@code Authorization: Bearer <key>}，
 * 例如 Dots Studio 只认 {@code api-key: <key>}，某些网关要求
 * {@code X-Api-Key: Token <key>}。本类把这类差异收敛到一处，
 * 供所有 {@link ChatClient} 实现类复用。</p>
 *
 * <p>解析规则（按优先级）：</p>
 * <ol>
 *   <li>{@code authScheme == "none"} —— 不发送任何认证头</li>
 *   <li>{@code authHeader} 非空 —— 发送 {@code <authHeader>: <authPrefix><key>}</li>
 *   <li>否则 —— 发送 {@code Authorization: Bearer <key>}（标准 OpenAI 兼容）</li>
 * </ol>
 *
 * <p>{@code authScheme} 只取 {@code api_key / bearer / oauth / none} 四值以保持
 * 向后兼容；头名与前缀的差异通过 {@code extra.auth_header} / {@code extra.auth_prefix}
 * 承载，因此不需要为每个服务商新增枚举。</p>
 *
 * @author CH
 * @since 2026/09/23
 */
public final class AuthHeaders {

    /**
     * 标准认证头名称
     */
    public static final String DEFAULT_HEADER = "Authorization";

    /**
     * 标准认证值前缀（含尾随空格，直接与 key 拼接）
     */
    public static final String DEFAULT_PREFIX = "Bearer ";

    /**
     * 免鉴权方案
     */
    public static final String SCHEME_NONE = "none";

    /**
     * 占位 Key
     *
     * <p>部分 SDK（如 openai-java）在 build 阶段强制要求至少提供一个凭据来源，
     * 即便本次请求并不需要认证头。此时用它占位，随后在出站前把认证头摘除。</p>
     */
    public static final String PLACEHOLDER_KEY = "unused";

    private AuthHeaders() {
    }

    /**
     * 是否为免鉴权方案
     *
     * @param authScheme 认证方案，可为 null
     * @return true 表示不发送认证头
     */
    public static boolean isNone(String authScheme) {
        return authScheme != null && SCHEME_NONE.equalsIgnoreCase(authScheme.trim());
    }

    /**
     * 归一化认证头名称
     *
     * @param authHeader 自定义头名称，可为空
     * @return 非空时返回原值，否则返回 {@link #DEFAULT_HEADER}
     */
    public static String headerName(String authHeader) {
        return (authHeader == null || authHeader.trim().isEmpty()) ? DEFAULT_HEADER : authHeader.trim();
    }


    public static String headerPrefix(String authPrefix) {
        return (authPrefix == null) ? DEFAULT_PREFIX : authPrefix;
    }

    /**
     * 归一化认证值前缀（感知头名）。
     *
     * <p>显式配置了 authPrefix 时原样使用；未配置时：仅标准 {@code Authorization}
     * 头默认带 {@code Bearer }，自定义头（如 {@code api-key}、{@code X-Api-Key}）
     * 默认<b>无前缀</b>，直接发 key——否则 Dots Studio 会因收到
     * {@code api-key: Bearer ak_xxx} 判定为非法 key 而 403。</p>
     *
     * @param authHeader 头名称（归一化前），可为空
     * @param authPrefix 自定义前缀，可为空
     * @return 最终值前缀
     */
    public static String headerPrefix(String authHeader, String authPrefix) {
        if (authPrefix != null) {
            return authPrefix;
        }
        return DEFAULT_HEADER.equalsIgnoreCase(headerName(authHeader)) ? DEFAULT_PREFIX : "";
    }

    /**
     * 拼接认证头值
     *
     * @param apiKey     API Key
     * @param authPrefix 值前缀，null 表示用默认 {@code "Bearer "}
     * @return 最终头值
     */
    public static String headerValue(String apiKey, String authPrefix) {
        return headerPrefix(authPrefix) + (apiKey == null ? "" : apiKey);
    }

    /**
     * 拼接认证头值（感知头名）
     *
     * @param apiKey     API Key
     * @param authHeader 头名称，可为空
     * @param authPrefix 值前缀，null 时按头名决定默认前缀
     * @return 最终头值
     */
    public static String headerValue(String apiKey, String authHeader, String authPrefix) {
        return headerPrefix(authHeader, authPrefix) + (apiKey == null ? "" : apiKey);
    }

    /**
     * 解析认证头
     *
     * @param authScheme 认证方案，取值 api_key / bearer / oauth / none
     * @param authHeader 自定义头名称，可为空
     * @param authPrefix 自定义值前缀，可为空
     * @param apiKey     API Key
     * @return 认证头键值对；免鉴权或无 Key 时返回 null
     */
    public static Map.Entry<String, String> resolve(String authScheme, String authHeader, String authPrefix, String apiKey) {
        if (isNone(authScheme)) {
            return null;
        }
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        return Map.entry(headerName(authHeader), headerValue(apiKey, authHeader, authPrefix));
    }

    /**
     * 从客户端配置解析认证头
     *
     * @param setting 客户端配置，可为 null
     * @param apiKey  API Key
     * @return 认证头键值对；免鉴权或无 Key 时返回 null
     */
    public static Map.Entry<String, String> resolve(ChatClientSetting setting, String apiKey) {
        if (setting == null) {
            return resolve(null, null, null, apiKey);
        }
        return resolve(setting.getAuthScheme(), setting.getAuthHeader(), setting.getAuthPrefix(), apiKey);
    }

    /**
     * 是否为标准 {@code Authorization: Bearer} 认证
     *
     * <p>为 true 时可交给 SDK 原生 apiKey 通道，无需额外干预。</p>
     *
     * @param auth 认证头键值对，可为 null
     * @return true 表示标准认证
     */
    public static boolean isDefault(Map.Entry<String, String> auth) {
        return auth != null
                && DEFAULT_HEADER.equalsIgnoreCase(auth.getKey())
                && auth.getValue() != null
                && auth.getValue().startsWith(DEFAULT_PREFIX);
    }

    /**
     * 把认证头写入请求头容器
     *
     * <p>会先移除同名的既有值，并在认证头不是 {@code Authorization} 时
     * 移除残留的 {@code Authorization}，避免出现双认证头。</p>
     *
     * @param headers    目标请求头容器，需支持 put / remove
     * @param authScheme 认证方案
     * @param authHeader 自定义头名称
     * @param authPrefix 自定义值前缀
     * @param apiKey     API Key
     */
    public static void apply(Map<String, String> headers,
                             String authScheme, String authHeader, String authPrefix, String apiKey) {
        if (headers == null) {
            return;
        }
        Map.Entry<String, String> auth = resolve(authScheme, authHeader, authPrefix, apiKey);
        headers.remove(DEFAULT_HEADER);
        if (auth == null) {
            return;
        }
        if (!DEFAULT_HEADER.equalsIgnoreCase(auth.getKey())) {
            // 头名大小写不敏感，先清掉其它大小写的同名残留
            headers.keySet().removeIf(k -> k != null && k.equalsIgnoreCase(auth.getKey()));
        }
        headers.put(auth.getKey(), auth.getValue());
    }

    /**
     * 构建一个已应用认证头的可变请求头容器
     *
     * @param authScheme 认证方案
     * @param authHeader 自定义头名称
     * @param authPrefix 自定义值前缀
     * @param apiKey     API Key
     * @return 请求头容器（永不为 null，可能为空）
     */
    public static Map<String, String> newHeaders(String authScheme, String authHeader, String authPrefix, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        apply(headers, authScheme, authHeader, authPrefix, apiKey);
        return headers;
    }
}
