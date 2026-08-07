package com.chua.spider.support.model;

import lombok.Builder;
import lombok.Data;

/**
 * 代理配置。
 *
 * <p>携带代理主机/端口/协议/凭据，用于 HttpFetcher 通过代理转发请求。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder(toBuilder = true)
public class SpiderProxyConfig {

    /**
     * 代理主机（IP 或域名）。
     */
    private String proxyHost;

    /**
     * 代理端口。
     */
    private Integer proxyPort;

    /**
     * 代理协议。
     *
     * <p>HTTP / HTTPS / SOCKS5，默认 HTTP。</p>
     */
    @Builder.Default
    private String proxyProtocol = "HTTP";

    /**
     * 代理认证用户名（可选）。
     */
    private String proxyUsername;

    /**
     * 代理认证密码（可选）。
     */
    private String proxyPassword;
}