package com.chua.gateway.server.ws;

import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 远控 WebSocket 桥接服务（独立端口 :8182）。
 *
 * <p>监听独立的 ServerSocket，处理 {@code ws://host:8182/<any-path>}，
 * 把客户端帧透传到对应的 {@link com.chua.gateway.server.bridge.RemoteBridge}。</p>
 *
 * <p>端口从 {@code gateway.ws.port} 读取，默认 8182。
 * 与 HTTP server（:8090）解耦，避免 jdk.httpserver 不支持 WebSocket upgrade 的限制。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GatewayWsServer implements AutoCloseable {

    /**
     * 默认 WS 端口
     */
    public static final int DEFAULT_WS_PORT = 8182;

    /**
     * accept backlog
     */
    private static final int ACCEPT_BACKLOG = 128;

    /**
     * Tunnel registry
     */
    private final TunnelRegistry tunnelRegistry;

    /**
     * 绑定端口
     */
    private final int port;

    /**
     * 服务 socket
     */
    private volatile ServerSocket serverSocket;

    /**
     * accept loop 线程
     */
    private volatile Thread acceptThread;

    /**
     * 帧读写线程池
     */
    private final ExecutorService ioExecutor;

    /**
     * 帧透传 handler
     */
    private final WsEndpointHandler handler;

    /**
     * 运行标志
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GatewayWsServer(TunnelRegistry tunnelRegistry) {
        this(tunnelRegistry, GatewayProperties.wsPort());
    }

    public GatewayWsServer(TunnelRegistry tunnelRegistry, int port) {
        this.tunnelRegistry = tunnelRegistry;
        this.port = port > 0 ? port : DEFAULT_WS_PORT;
        this.ioExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ws-io-" + System.identityHashCode(r));
            t.setDaemon(true);
            return t;
        });
        this.handler = new WsEndpointHandler(tunnelRegistry);
    }

    /**
     * 启动 accept loop。
     *
     * @throws IOException 端口绑定失败
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket(port, ACCEPT_BACKLOG);
        acceptThread = new Thread(this::acceptLoop, "ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.info("[gateway-server] WebSocket 桥接服务启动: port={}", port);
    }

    /**
     * accept 循环：每个连接投给 ioExecutor → WsEndpointHandler。
     */
    private void acceptLoop() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                ioExecutor.submit(() -> handler.handle(socket));
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("[gateway-server] WS accept 异常: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 关闭服务。
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        ioExecutor.shutdownNow();
        handler.shutdown();
        log.info("[gateway-server] WebSocket 桥接服务停止");
    }

    /**
     * 当前绑定端口（如果用了 0 探测可用端口）。
     *
     * @return 端口号
     */
    public int boundPort() {
        return serverSocket == null ? port : serverSocket.getLocalPort();
    }

    /**
     * 是否正在运行。
     *
     * @return boolean
     */
    public boolean isRunning() {
        return running.get();
    }
}
