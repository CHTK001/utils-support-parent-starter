package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 JDK {@link HttpServer} 的 HTTP 服务器实现。
 *
 * <p>使用 {@code com.sun.net.httpserver.HttpServer}，简易内嵌，零依赖。
 * 同步阻塞模型，使用虚拟线程池处理请求。
 * SSL 支持 KeyStore（JKS/PKCS12）和 PEM 证书文件两种模式。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
@Spi({"jdk", "jdk-http"})
public class JdkHttpServer extends AbstractServer {

    private HttpServer server;
    private ExecutorService executor;

    public JdkHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            ServerSetting.SslConfig ssl = setting.getSsl();
            SSLContext sslCtx = SslUtils.autoSsl(ssl);
            if (sslCtx != null) {
                server = createHttpsServer(addr, sslCtx);
            } else {
                int backlog = Math.max(setting.getBacklog(), 8192);
                server = HttpServer.create(addr, backlog);
            }
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(server.getAddress().getPort());
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext(setting.getContextPath(), this::handleExchange);
            server.start();
            log.info("JDK HttpServer started on {}:{} (backlog={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), Math.max(setting.getBacklog(), 8192));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpServer createHttpsServer(InetSocketAddress addr, SSLContext sslContext) {
        try {
            HttpsServer httpsServer = HttpsServer.create(addr, Math.max(setting.getBacklog(), 8192));
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            return httpsServer;
        } catch (IOException e) {
            throw new RuntimeException("创建 HTTPS 服务器失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (server != null) {
            server.stop(0);
            log.info("JDK HttpServer stopped");
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    private void handleExchange(HttpExchange exchange) {
        HttpServerRequest request = new HttpServerRequest(exchange, setting.getMaxRequestSize(), setting.getCharset());
        HttpServerResponse response = new HttpServerResponse(exchange);
        try {
            handleRequest(request, response);
        } catch (Exception e) {
            log.warn("Request handling failed", e);
            if (!response.isCommitted()) {
                response.sendError(500, "Internal Server Error");
            }
        } finally {
            response.complete();
        }
    }
}