package com.chua.common.support.network.server.nio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 NIO {@link ServerSocketChannel} 的 HTTP/1.1 服务器实现。
 *
 * <p>替代 {@code com.sun.net.httpserver.HttpServer}，从根本上解决单 acceptor + 无法调优的限制。
 * 使用 ServerSocketChannel（阻塞模式）accept 连接，每个连接分配一个虚拟线程处理请求，
 * 支持 HTTP/1.1 Keep-Alive 连接复用。</p>
 *
 * <p>特性：
 * <ul>
 *   <li>Selector 无关 — 阻塞 accept + 虚拟线程阻塞 I/O，简洁高效</li>
 *   <li>完全可控的 backlog / SO_REUSEADDR / TCP_NODELAY / bufferSize</li>
 *   <li>HTTP/1.1 Keep-Alive 连接复用</li>
 *   <li>SSE (Server-Sent Events) chunked transfer 流式推送</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/12
 */
@Slf4j
@Spi({"nio", "nio-http"})
public class NioHttpServer extends AbstractServer {

    private ServerSocketChannel serverChannel;
    private ExecutorService executor;
    private SSLContext sslContext;

    public NioHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            ServerSetting.SslConfig ssl = setting.getSsl();
            sslContext = SslUtils.autoSsl(ssl);
            if (sslContext != null) {
                log.info("NIO HttpServer SSL enabled (selfSigned={})", ssl.isSelfSigned());
            }

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, Math.max(setting.getBufferSize(), 16384));
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    Math.max(setting.getBacklog(), 4096));

            // 回填实际端口（port=0 时由系统分配）
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(bound.getPort());

            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.submit(this::acceptLoop);

            log.info("NIO HttpServer started on {}:{} (backlog={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), Math.max(setting.getBacklog(), 4096));
        } catch (Exception e) {
            throw new RuntimeException("NIO HttpServer 启动失败", e);
        }
    }

    /**
     * 接收连接循环
     */
    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel client = serverChannel.accept();
                if (client != null) {
                    client.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
                    if (setting.getReadTimeout() > 0) {
                        client.socket().setSoTimeout(setting.getReadTimeout());
                    }
                    executor.submit(() -> handleConnection(client));
                }
            } catch (IOException e) {
                if (running) {
                    log.warn("Accept failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 处理连接：循环解析请求并响应，支持Keep-Alive，异常或结束则关闭连接
     */
    private void handleConnection(SocketChannel channel) {
        try {
            NioServerRequest request = new NioServerRequest(channel,
                    setting.getMaxRequestSize(), setting.getCharset());
            while (running && channel.isConnected()) {
                if (!request.parse()) {
                    break; // 连接关闭或解析失败
                }
                NioServerResponse response = new NioServerResponse(channel);
                try {
                    handleRequest(request, response);
                } catch (Exception e) {
                    log.warn("Request handling failed: {}", e.getMessage());
                    if (!response.isCommitted()) {
                        response.sendError(500, "Internal Server Error");
                    }
                } finally {
                    response.complete();
                }
                // Keep-Alive 判断
                if (!shouldKeepAlive(request, response)) {
                    break;
                }
                request.resetForNextRequest();
            }
        } catch (Exception e) {
            log.debug("Connection handling failed: {}", e.getMessage());
        } finally {
            closeQuietly(channel);
        }
    }

    /**
     * 判断是否保持连接
     */
    private boolean shouldKeepAlive(NioServerRequest request, NioServerResponse response) {
        if (response.isChannelClosed()) {
            return false;
        }
        String connHeader = request.getHeader("Connection");
        if (connHeader != null) {
            return "keep-alive".equalsIgnoreCase(connHeader.trim());
        }
        // HTTP/1.1 默认 Keep-Alive
        return "HTTP/1.1".equalsIgnoreCase(request.getHttpVersion());
    }

    /** 安静关闭SocketChannel */
    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void doStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
        log.info("NIO HttpServer stopped");
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    // ==================== SSL 支持 ====================

    /**
     * 获取 SSL 引擎（用于 NIO SSL 通道包装）。
     * 仅在 SSL 启用时有效。
     *
     * @return SSLEngine 实例，SSL 未启用时返回 null
     */
    SSLEngine createSslEngine() {
        if (sslContext == null) {
            return null;
        }
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(false);
        return engine;
    }
}
