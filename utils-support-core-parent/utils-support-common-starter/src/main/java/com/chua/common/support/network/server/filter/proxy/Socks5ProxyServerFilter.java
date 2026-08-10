package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.proxy.Socks5ProxyServer;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * SOCKS5 代理服务器过滤器。
 * <p>该过滤器包装一个内嵌的 {@link Socks5ProxyServer} 实例，
 * 可注册到任意 {@link com.chua.common.support.network.server.Server} 上，
 * 以过滤器形式启用 SOCKS5 代理能力。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * Socks5ProxyServerFilter filter = Socks5ProxyServerFilter.builder()
 *         .listenPort(1080)
 *         .username("user")
 *         .password("pass")
 *         .build();
 * server.addFilter(filter);
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Socks5ProxyServerFilter implements ServerFilter {

    /**
     * 内嵌的 SOCKS5 代理服务器实例。
     */
    protected final Socks5ProxyServer proxyServer;

    /**
     * 监听端口。
     */
    protected final int listenPort;

    /**
     * 构造 SOCKS5 代理过滤器（无认证）。
     *
     * @param listenPort       监听端口
     * @param connectTimeoutMs 后端连接超时（毫秒）
     * @param readTimeoutMs    IO 读取超时（毫秒）
     */
    public Socks5ProxyServerFilter(int listenPort, int connectTimeoutMs, int readTimeoutMs) {
        this(listenPort, null, null, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 构造 SOCKS5 代理过滤器（支持用户名/口令认证）。
     *
     * @param listenPort       监听端口
     * @param username         用户名
     * @param password         口令
     * @param connectTimeoutMs 后端连接超时（毫秒）
     * @param readTimeoutMs    IO 读取超时（毫秒）
     */
    public Socks5ProxyServerFilter(int listenPort, String username, String password,
                                   int connectTimeoutMs, int readTimeoutMs) {
        this.listenPort = listenPort;
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(listenPort);
        setting.setProtocol("socks5-proxy");
        setting.setReadTimeout(connectTimeoutMs);
        setting.setWriteTimeout(readTimeoutMs);
        this.proxyServer = new Socks5ProxyServer(setting, username, password, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 创建构建器。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取内嵌的代理服务器实例。
     *
     * @return Socks5ProxyServer 实例
     */
    public Socks5ProxyServer getProxyServer() {
        return proxyServer;
    }

    /**
     * 获取监听端口。
     *
     * @return 端口号
     */
    public int getListenPort() {
        return listenPort;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 30;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.TCP};
    }

    @Override
    public String getFilterId() {
        return "socks5-proxy@" + listenPort;
    }

    @Override
    public void init(ServerFilterConfig config) throws Exception {
        proxyServer.start();
    }

    @Override
    public void destroy() {
        try {
            proxyServer.close();
        } catch (Exception e) {
            log.warn("[socks5-proxy-filter] 关闭代理异常: {}", e.getMessage());
        }
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        chain.doFilter(request, response);
    }

    /**
     * SOCKS5 代理过滤器构建器。
     */
    public static class Builder {

        /**
         * 监听端口。
         */
        private int listenPort = 1080;
        /**
         * 用户名。
         */
        private String username;
        /**
         * 口令。
         */
        private String password;
        /**
         * 后端连接超时（毫秒）。
         */
        private int connectTimeoutMs = 5000;
        /**
         * IO 读取超时（毫秒）。
         */
        private int readTimeoutMs = 30000;

        /**
         * 设置监听端口。
         *
         * @param port 端口号
         * @return this
         */
        public Builder listenPort(int port) {
            this.listenPort = port;
            return this;
        }

        /**
         * 设置用户名。
         *
         * @param username 用户名，null 表示无认证
         * @return this
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * 设置口令。
         *
         * @param password 口令
         * @return this
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * 设置后端连接超时（毫秒）。
         *
         * @param ms 超时
         * @return this
         */
        public Builder connectTimeoutMs(int ms) {
            this.connectTimeoutMs = ms;
            return this;
        }

        /**
         * 设置 IO 读取超时（毫秒）。
         *
         * @param ms 超时
         * @return this
         */
        public Builder readTimeoutMs(int ms) {
            this.readTimeoutMs = ms;
            return this;
        }

        /**
         * 构建过滤器实例。
         *
         * @return Socks5ProxyServerFilter 实例
         */
        public Socks5ProxyServerFilter build() {
            return new Socks5ProxyServerFilter(listenPort, username, password, connectTimeoutMs, readTimeoutMs);
        }
    }
}
