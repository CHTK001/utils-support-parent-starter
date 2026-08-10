package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.proxy.ProxyTargetResolver;
import com.chua.common.support.network.server.proxy.TcpProxyServer;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * TCP 代理服务器过滤器。
 * <p>该过滤器包装一个内嵌的 {@link TcpProxyServer} 实例，
 * 允许在已有的 {@link com.chua.common.support.network.server.Server}（如 HTTP/TCP）
 * 中以过滤器形式注入 TCP 代理能力，便于多协议统一管理。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * TcpProxyServerFilter filter = TcpProxyServerFilter.builder()
 *         .backend(new InetSocketAddress("127.0.0.1", 6379))
 *         .build();
 * server.addFilter(filter);
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TcpProxyServerFilter implements ServerFilter {

    /**
     * 内嵌的 TCP 代理服务器实例。
     */
    protected final TcpProxyServer proxyServer;

    /**
     * 监听端口。
     */
    protected final int listenPort;

    /**
     * 构造 TCP 代理过滤器。
     *
     * @param listenPort      监听端口
     * @param backend         固定后端地址
     * @param connectTimeoutMs 后端连接超时（毫秒）
     * @param readTimeoutMs    IO 读取超时（毫秒）
     */
    public TcpProxyServerFilter(int listenPort, InetSocketAddress backend,
                                int connectTimeoutMs, int readTimeoutMs) {
        this.listenPort = listenPort;
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(listenPort);
        setting.setProtocol("tcp-proxy");
        setting.setReadTimeout(connectTimeoutMs);
        setting.setWriteTimeout(readTimeoutMs);
        this.proxyServer = new TcpProxyServer(setting, remote -> backend, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 构造 TCP 代理过滤器（自定义目标解析器）。
     *
     * @param listenPort       监听端口
     * @param targetResolver   后端目标解析器
     * @param connectTimeoutMs 后端连接超时（毫秒）
     * @param readTimeoutMs    IO 读取超时（毫秒）
     */
    public TcpProxyServerFilter(int listenPort,
                                ProxyTargetResolver<InetSocketAddress> targetResolver,
                                int connectTimeoutMs, int readTimeoutMs) {
        this.listenPort = listenPort;
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(listenPort);
        setting.setProtocol("tcp-proxy");
        setting.setReadTimeout(connectTimeoutMs);
        setting.setWriteTimeout(readTimeoutMs);
        this.proxyServer = new TcpProxyServer(setting, Objects.requireNonNull(targetResolver));
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
     * @return TcpProxyServer 实例
     */
    public TcpProxyServer getProxyServer() {
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
        return "tcp-proxy@" + listenPort;
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
            log.warn("[tcp-proxy-filter] 关闭代理异常: {}", e.getMessage());
        }
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        chain.doFilter(request, response);
    }

    /**
     * TCP 代理过滤器构建器。
     */
    public static class Builder {

        /**
         * 监听端口。
         */
        private int listenPort = 0;
        /**
         * 固定后端地址。
         */
        private InetSocketAddress backend;
        /**
         * 后端目标解析器。
         */
        private ProxyTargetResolver<InetSocketAddress> targetResolver;
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
         * 设置固定后端地址。
         *
         * @param backend 后端地址
         * @return this
         */
        public Builder backend(InetSocketAddress backend) {
            this.backend = backend;
            return this;
        }

        /**
         * 设置后端目标解析器。
         *
         * @param resolver 解析器
         * @return this
         */
        public Builder targetResolver(ProxyTargetResolver<InetSocketAddress> resolver) {
            this.targetResolver = resolver;
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
         * @return TcpProxyServerFilter 实例
         */
        public TcpProxyServerFilter build() {
            if (targetResolver != null) {
                return new TcpProxyServerFilter(listenPort, targetResolver, connectTimeoutMs, readTimeoutMs);
            }
            if (backend == null) {
                throw new IllegalArgumentException("backend or targetResolver must be set");
            }
            return new TcpProxyServerFilter(listenPort, backend, connectTimeoutMs, readTimeoutMs);
        }
    }
}
