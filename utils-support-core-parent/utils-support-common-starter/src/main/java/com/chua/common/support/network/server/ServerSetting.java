package com.chua.common.support.network.server;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
     * 创建一份按当前系统自动配置最优参数的服务器配置。
     *
     * <p>基于 CPU 核数、JVM 可用堆内存与操作系统类型自动调整线程数、连接数、
     * 等待队列长度、缓冲区等关键性能参数（等效于 {@code autoConfig()}）。</p>
     *
     * @return 自动配置实例
     */
    public static ServerSetting auto() {
        return new ServerSetting().autoConfig();
    }

    /**
     * 是否启用按当前系统自动配置最优参数。
     *
     * <p>开启后，{@link AbstractServer} 启动前会基于 CPU 核数、JVM 可用堆内存与
     * 操作系统类型自动调整线程数、连接数、等待队列、缓冲区等性能参数，
     * 使服务器在各环境中都能获得较优的默认表现。默认开启，可设为 {@code false} 手动指定。</p>
     */
    @Builder.Default
    /** Auto */
    private boolean auto = true;

    /**
     * 按当前系统自动配置最优参数。
     *
     * <p>基于 CPU 核数、JVM 可用堆内存与操作系统类型自动调整线程数、连接数、
     * 等待队列长度、缓冲区等关键性能参数。返回当前实例，便于链式调用。</p>
     *
     * @return 当前配置实例
     */
    public ServerSetting autoConfig() {
        int cpus = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        String os = System.getProperty("os.name", "").toLowerCase();

        // 线程模型：Worker 随 CPU 核数伸缩，Boss 保持默认（accept 循环按核数扩展现已足够，
        // 且 KCP 等同时用 bossThreads 作为 IO event loop 数，需随核数扩展）
        this.workerThreads = Math.max(2, cpus * 2);
        if (this.bossThreads < 1) {
            this.bossThreads = 1;
        }

        // IO Selector 事件循环：Windows 实测 4 分片为最优（2 分片未吃满并行度，16+ 分片 select 负优化），
        // Linux/macOS 按核数扩展；eventLoops 与 ioThreads 保持一致
        int io = os.contains("win") ? 4 : Math.max(cpus, 2);
        this.ioThreads = io;
        this.eventLoops = io;

        // 等待队列：Windows 语义较弱适当收敛，Unix 系可放大
        this.backlog = os.contains("win")
                ? Math.min(Math.max(cpus * 64, 128), 1024)
                : Math.min(Math.max(cpus * 128, 256), 4096);

        // 最大连接数：按可用堆内存分级(目标:百万级并发连接,配合各实现的每连接懒分配内存)
        if (heapMb >= 8192) {
            this.maxConnections = 1000000;
        } else if (heapMb >= 4096) {
            this.maxConnections = 500000;
        } else if (heapMb >= 2048) {
            this.maxConnections = 200000;
        } else {
            this.maxConnections = 100000;
        }

        // 缓冲区：内存充足时放大，减少系统调用次数
        this.bufferSize = heapMb >= 4096 ? 16384 : 8192;

        // 最大帧/请求体：跟随缓冲区分级，容纳大消息往返（含长度头余量）
        this.maxFrameSize = heapMb >= 4096 ? 1024 * 1024 : 65536;

        // 最大并发请求数：与连接池容量对齐（RPC auto 连接数=cpu*8）,
        // 防止异常场景下在途请求无限堆积击穿内存,同时不限制正常高吞吐。
        // 显式配置过(>0 或 0=不限制)时保留原值,仅未配置时按核数给默认
        if (!maxConcurrencyExplicit) {
            this.maxConcurrency = Math.max(cpus * 8, 32);
        }

        // 最大 Keep-Alive 请求数：内存充足时放宽长连接复用次数，
        // 减少高吞吐场景下频繁建连/断连的握手开销
        this.maxKeepAliveRequests = heapMb >= 4096 ? 500 : 100;

        // 响应式处理模式：多核机器默认启用（由具体实现决定是否支持）
        this.reactor = cpus >= 8;

        return this;
    }

    /**
     * 主机名
     */
    @Builder.Default
    /** 主机 */
    private String host = "0.0.0.0";

    /**
     * 端口号
     */
    @Builder.Default
    /** 端口 */
    private int port = 8080;

    /**
     * 协议类型名称
     */
    @Builder.Default
    /** 协议 */
    private String protocol = "http";

    /**
     * 上下文路径
     */
    @Builder.Default
    /** 上下文路径 */
    private String contextPath = "/";

    /**
     * Boss 线程数
     */
    @Builder.Default
    /** Bossthreads */
    private int bossThreads = Math.max(1, Runtime.getRuntime().availableProcessors());

    /**
     * Worker 线程数
     */
    @Builder.Default
    /** Workerthreads */
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * IO Selector 事件循环线程数（Reactor 模式，仅支持 IO 多路复用的实现使用）。
     *
     * <p>默认 0 表示由实现自行确定；{@link #autoConfig()} 会根据 CPU 核数与平台
     * 自动生成最优值。Windows 平台实测 4 分片为最优，Linux/macOS 按核数扩展。</p>
     */
    @Builder.Default
    /** IoThreads */
    private int ioThreads = 0;

    /**
     * 等待队列长度
     */
    @Builder.Default
    /** Backlog */
    private int backlog = 128;

    /**
     * 最大请求体/消息大小（字节）
     */
    @Builder.Default
    /** 最大值请求尺寸 */
    private long maxRequestSize = 10 * 1024 * 1024;

    /**
     * 最大连接数
     */
    @Builder.Default
    /** 最大值connections */
    private int maxConnections = 10000;

    /**
     * 字符集
     */
    @Builder.Default
    /** 字符集 */
    private String charset = "UTF-8";

    /**
     * 读取超时时间（毫秒）
     */
    @Builder.Default
    /** Read超时 */
    private int readTimeout = 30000;

    /**
     * 写超时时间（毫秒）
     */
    @Builder.Default
    /** Write超时 */
    private int writeTimeout = 30000;

    /**
     * 最大并发请求数，0 表示不限制
     */
    @Builder.Default
    private int maxConcurrency = 0;

    /**
     * 是否显式设置过 maxConcurrency(autoConfig 内部标记;默认 false,由 autoConfig 设置时置默认)
     */
    @Builder.Default
    private boolean maxConcurrencyExplicit = false;

    /**
     * 设置最大并发请求数,并标记为显式配置。
     *
     * @param maxConcurrency 最大并发数,0 表示不限制
     */
    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
        this.maxConcurrencyExplicit = true;
    }

    /**
     * NIO 事件循环(Selector)数量，0 表示自动按 CPU 核数。
     *
     * <p>控制 {@code nio} 实现的事件循环线程数，影响读写 CPU 并行度与吞吐上限。
     * 自动模式：Windows 默认 2（{@code WindowsSelectorImpl} 多 Selector 并发稳定性限制），
     * Linux/macOS 默认 = CPU 核数（epoll/kqueue 可安全扩展）。</p>
     */
    @Builder.Default
    private int eventLoops = 0;

    /**
     * 是否在事件循环线程内联执行 handler（跳过虚拟线程提交与 Selector 唤醒往返）。
     *
     * <p>默认关闭：handler 提交到虚拟线程池异步执行，事件循环专注 I/O，
     * 2 核及以上场景下吞吐显著高于内联模式。
     * 仅当 handler 是微秒级纯计算（如 echo）且连接数较少时，
     * 才建议开启 {@code setting.setInlineDispatch(true)} 以省去线程调度开销。</p>
     */
    @Builder.Default
    private boolean inlineDispatch = false;

    /**
     * 响应超时时间（毫秒）
     */
    @Builder.Default
    /** 响应超时 */
    private long responseTimeout = 60000;

    /**
     * 是否启用 Reactor 处理模式
     */
    @Builder.Default
    /** Reactor */
    private boolean reactor = false;

    /**
     * 最大 Keep-Alive 请求数
     */
    @Builder.Default
    /** 最大值keepaliverequests */
    private int maxKeepAliveRequests = 100;

    /**
     * 优雅关闭等待时间（秒）
     */
    @Builder.Default
    /** Shutdownquietperiod */
    private int shutdownQuietPeriod = 30;

    /**
     * TCP_NODELAY
     */
    @Builder.Default
    /** TCPNOdelay */
    private boolean tcpNoDelay = true;

    /**
     * SO_REUSEADDR
     */
    @Builder.Default
    /** SOreuseaddr */
    private boolean soReuseAddr = true;

    /**
     * 默认 Content-Type
     */
    @Builder.Default
    /** 内容类型 */
    private String contentType = "text/html; charset=utf-8";

    /**
     * 缓冲区大小（字节）
     */
    @Builder.Default
    /** 缓冲区尺寸 */
    private int bufferSize = 8192;

    /**
     * WebSocket/消息协议最大帧大小（字节）
     */
    @Builder.Default
    /** 最大值frame尺寸 */
    private int maxFrameSize = 65536;

    /**
     * 是否启用 Gzip 压缩
     */
    @Builder.Default
    /** Gzip是否启用 */
    private boolean gzipEnabled = false;

    /**
     * Gzip 压缩等级（1-9）
     */
    @Builder.Default
    /** Gzip级别 */
    private int gzipLevel = 6;

    /**
     * Gzip 最小压缩大小（字节），小于此值不压缩
     */
    @Builder.Default
    /** Gzip最小值尺寸 */
    private int gzipMinSize = 1024;

    /**
     * CORS 配置
     */
    @Builder.Default
    /** Cors */
    private CorsConfig cors = new CorsConfig();

    /**
     * SSL/TLS 配置
     */
    @Builder.Default
    /** SSL */
    private SslConfig ssl = new SslConfig();

    /**
     * HTTP 协议专用配置
     */
    @Builder.Default
    /** HTTP */
    private HttpConfig http = new HttpConfig();

    /**
     * CORS 跨域配置。
     *
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
        /** Alloworigin */
        private boolean allowOrigin = false;

        /**
         * 允许的源
         */
        @Builder.Default
        /** Allowedorigins */
        private String allowedOrigins = "*";

        /**
         * 允许的方法
         */
        @Builder.Default
        /** Allowedmethods */
        private String allowedMethods = "GET,POST,PUT,DELETE,PATCH,OPTIONS";

        /**
         * 允许的请求头
         */
        @Builder.Default
        /** Allowedheaders */
        private String allowedHeaders = "Content-Type,Authorization";
    }

    /**
     * SSL/TLS 配置。
     *
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
        /** 是否启用 */
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
        /** TrustALL */
        private boolean trustAll = false;

        /**
         * 一键自签 — 启用后自动开启 SSL 并生成自签名证书，无需任何其他配置。
         *
         * <p>等效于同时设置 {@code enabled=true} + {@code selfSigned=true}，
         * 使用默认域名（localhost, 127.0.0.1）和默认有效期（365天）。</p>
         *
         * <p>示例：{@code .selfSignedAuto(true)} 即可启动 HTTPS。</p>
         */
        @Builder.Default
        /** Selfsignedauto */
        private boolean selfSignedAuto = false;

        /**
         * 是否启用自签名证书自动生成（开发/测试环境）
         *
         * <p>启用后，若未配置 keyStorePath 或 certPath/keyPath，
         * 将自动使用 JDK keytool 生成自签名证书。</p>
         */
        @Builder.Default
        /** Selfsigned */
        private boolean selfSigned = false;

        /**
         * 自签名证书域名列表
         *
         * <p>默认包含 localhost 和 127.0.0.1。
         * 多个域名将作为 SAN（Subject Alternative Name）扩展添加到证书中。</p>
         */
        @Builder.Default
        /** Selfsigneddomains */
        private List<String> selfSignedDomains = List.of("localhost", "127.0.0.1");

        /**
         * 自签名证书有效期（天），默认 365 天
         */
        @Builder.Default
        /** Selfsignedvalidity */
        private int selfSignedValidity = 365;

        /**
         * 自签名证书密钥算法（RSA / EC），默认 RSA
         */
        @Builder.Default
        /** Selfsigned密钥ALG */
        private String selfSignedKeyAlg = "RSA";

        /**
         * 自签名证书密钥大小，默认 2048
         */
        @Builder.Default
        /** Selfsigned密钥尺寸 */
        private int selfSignedKeySize = 2048;
    }

    /**
     * HTTP 协议专用配置。
     *
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
        /** Websocket是否启用 */
        private boolean websocketEnabled = false;
    }
}