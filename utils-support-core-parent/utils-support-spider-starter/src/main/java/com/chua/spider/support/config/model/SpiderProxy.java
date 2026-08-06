package com.chua.spider.support.config.model;

import lombok.Data;

/**
 * 单个代理节点。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class SpiderProxy {

    /**
     * 代理主机（IP 或域名）
     */
    private String proxyHost;

    /**
     * 代理端口
     */
    private Integer proxyPort;

    /**
     * 代理协议（HTTP / HTTPS / SOCKS5）
     */
    private String proxyProtocol;

    /**
     * 用户名（可选）
     */
    private String proxyUsername;

    /**
     * 密码（可选）
     */
    private String proxyPassword;
}