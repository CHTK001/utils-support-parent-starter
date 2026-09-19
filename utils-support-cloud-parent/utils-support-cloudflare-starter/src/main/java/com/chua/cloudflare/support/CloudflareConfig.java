package com.chua.cloudflare.support;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * Cloudflare 客户端配置。
 *
 * <p>用于构造 {@link CloudflareClient}。包含 API 认证、账户/数据库标识等核心参数。
 * 推荐通过 Spring Boot 配置绑定（{@code @ConfigurationProperties}）注入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Accessors(chain = true)
public class CloudflareConfig implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
     * API 令牌 或 账户 API 键。访问 D1 推荐使用 API 令牌。
     */
    private String token;

    /**
     * 账户 标识（账户 标识），D1 REST 接口路径需要。
     */
    private String accountId;

    /**
     * 默认数据库 标识（D1 database_标识）。
     */
    private String databaseId;

    /**
     * API 基础地址。Cloudflare 固定为 {@code https://api.cloudflare.com/client/v4}。
     */
    private String baseUrl = "https://api.cloudflare.com/client/v4";

    /**
     * 单次请求超时（毫秒）。
     */
    private long timeoutMs = 30_000L;
}
