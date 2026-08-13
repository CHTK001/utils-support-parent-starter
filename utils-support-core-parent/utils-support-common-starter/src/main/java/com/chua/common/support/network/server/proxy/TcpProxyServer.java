package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 基于原生 JDK {@link java.net.ServerSocket} 的 TCP 反向代理服务器。
 * <p>继承 {@link AbstractProxyServer}，自动获得多 acceptor 并行、Semaphore 连接限流、
 * TCP_NODELAY、32KB ThreadLocal 转发缓冲、CompletableFuture 双向转发等高并发基础设施。</p>
 *
 * <p>每个客户端连接使用虚拟线程进行双向转发；后端目标由
 * {@link ProxyTargetResolver} 解析，可对接静态路由、服务发现或自定义策略。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 1) 静态路由（单目标）
 * TcpProxyServer server = new TcpProxyServer(setting, remote -> new InetSocketAddress("127.0.0.1", 6379));
 *
 * // 2) 多目标，按客户端 IP 分流
 * TcpProxyServer server = new TcpProxyServer(setting, remote -> {
 *     if (remote.getAddress().toString().startsWith("/10")) {
 *         return new InetSocketAddress("redis-a", 6379);
 *     }
 *     return new InetSocketAddress("redis-b", 6379);
 * });
 *
 * server.addFilter(new AccessLogFilter());
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"tcp-proxy"})
public class TcpProxyServer extends AbstractProxyServer {

    /**
     * 后端连接超时（毫秒）。
     */
    protected final int connectTimeoutMs;

    /**
     * IO 读取超时（毫秒）。
     */
    protected final int readTimeoutMs;

    /**
     * 后端目标解析器。
     */
    protected final ProxyTargetResolver<InetSocketAddress> targetResolver;

    /**
     * 构造 TCP 代理服务器（SPI 工厂使用）。
     * <p>注意：通过 SPI 加载时 {@link ProxyTargetResolver} 未提供，
     * 会拒绝所有连接（{@code resolve} 返回 null），调用方需自行注入。</p>
     *
     * @param setting 服务器配置
     */
    public TcpProxyServer(ServerSetting setting) {
        super(setting);
        this.targetResolver = remote -> null;
        this.connectTimeoutMs = setting.getReadTimeout();
        this.readTimeoutMs = setting.getWriteTimeout();
    }

    /**
     * 构造 TCP 代理服务器。
     *
     * @param setting        服务器配置
     * @param targetResolver 后端目标解析器
     */
    public TcpProxyServer(ServerSetting setting, ProxyTargetResolver<InetSocketAddress> targetResolver) {
        super(setting);
        this.targetResolver = targetResolver;
        this.connectTimeoutMs = setting.getReadTimeout();
        this.readTimeoutMs = setting.getWriteTimeout();
    }

    /**
     * 构造 TCP 代理服务器（完整参数）。
     *
     * @param setting          服务器配置
     * @param targetResolver   后端目标解析器
     * @param connectTimeoutMs 后端连接超时（毫秒）
     * @param readTimeoutMs    IO 读取超时（毫秒）
     */
    public TcpProxyServer(ServerSetting setting,
                          ProxyTargetResolver<InetSocketAddress> targetResolver,
                          int connectTimeoutMs,
                          int readTimeoutMs) {
        super(setting);
        this.targetResolver = targetResolver;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 构造 TCP 代理服务器（固定后端地址）。
     *
     * @param setting 服务器配置
     * @param backend 固定后端地址
     */
    public TcpProxyServer(ServerSetting setting, InetSocketAddress backend) {
        this(setting, remote -> backend);
    }

    @Override
    public TcpProxyServer addFilter(ServerFilter filter) {
        super.addFilter(filter);
        return this;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
     * 处理单个客户端连接：解析后端地址 → 建立后端连接 → 双向转发。
     * <p>复用父类 {@link AbstractProxyServer#forwardBidirectional} 进行高效双向数据传输，
     * 自动获得 TCP_NODELAY、32KB ThreadLocal 缓冲、CompletableFuture 并发转发。</p>
     *
     * @param clientSocket 客户端套接字
     */
    @Override
    protected void handleConnection(Socket clientSocket) {
        InetSocketAddress remote = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
        InetSocketAddress backendAddr = targetResolver.resolve(remote);
        if (backendAddr == null) {
            log.warn("[tcp-proxy] 无法解析后端地址 for remote={}", remote);
            closeQuietly(clientSocket);
            return;
        }
        try (Socket backendSocket = new Socket()) {
            backendSocket.connect(backendAddr, connectTimeoutMs);
            backendSocket.setSoTimeout(readTimeoutMs);
            clientSocket.setSoTimeout(readTimeoutMs);
            log.debug("[tcp-proxy] 代理连接建立: {} -> {}:{}",
                    remote, backendAddr.getHostString(), backendAddr.getPort());
            forwardBidirectional(clientSocket, backendSocket);
        } catch (Exception e) {
            log.debug("[tcp-proxy] 代理连接异常: {}", e.getMessage());
        } finally {
            closeQuietly(clientSocket);
        }
    }
}
