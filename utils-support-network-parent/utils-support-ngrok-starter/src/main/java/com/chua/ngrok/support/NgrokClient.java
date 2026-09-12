package com.chua.ngrok.support;

import com.chua.common.support.reflection.ReflectUtils;
import com.ngrok.Forwarder;
import com.ngrok.HttpBuilder;
import com.ngrok.Listener;
import com.ngrok.Session;
import com.ngrok.TcpBuilder;
import com.ngrok.TlsBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
   * 链式 Ngrok 客户端，基于官方 ngrok-Java 智能体 SDK。
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (NgrokClient client = NgrokClient.create(System.getenv("NGROK_AUTHTOKEN"))
 *         .metadata("chua-app")
 *         .connect()
 *         .http()
 *         .domain("example.ngrok-free.app")
 *         .listenHttp()
 *         .tcp()
 *         .remoteAddress("1.tcp.ngrok.io:20000")
 *         .listenTcp()
 *         .block()) {
 *     // 隧道已建立，可读取 url
 *     System.out.println(client.getUrls());
 * }
 * }</pre>隧道已建立，可读取 url
 *     System.out.println(client.getUrls());
 * }
 * }</pre>
 *
 * <p>支持以转发模式将外部流量代理到内部 URL：</p>
 * <pre>{@code
 * try (NgrokClient client = NgrokClient.create(token)
 *         .connect()
 *         .http()
 *         .domain("example.ngrok-free.app")
 *         .forwardHttp(new URL("http://127.0.0.1:8080"))) {
 *     // 公网 https://example.ngrok-free.app -> 127.0.0.1:8080
 * }
 * }</pre>e.app -> 127.0.0.1:8080
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class NgrokClient implements AutoCloseable {

    /**
     * 默认心跳间隔（秒）
     */
    private static final long DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30L;

    /**
     * 默认心跳容忍（秒）
     */
    private static final long DEFAULT_HEARTBEAT_TOLERANCE_SECONDS = 60L;

    /**
     * ngrok authtoken
     */
    private final String authtoken;

    /**
     * 会话元数据
     */
    private String metadata;

    /**
     * 自定义 ngrok 服务端地址（私有部署时使用）
     */
    private String serverAddr;

    /**
     * CA 证书字节（私有部署时使用）
     */
    private byte[] caCert;

    /**
     * 心跳间隔
     */
    private Duration heartbeatInterval = Duration.ofSeconds(DEFAULT_HEARTBEAT_INTERVAL_SECONDS);

    /**
     * 心跳容忍
     */
    private Duration heartbeatTolerance = Duration.ofSeconds(DEFAULT_HEARTBEAT_TOLERANCE_SECONDS);

    /**
      * 已连接的 ngrok 会话
     */
    private Session session;

    /**
     * 启动过程中的监听器
     */
    private final List<Listener> listeners = new ArrayList<>();

    /**
     * 启动过程中的转发器
     */
    private final List<Forwarder> forwarders = new ArrayList<>();

    /**
      * 创建 ngrok客户端 实例
     * @param authtoken authtoken
     */
    private NgrokClient(String authtoken) {
        this.authtoken = authtoken;
    }

    /**
      * 创建一个 ngrok客户端 构造器。
     *
     * @param authtoken ngrok authtoken（可为空，为空时尝试读取 NGROK_AUTHTOKEN 环境变量）
     * @return NgrokClient 实例
     */
    public static NgrokClient create(String authtoken) {
        return new NgrokClient(authtoken);
    }

    /**
      * 设置 会话 元数据。
     *
     * @param metadata 元数据
     * @return 当前 ngrok客户端
     */
    public NgrokClient metadata(String metadata) {
        this.metadata = metadata;
        return this;
    }

    /**
     * 设置自定义 ngrok 服务端地址（私有部署）。
     *
     * @param serverAddr 服务端地址，例如 {@code tunnel.example.com:443}
     * @return 当前 ngrok客户端
     */
    public NgrokClient serverAddr(String serverAddr) {
        this.serverAddr = serverAddr;
        return this;
    }

    /**
     * 设置私有部署 CA 证书。
     *
     * @param caCert CA 证书字节
     * @return 当前 ngrok客户端
     */
    public NgrokClient caCert(byte[] caCert) {
        this.caCert = caCert;
        return this;
    }

    /**
     * 设置心跳间隔。
     *
     * @param heartbeatInterval 心跳间隔
     * @return 当前 ngrok客户端
     */
    public NgrokClient heartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
        return this;
    }

    /**
     * 设置心跳容忍。
     *
     * @param heartbeatTolerance 心跳容忍
     * @return 当前 ngrok客户端
     */
    public NgrokClient heartbeatTolerance(Duration heartbeatTolerance) {
        this.heartbeatTolerance = heartbeatTolerance;
        return this;
    }

    /**
      * 连接到 ngrok 服务，建立 会话。
     *
     * @return 当前 ngrok客户端
     */
    public NgrokClient connect() {
        if (session != null) {
            return this;
        }
        try {
            Session.Builder builder = (authtoken == null || authtoken.isEmpty())
                    ? Session.withAuthtokenFromEnv()
                    : Session.withAuthtoken(authtoken);
            if (metadata != null) {
                builder = builder.metadata(metadata);
            }
            if (serverAddr != null) {
                builder = builder.serverAddr(serverAddr);
            }
            if (caCert != null) {
                builder = builder.caCert(caCert);
            }
            builder = builder
                    .heartbeatInterval(heartbeatInterval)
                    .heartbeatTolerance(heartbeatTolerance);
            session = builder.connect();
            log.info("Ngrok Session 已建立: id={}, metadata={}", session.getId(), session.getMetadata());
            return this;
        } catch (IOException e) {
            throw new RuntimeException("Ngrok Session 连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 准备 HTTP 端点构建器。
     *
     * <p>调用后链式配置（如 {@code .domain("...")}），最后调用 {@link #listenHttp()} 或 {@link #forwardHttp(URL)} 启动。</p>
     *
     * @return HttpBuilderStage
     */
    public HttpBuilderStage http() {
        ensureSession();
        return new HttpBuilderStage(this, session.httpEndpoint());
    }

    /**
     * 准备 TCP 端点构建器。
     *
     * <p>调用后链式配置（如 {@code .remoteAddress("...")}），最后调用 {@link #listenTcp()} 启动。</p>
     *
     * @return TcpBuilderStage
     */
    public TcpBuilderStage tcp() {
        ensureSession();
        return new TcpBuilderStage(this, session.tcpEndpoint());
    }

    /**
     * 准备 TLS 端点构建器。
     *
     * @return TlsBuilderStage
     */
    public TlsBuilderStage tls() {
        ensureSession();
        return new TlsBuilderStage(this, session.tlsEndpoint());
    }

    /**
     * 启动 HTTP 监听。
     *
     * @param builder 已配置好的 http构建器
     * @return 当前 ngrok客户端
     */
    public NgrokClient listenHttp(HttpBuilder builder) {
        ensureSession();
        try {
            Listener.Endpoint listener = session.listenHttp(builder);
            listeners.add(listener);
            log.info("Ngrok HTTP 监听已建立: url={}", listener.getUrl());
            return this;
        } catch (IOException e) {
            throw new RuntimeException("Ngrok HTTP 监听失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动 HTTP 转发到本地 URL。
     *
     * @param builder 已配置好的 http构建器
     * @param url     内部目标地址
     * @return 当前 ngrok客户端
     */
    public NgrokClient forwardHttp(HttpBuilder builder, URL url) {
        ensureSession();
        try {
            Forwarder.Endpoint forwarder = session.forwardHttp(builder, url);
            forwarders.add(forwarder);
            log.info("Ngrok HTTP 转发已建立: url={}, target={}", forwarder.getUrl(), url);
            return this;
        } catch (IOException e) {
            throw new RuntimeException("Ngrok HTTP 转发失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动 TCP 监听。
     *
     * @param builder 已配置好的 tcp构建器
     * @return 当前 ngrok客户端
     */
    public NgrokClient listenTcp(TcpBuilder builder) {
        ensureSession();
        try {
            Listener.Endpoint listener = session.listenTcp(builder);
            listeners.add(listener);
            log.info("Ngrok TCP 监听已建立: url={}", listener.getUrl());
            return this;
        } catch (IOException e) {
            throw new RuntimeException("Ngrok TCP 监听失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动 TCP 转发到本地 URL。
     *
     * @param builder 已配置好的 tcp构建器
     * @param url     内部目标地址
     * @return 当前 ngrok客户端
     */
    public NgrokClient forwardTcp(TcpBuilder builder, URL url) {
        ensureSession();
        try {
            Forwarder.Endpoint forwarder = session.forwardTcp(builder, url);
            forwarders.add(forwarder);
            log.info("Ngrok TCP 转发已建立: url={}, target={}", forwarder.getUrl(), url);
            return this;
        } catch (IOException e) {
            throw new RuntimeException("Ngrok TCP 转发失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动 TLS 监听。
     *
     * @param builder 已配置好的 tls构建器
     * @return 当前 ngrok客户端
     */
    public NgrokClient listenTls(TlsBuilder builder) {
        ensureSession();
        try {
            Listener.Endpoint listener = session.listenTls(builder);
            listeners.add(listener);
            log.info("Ngrok TLS 监听已建立: url={}", listener.getUrl());
            return this;
        } catch (IOException e) {
            throw new RuntimeException("Ngrok TLS 监听失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动 TLS 转发到本地 URL。
     *
     * @param builder 已配置好的 tls构建器
     * @param url     内部目标地址
     * @return 当前 ngrok客户端
     */
    public NgrokClient forwardTls(TlsBuilder builder, URL url) {
        ensureSession();
        try {
            Forwarder.Endpoint forwarder = session.forwardTls(builder, url);
            forwarders.add(forwarder);
            log.info("Ngrok TLS 转发已建立: url={}, target={}", forwarder.getUrl(), url);
            return this;
        } catch (IOException e) {
            throw new RuntimeException("Ngrok TLS 转发失败: " + e.getMessage(), e);
        }
    }

    /**
      * 阻塞当前线程，直到 会话 关闭。
     *
     * <p>通常在所有隧道建立后调用此方法，使进程保持运行。</p>
     *
     * @return 当前 ngrok客户端
     */
    public NgrokClient block() {
        ensureSession();
        log.info("Ngrok Client 已就绪，开始阻塞等待 Session 关闭");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Ngrok Client 阻塞被中断");
        }
        return this;
    }

    /**
     * 获取所有已启动监听器与转发器的 URL 列表。
     *
     * @return URL 列表
     */
    public List<String> getUrls() {
        List<String> urls = new ArrayList<>();
        for (Listener listener : listeners) {
            String url = safeGetUrl(listener);
            if (url != null) {
                urls.add(url);
            }
        }
        for (Forwarder forwarder : forwarders) {
            String url = safeGetUrl(forwarder);
            if (url != null) {
                urls.add(url);
            }
        }
        return urls;
    }

    /**
      * 获取底层 ngrok 会话。
     *
     * @return Session 实例（未连接时返回 空）
     */
    public Session getSession() {
        return session;
    }

    @Override
    /** 关闭 */
    public void close() {
        for (Forwarder forwarder : forwarders) {
            try {
                forwarder.close();
            } catch (Exception e) {
                log.warn("关闭 Ngrok Forwarder 失败: {}", e.getMessage());
            }
        }
        forwarders.clear();
        for (Listener listener : listeners) {
            try {
                listener.close();
            } catch (Exception e) {
                log.warn("关闭 Ngrok Listener 失败: {}", e.getMessage());
            }
        }
        listeners.clear();
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("关闭 Ngrok Session 失败: {}", e.getMessage());
            }
            session = null;
        }
    }

    /** ensure会话 */
    private void ensureSession() {
        if (session == null) {
            connect();
        }
    }

    /**
     * Safe获取Url
     *
     * @param obj obj
     * @return safe获取url的结果
     */
    private static String safeGetUrl(Object obj) {
        try {
            return (String) ReflectUtils.invoke(obj, "getUrl", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 端点构建阶段（链式） ====================

    /**
     * HTTP 端点构建阶段。
     *
     * <p>可通过 {@code .domain(...)}、{@code .metadata(...)} 等链式配置后，
     * 调用 {@link #listenHttp()} 或 {@link #forwardHttp(URL)} 启动隧道。</p>
     *
     * @since 4.0.0.42
     */
    public static final class HttpBuilderStage {

        /**
          * 所属 ngrok客户端
         */
        private final NgrokClient owner;

        /**
          * 原始 http构建器
         */
        private final HttpBuilder builder;

        /**
          * 创建 http构建器Stage 实例
         * @param owner owner
         * @param builder http构建器
         * @param builder 构建器
         * @return http构建器Stage的结果
         */
        private HttpBuilderStage(NgrokClient owner, HttpBuilder builder) {
            this.owner = owner;
            this.builder = builder;
        }

        /**
         * 设置监听器元数据。
         *
         * @param metadata 元数据
         * @return 当前阶段
         */
        public HttpBuilderStage metadata(String metadata) {
            builder.metadata(metadata);
            return this;
        }

        /**
         * 设置转发目标描述。
         *
         * @param forwardsTo 转发目标描述
         * @return 当前阶段
         */
        public HttpBuilderStage forwardsTo(String forwardsTo) {
            builder.forwardsTo(forwardsTo);
            return this;
        }

        /**
         * 设置域名。
         *
         * @param domain 域名（需在 ngrok 面板已注册）
         * @return 当前阶段
         */
        public HttpBuilderStage domain(String domain) {
            builder.domain(domain);
            return this;
        }

        /**
         * 设置协议方案。
         *
         * @param scheme HTTP/HTTPS
         * @return 当前阶段
         */
        public HttpBuilderStage scheme(com.ngrok.Http.Scheme scheme) {
            builder.scheme(scheme);
            return this;
        }

        /**
         * 启用 HTTP 响应压缩。
         *
         * @return 当前阶段
         */
        public HttpBuilderStage compression() {
            builder.compression();
            return this;
        }

        /**
         * 启用 WebSocket -> TCP 转换。
         *
         * @return 当前阶段
         */
        public HttpBuilderStage websocketTcpConversion() {
            builder.websocketTcpConversion();
            return this;
        }

        /**
         * 设置熔断阈值（0~1）。
         *
         * @param value 熔断值
         * @return 当前阶段
         */
        public HttpBuilderStage circuitBreaker(double value) {
            builder.circuitBreaker(value);
            return this;
        }

        /**
          * 添加 basic认证 鉴权。
         *
         * @param options 基础认证 配置
         * @return 当前阶段
         */
        public HttpBuilderStage basicAuthOptions(com.ngrok.Http.BasicAuth options) {
            builder.basicAuthOptions(options);
            return this;
        }

        /**
         * 添加 OAuth 鉴权。
         *
         * @param options OAuth 配置
         * @return 当前阶段
         */
        public HttpBuilderStage oauthOptions(com.ngrok.Http.OAuth options) {
            builder.oauthOptions(options);
            return this;
        }

        /**
         * 添加 OIDC 鉴权。
         *
         * @param options OIDC 配置
         * @return 当前阶段
         */
        public HttpBuilderStage oidcOptions(com.ngrok.Http.OIDC options) {
            builder.oidcOptions(options);
            return this;
        }

        /**
         * 添加 Webhook 校验。
         *
         * @param verification Webhook 校验配置
         * @return 当前阶段
         */
        public HttpBuilderStage webhookVerification(com.ngrok.Http.WebhookVerification verification) {
            builder.webhookVerification(verification);
            return this;
        }

        /**
         * 添加请求头。
         *
         * @param name  头名称
         * @param value 头值
         * @return 当前阶段
         */
        public HttpBuilderStage addRequestHeader(String name, String value) {
            builder.addRequestHeader(name, value);
            return this;
        }

        /**
         * 添加响应头。
         *
         * @param name  头名称
         * @param value 头值
         * @return 当前阶段
         */
        public HttpBuilderStage addResponseHeader(String name, String value) {
            builder.addResponseHeader(name, value);
            return this;
        }

        /**
         * 移除请求头。
         *
         * @param name 头名称
         * @return 当前阶段
         */
        public HttpBuilderStage removeRequestHeader(String name) {
            builder.removeRequestHeader(name);
            return this;
        }

        /**
         * 移除响应头。
         *
         * @param name 头名称
         * @return 当前阶段
         */
        public HttpBuilderStage removeResponseHeader(String name) {
            builder.removeResponseHeader(name);
            return this;
        }

        /**
         * 允许指定 CIDR。
         *
         * @param cidr CIDR 列表
         * @return 当前阶段
         */
        public HttpBuilderStage allowCIDR(String cidr) {
            builder.allowCIDR(cidr);
            return this;
        }

        /**
         * 拒绝指定 CIDR。
         *
         * @param cidr CIDR 列表
         * @return 当前阶段
         */
        public HttpBuilderStage denyCIDR(String cidr) {
            builder.denyCIDR(cidr);
            return this;
        }

        /**
         * 设置流量策略。
         *
         * @param policy YAML 策略
         * @return 当前阶段
         */
        public HttpBuilderStage trafficPolicy(String policy) {
            builder.trafficPolicy(policy);
            return this;
        }

        /**
          * 暴露底层 http构建器，供高级用户使用。
         *
         * @return HttpBuilder
         */
        public HttpBuilder builder() {
            return builder;
        }

        /**
         * 启动 HTTP 监听。
         *
         * @return 所属 ngrok客户端
         */
        public NgrokClient listenHttp() {
            return owner.listenHttp(builder);
        }

        /**
         * 启动 HTTP 转发。
         *
         * @param url 内部目标 URL
         * @return 所属 ngrok客户端
         */
        public NgrokClient forwardHttp(URL url) {
            return owner.forwardHttp(builder, url);
        }
    }

    /**
     * TCP 端点构建阶段。
     *
     * @since 4.0.0.42
     */
    public static final class TcpBuilderStage {

        /**
          * 所属 ngrok客户端
         */
        private final NgrokClient owner;

        /**
          * 原始 tcp构建器
         */
        private final TcpBuilder builder;

        /**
          * 创建 tcp构建器Stage 实例
         * @param owner owner
         * @param builder tcp构建器
         * @param builder 构建器
         * @return tcp构建器Stage的结果
         */
        private TcpBuilderStage(NgrokClient owner, TcpBuilder builder) {
            this.owner = owner;
            this.builder = builder;
        }

        /**
         * 设置监听器元数据。
         *
         * @param metadata 元数据
         * @return 当前阶段
         */
        public TcpBuilderStage metadata(String metadata) {
            builder.metadata(metadata);
            return this;
        }

        /**
         * 设置转发目标描述。
         *
         * @param forwardsTo 转发目标描述
         * @return 当前阶段
         */
        public TcpBuilderStage forwardsTo(String forwardsTo) {
            builder.forwardsTo(forwardsTo);
            return this;
        }

        /**
         * 设置保留的远程地址（形如 {@code 1.tcp.ngrok.io:20000}）。
         *
         * @param remoteAddress 远程地址
         * @return 当前阶段
         */
        public TcpBuilderStage remoteAddress(String remoteAddress) {
            builder.remoteAddress(remoteAddress);
            return this;
        }

        /**
         * 允许指定 CIDR。
         *
         * @param cidr CIDR 列表
         * @return 当前阶段
         */
        public TcpBuilderStage allowCIDR(String cidr) {
            builder.allowCIDR(cidr);
            return this;
        }

        /**
         * 拒绝指定 CIDR。
         *
         * @param cidr CIDR 列表
         * @return 当前阶段
         */
        public TcpBuilderStage denyCIDR(String cidr) {
            builder.denyCIDR(cidr);
            return this;
        }

        /**
          * 暴露底层 tcp构建器。
         *
         * @return TcpBuilder
         */
        public TcpBuilder builder() {
            return builder;
        }

        /**
         * 启动 TCP 监听。
         *
         * @return 所属 ngrok客户端
         */
        public NgrokClient listenTcp() {
            return owner.listenTcp(builder);
        }

        /**
         * 启动 TCP 转发。
         *
         * @param url 内部目标 URL
         * @return 所属 ngrok客户端
         */
        public NgrokClient forwardTcp(URL url) {
            return owner.forwardTcp(builder, url);
        }
    }

    /**
     * TLS 端点构建阶段。
     *
     * @since 4.0.0.42
     */
    public static final class TlsBuilderStage {

        /**
          * 所属 ngrok客户端
         */
        private final NgrokClient owner;

        /**
          * 原始 tls构建器
         */
        private final TlsBuilder builder;

        /**
          * 创建 tls构建器Stage 实例
         * @param owner owner
         * @param builder tls构建器
         * @param builder 构建器
         * @return tls构建器Stage的结果
         */
        private TlsBuilderStage(NgrokClient owner, TlsBuilder builder) {
            this.owner = owner;
            this.builder = builder;
        }

        /**
         * 设置监听器元数据。
         *
         * @param metadata 元数据
         * @return 当前阶段
         */
        public TlsBuilderStage metadata(String metadata) {
            builder.metadata(metadata);
            return this;
        }

        /**
         * 设置转发目标描述。
         *
         * @param forwardsTo 转发目标描述
         * @return 当前阶段
         */
        public TlsBuilderStage forwardsTo(String forwardsTo) {
            builder.forwardsTo(forwardsTo);
            return this;
        }

        /**
         * 设置域名。
         *
         * @param domain 域名
         * @return 当前阶段
         */
        public TlsBuilderStage domain(String domain) {
            builder.domain(domain);
            return this;
        }

        /**
         * 允许指定 CIDR。
         *
         * @param cidr CIDR 列表
         * @return 当前阶段
         */
        public TlsBuilderStage allowCIDR(String cidr) {
            builder.allowCIDR(cidr);
            return this;
        }

        /**
         * 拒绝指定 CIDR。
         *
         * @param cidr CIDR 列表
         * @return 当前阶段
         */
        public TlsBuilderStage denyCIDR(String cidr) {
            builder.denyCIDR(cidr);
            return this;
        }

        /**
          * 暴露底层 tls构建器。
         *
         * @return TlsBuilder
         */
        public TlsBuilder builder() {
            return builder;
        }

        /**
         * 启动 TLS 监听。
         *
         * @return 所属 ngrok客户端
         */
        public NgrokClient listenTls() {
            return owner.listenTls(builder);
        }

        /**
         * 启动 TLS 转发。
         *
         * @param url 内部目标 URL
         * @return 所属 ngrok客户端
         */
        public NgrokClient forwardTls(URL url) {
            return owner.forwardTls(builder, url);
        }
    }
}
