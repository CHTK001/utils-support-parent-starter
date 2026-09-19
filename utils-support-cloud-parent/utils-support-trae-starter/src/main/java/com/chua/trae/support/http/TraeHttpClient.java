package com.chua.trae.support.http;

import com.chua.trae.support.auth.AuthManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Trae HTTP 客户端，封装 OkHttp 实例、请求头构造与 HTTP 代理配置。
 * 负责向 Trae 后端发送带 Cloud-IDE-JWT 认证的请求。
 *
 * <p>通过 {@link Builder} 模式创建，必须提供 {@link AuthManager}。
 *
 * @see <a href="https://github.com/square/okhttp">OkHttp</a>
 * @author CH
 * @since 4.0.0.42
 */
public class TraeHttpClient {

    private static final Logger log = LoggerFactory.getLogger(TraeHttpClient.class); // 日志
    /** 默认 Trae CN API 主机 */
    private static final String DEFAULT_API_HOST = "https://trae-api-cn.mchost.guru";
    /** 默认 App 标识 */
    private static final String DEFAULT_APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8";
    /** 默认 IDE 版本 */
    private static final String DEFAULT_IDE_VERSION = "3.3.67";
    /** 默认 IDE 版本码 */
    private static final String DEFAULT_IDE_VERSION_CODE = "20260401";

    /** 底层 OkHttp 实例，持有代理/超时配置，不可为 空 */
    private final OkHttpClient client;
    /** 认证管理器，不可为 空 */
    private final AuthManager authManager;
    /** Trae API 主机地址，不可为 空 */
    private final String apiHost;
    /** App 标识 */
    private final String appId;
    /** IDE 版本 */
    private final String ideVersion;
    /** IDE 版本码 */
    private final String ideVersionCode;

    /**
     * Traehttp客户端。
     * @param builder 构建器
     */
    private TraeHttpClient(Builder builder) {
        Objects.requireNonNull(builder.authManager, "authManager is required");
        this.authManager = builder.authManager;
        this.apiHost = builder.apiHost;
        this.appId = builder.appId;
        this.ideVersion = builder.ideVersion;
        this.ideVersionCode = builder.ideVersionCode;

        OkHttpClient.Builder cb = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMinutes(5))
            .writeTimeout(Duration.ofMinutes(1));

        if (builder.httpProxy != null) {
            cb.proxy(new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(builder.httpProxy, builder.proxyPort)));
            log.info("[proxy] Using HTTP proxy: {}:{}", builder.httpProxy, builder.proxyPort);
        }

        this.client = cb.build();
    }

    /**
     * HTTP 客户端 构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class Builder {
        /** 认证管理器，必填 */
        private AuthManager authManager;
        /** API 主机，默认 CN */
        private String apiHost = DEFAULT_API_HOST;
        /** App 标识 */
        private String appId = DEFAULT_APP_ID;
        /** IDE 版本 */
        private String ideVersion = DEFAULT_IDE_VERSION;
        /** IDE 版本码 */
        private String ideVersionCode = DEFAULT_IDE_VERSION_CODE;
        /** HTTP 代理主机，空 表示不启用 */
        private String httpProxy;
        /** HTTP 代理端口，默认 7890 */
        private int proxyPort = 7890;

        /**
        * 设置认证管理器。
        *
        * @param authManager 认证实例，不可为 空
        * @return 当前 构建器
        */
        public Builder authManager(AuthManager authManager) {
            this.authManager = authManager;
            return this;
        }

        /**
         * 设置 API 主机。
         *
         * @param apiHost 主机地址，不可为 空
         * @return 当前 构建器
         */
        public Builder apiHost(String apiHost) {
            this.apiHost = apiHost;
            return this;
        }

        /**
         * 设置 App 标识。
         *
         * @param appId 应用标识
         * @return 当前 构建器
         */
        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        /**
         * 设置 IDE 版本。
         *
         * @param v 版本号
         * @return 当前 构建器
         */
        public Builder ideVersion(String v) {
            this.ideVersion = v;
            return this;
        }

        /**
         * 设置 IDE 版本码。
         *
         * @param v 版本码
         * @return 当前 构建器
         */
        public Builder ideVersionCode(String v) {
            this.ideVersionCode = v;
            return this;
        }

        /**
         * 设置 HTTP 代理。
         *
         * @param host 代理主机
         * @param port 代理端口
         * @return 当前 构建器
         */
        public Builder httpProxy(String host, int port) {
            this.httpProxy = host;
            this.proxyPort = port;
            return this;
        }

        /**
         * 构建 HTTP 客户端。
         *
         * @return 配置完成的 Traehttp客户端
         * @throws IllegalStateException 当 认证管理器 未设置时
         */
        public TraeHttpClient build() {
            if (authManager == null) {
                throw new IllegalStateException("authManager is required");
            }
            return new TraeHttpClient(this);
        }
    }

    /**
     * 构造 Trae 后端请求头。
     * 包含 Cloud-IDE-JWT 认证、设备标识、版本信息等。
     *
     * @param auth 认证快照，不可为 空
     * @return 请求头 映射，有序
     */
    public Map<String, String> buildHeaders(AuthManager.AuthSnapshot auth) {
        Objects.requireNonNull(auth, "auth must not be null");
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Cloud-IDE-JWT " + auth.token());
        headers.put("X-Cloudide-Token", auth.token());
        headers.put("x-app-id", appId);
        headers.put("x-app-version", "default");
        headers.put("x-ide-version-code", ideVersionCode);
        headers.put("x-app-version-code", ideVersionCode);
        headers.put("x-custom-trace-id", traceId);
        headers.put("x-device-type", "windows");
        headers.put("x-device-model", "82RF");
        headers.put("x-device-cpu", "Intel");
        headers.put("x-os-version", "Windows 10");
        headers.put("x-ide-version", ideVersion);
        headers.put("x-ide-version-type", "stable");
        headers.put("request-traffic-type", "prod");
        headers.put("x-uid", auth.userId() != null ? auth.userId() : "");
        return headers;
    }

    /**
     * 构造 OkHttp 请求对象。
     *
     * @param url 目标 URL
     * @param headers 请求头
     * @param body JSON 请求体
     * @param streaming 是否流式请求（true 时附加 Accept: 文本/事件-流）
     * @return 构造好的 请求 对象
     */
    public Request buildRequest(String url, Map<String, String> headers, String body, boolean streaming) {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Request.Builder rb = new Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body));
        headers.forEach(rb::header);
        if (streaming) {
            String reqId = UUID.randomUUID().toString();
            rb.header("Accept", "text/event-stream");
            rb.header("X-Request-ID", reqId);
            rb.header("X-Trae-Request-ID", reqId);
        }
        return rb.build();
    }

    /**
     * 获取底层 OkHttp 实例。
     *
     * @return 原始 OkHttp 客户端
     */
    public OkHttpClient rawClient() { return client; }

    /**
     * 获取 API 主机地址。
     *
     * @return 主机 URL
     */
    public String apiHost() { return apiHost; }

    /**
     * 获取认证管理器。
     *
     * @return AuthManager 实例
     */
    public AuthManager authManager() { return authManager; }
}
