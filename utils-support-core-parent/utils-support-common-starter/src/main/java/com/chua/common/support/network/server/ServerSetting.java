package com.chua.common.support.network.server;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务器配置，协议无关。
 *
 * <p>包含网络绑定、线程池、超时、并发、连接、协议帧大小、Gzip 压缩、SSL/TLS、HTTP 等配置项。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerSetting {

    /**
     * 创建一份独立的默认服务器配置。
     *
     * @return 新的默认配置实例
     */
    public static ServerSetting defaults() {
        return new ServerSetting();
    }

    /**
     * 主机名
     */
    @Builder.Default
    private String host = "0.0.0.0";

    /**
     * 端口号
     */
    @Builder.Default
    private int port = 8080;

    /**
     * 协议类型名称
     */
    @Builder.Default
    private String protocol = "http";

    /**
     * 上下文路径
     */
    @Builder.Default
    private String contextPath = "/";

    /**
     * Boss 线程数
     */
    @Builder.Default
    private int bossThreads = 1;

    /**
     * Worker 线程数
     */
    @Builder.Default
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * 等待队列长度
     */
    @Builder.Default
    private int backlog = 128;

    /**
     * 最大请求体/消息大小（字节）
     */
    @Builder.Default
    private long maxRequestSize = 10 * 1024 * 1024;

    /**
     * 最大连接数
     */
    @Builder.Default
    private int maxConnections = 10000;

    /**
     * 字符集
     */
    @Builder.Default
    private String charset = "UTF-8";

    /**
     * 读取超时时间（毫秒）
     */
    @Builder.Default
    private int readTimeout = 30000;

    /**
     * 写超时时间（毫秒）
     */
    @Builder.Default
    private int writeTimeout = 30000;

    /**
     * 最大并发请求数，0 表示不限制
     */
    @Builder.Default
    private int maxConcurrency = 0;

    /**
     * 响应超时时间（毫秒）
     */
    @Builder.Default
    private long responseTimeout = 60000;

    /**
     * 是否启用 Reactor 处理模式
     */
    @Builder.Default
    private boolean reactor = false;

    /**
     * 最大 Keep-Alive 请求数
     */
    @Builder.Default
    private int maxKeepAliveRequests = 100;

    /**
     * 优雅关闭等待时间（秒）
     */
    @Builder.Default
    private int shutdownQuietPeriod = 30;

    /**
     * TCP_NODELAY
     */
    @Builder.Default
    private boolean tcpNoDelay = true;

    /**
     * SO_REUSEADDR
     */
    @Builder.Default
    private boolean soReuseAddr = true;

    /**
     * 默认 Content-Type
     */
    @Builder.Default
    private String contentType = "text/html; charset=utf-8";

    /**
     * 缓冲区大小（字节）
     */
    @Builder.Default
    private int bufferSize = 8192;

    /**
     * WebSocket/消息协议最大帧大小（字节）
     */
    @Builder.Default
    private int maxFrameSize = 65536;

    /**
     * 是否启用 Gzip 压缩
     */
    @Builder.Default
    private boolean gzipEnabled = false;

    /**
     * Gzip 压缩等级（1-9）
     */
    @Builder.Default
    private int gzipLevel = 6;

    /**
     * Gzip 最小压缩大小（字节），小于此值不压缩
     */
    @Builder.Default
    private int gzipMinSize = 1024;

    /**
     * CORS 配置
     */
    @Builder.Default
    private CorsConfig cors = new CorsConfig();

    /**
     * SSL/TLS 配置
     */
    @Builder.Default
    private SslConfig ssl = new SslConfig();

    /**
     * HTTP 协议专用配置
     */
    @Builder.Default
    private HttpConfig http = new HttpConfig();

    /**
     * CORS 跨域配置。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CorsConfig {

        /**
         * CORS 开关
         */
        @Builder.Default
        private boolean allowOrigin = false;

        /**
         * 允许的源
         */
        @Builder.Default
        private String allowedOrigins = "*";

        /**
         * 允许的方法
         */
        @Builder.Default
        private String allowedMethods = "GET,POST,PUT,DELETE,PATCH,OPTIONS";

        /**
         * 允许的请求头
         */
        @Builder.Default
        private String allowedHeaders = "Content-Type,Authorization";
    }

    /**
     * SSL/TLS 配置。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SslConfig {

        /**
         * 是否启用
         */
        @Builder.Default
        private boolean enabled = false;

        /**
         * SSL KeyStore 文件路径（JKS/PKCS12）
         */
        private String keyStorePath;

        /**
         * SSL KeyStore 密码
         */
        private String keyStorePassword;

        /**
         * SSL 证书文件路径（PEM 格式）
         */
        private String certPath;

        /**
         * SSL 私钥文件路径（PEM 格式）
         */
        private String keyPath;

        /**
         * SSL 私钥密码
         */
        private String keyPassword;

        /**
         * 是否信任所有证书（开发环境）
         */
        @Builder.Default
        private boolean trustAll = false;
    }

    /**
     * HTTP 协议专用配置。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HttpConfig {

        /**
         * 是否启用 WebSocket 升级
         */
        @Builder.Default
        private boolean websocketEnabled = false;
    }
}