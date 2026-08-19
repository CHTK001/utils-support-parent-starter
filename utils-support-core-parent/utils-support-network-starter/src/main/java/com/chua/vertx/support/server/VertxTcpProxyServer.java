package com.chua.vertx.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTargetResolver;
import com.chua.common.support.spi.annotations.Spi;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Vert.x 事件循环的 TCP 代理服务器,与 {@link com.chua.common.support.network.server.proxy.TcpProxyServer}
 * 能力对齐,但转发完全走事件循环(NetSocket.pipeTo 双向泵送),无每连接虚拟线程开销:
 * <ul>
 *   <li>前端连接接入后,通过 {@link ProxyTargetResolver} 解析后端地址</li>
 *   <li>{@link NetClient} 建立后端连接,双向 {@code pipeTo} 转发(背压自动处理)</li>
 *   <li>任一方关闭,另一方随之关闭</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/16
 */
@Slf4j
@Spi("vertx-tcp-proxy")
public class VertxTcpProxyServer extends AbstractServer {

    /** 目标解析器 */
    private final ProxyTargetResolver<InetSocketAddress> targetResolver;
    /** Vertx */
    private Vertx vertx;
    /** NET服务器 */
    private NetServer netServer;
    /** NET客户端 */
    private NetClient netClient;

    public VertxTcpProxyServer(ServerSetting setting) {
        super(setting);
        // 与 TcpProxyServer 一致:SPI 加载时 resolver 未提供,拒绝所有连接,调用方自行注入
        this.targetResolver = remote -> null;
    }

    public VertxTcpProxyServer(ServerSetting setting, ProxyTargetResolver<InetSocketAddress> targetResolver) {
        super(setting);
        this.targetResolver = targetResolver;
    }

    public VertxTcpProxyServer(ServerSetting setting, InetSocketAddress backend) {
        super(setting);
        this.targetResolver = remote -> backend;
    }

    @Override
    protected void doStart() {
        try {
            VertxOptions opts = new VertxOptions()
                    .setEventLoopPoolSize(Math.max(Runtime.getRuntime().availableProcessors(), 2))
                    .setWorkerPoolSize(Math.max(setting.getWorkerThreads(),
                            Runtime.getRuntime().availableProcessors() * 4))
                    .setPreferNativeTransport(true);
            vertx = Vertx.vertx(opts);
            netClient = vertx.createNetClient(new NetClientOptions()
                    .setTcpNoDelay(setting.isTcpNoDelay())
                    .setConnectTimeout(setting.getReadTimeout())
                    // 后端连接 TCP 性能优化:FastOpen 加速握手,Cork 合并小包,QuickAck 减 ACK 延迟
                    .setTcpFastOpen(true)
                    .setTcpCork(true)
                    .setTcpQuickAck(true)
                    .setReconnectAttempts(0));

            NetServerOptions options = new NetServerOptions()
                    .setHost(setting.getHost())
                    .setPort(setting.getPort())
                    .setTcpNoDelay(setting.isTcpNoDelay())
                    .setAcceptBacklog(Math.max(setting.getBacklog(), 2048))
                    .setReuseAddress(setting.isSoReuseAddr())
                    // 吞吐优化:收发缓冲放大 + TCP_CORK/QUICKACK/FastOpen/KeepAlive
                    .setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384))
                    .setSendBufferSize(Math.max(setting.getBufferSize(), 16384))
                    .setTcpCork(true)
                    .setTcpQuickAck(true)
                    .setTcpFastOpen(true)
                    .setTcpKeepAlive(true);
            netServer = vertx.createNetServer(options);
            netServer.connectHandler(this::handleProxy);
            // Vert.x 5.x:listen 返回 Future,异步完成;用 latch 等监听就绪并回填端口
            CountDownLatch ready = new CountDownLatch(1);
            netServer.listen().onSuccess(server -> {
                setting.setPort(netServer.actualPort());
                log.info("Vertx TcpProxyServer started on {}:{} (eventLoops={}, reactive=true)",
                        setting.getHost(), setting.getPort(),
                        Runtime.getRuntime().availableProcessors());
                ready.countDown();
            }).onFailure(err -> {
                log.error("Vertx TcpProxyServer 启动失败: {}", err.getMessage(), err);
                ready.countDown();
            });
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Vertx TcpProxyServer 监听启动超时");
            }
        } catch (Exception e) {
            throw new RuntimeException("Vertx TcpProxyServer 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (netServer != null) {
            try {
                netServer.close();
            } catch (Exception ignored) {
            }
            log.info("Vertx TcpProxyServer stopped");
        }
        if (netClient != null) {
            try {
                netClient.close();
            } catch (Exception ignored) {
            }
        }
        if (vertx != null) {
            try {
                vertx.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    private void handleProxy(NetSocket front) {
        InetSocketAddress backend;
        try {
            InetSocketAddress remote = front.remoteAddress() != null
                    ? new InetSocketAddress(front.remoteAddress().host(), front.remoteAddress().port())
                    : null;
            backend = targetResolver.resolve(remote);
        } catch (Exception e) {
            log.warn("vertx-tcp-proxy 解析后端地址失败: {}", e.getMessage());
            front.close();
            return;
        }
        if (backend == null || backend.getPort() <= 0) {
            log.warn("vertx-tcp-proxy 后端地址无效: {}", backend);
            front.close();
            return;
        }
        // 关键:先暂停前端,防止客户端数据在 pipeTo 安装前(后端连接建立期间)于
        // flowing 模式下被丢弃——数据早于 onSuccess 到达 front 时会丢失,后端收不到
        // 完整请求 → 不回显 → 客户端挂起。连接成功后 resume 交由 pipeTo 消费。
        front.pause();
        Future<NetSocket> connectFuture = netClient.connect(backend.getPort(), backend.getHostString());
        connectFuture.onSuccess(back -> {
            // 双向 pipeTo:背压由 Vert.x 自动处理,事件循环零拷贝泵送。
            // pipeTo 在源端 EOF 时自动 end 目标端(自带关闭传播),无需手动级联 close
            front.pipeTo(back);
            back.pipeTo(front);
            front.resume();
            log.debug("vertx-tcp-proxy: {} -> {}:{}", front.remoteAddress(), backend.getHostString(), backend.getPort());
        }).onFailure(err -> {
            log.debug("vertx-tcp-proxy 连接后端 {}:{} 失败: {}", backend.getHostString(), backend.getPort(), err.getMessage());
            front.close();
        });
    }
}
