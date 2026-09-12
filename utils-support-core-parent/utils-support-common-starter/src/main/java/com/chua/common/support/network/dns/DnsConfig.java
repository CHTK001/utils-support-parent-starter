package com.chua.common.support.network.dns;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
* DNS 服务商配置。
*
* <p>承载 DNS 服务商的认证信息与区域标识，用于构造 {@link DnsProvider} 实例。
* 不同服务商字段含义略有差异，但 token 与 zoneName 为通用核心字段。</p>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
@Data
@Builder
@Accessors(chain = true)
public class DnsConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
    * API Token / AccessKey（服务商认证凭据）
     */
    private String token;

    /**
    * 区域名称（主域名，如 example.com）
     */
    private String zoneName;

    /**
    * 区域 ID（可选，部分服务商需要显式指定）
     */
    private String zoneId;

    /**
    * API 基础地址（可选，默认使用服务商官方地址）
     */
    private String baseUrl;

    /**
    * 单次请求超时（毫秒），默认 30000
     */
    @Builder.Default
    private long timeoutMs = 30_000L;
}
