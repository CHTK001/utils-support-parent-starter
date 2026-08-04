package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NullUnmarked;

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
@NullUnmarked
@SuppressWarnings("NullAway")
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
            if (ssl != null && ssl.isEnabled()) {
                server = createHttpsServer(addr, ssl);
            } else {
                int backlog = Math.max(setting.getBacklog(), 8192);
                server = HttpServer.create(addr, backlog);
            }
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

    private HttpServer createHttpsServer(InetSocketAddress addr, ServerSetting.SslConfig ssl) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            KeyStore ks = loadKeyStore(ssl);
            char[] password = ssl.getKeyStorePassword() != null
                    ? ssl.getKeyStorePassword().toCharArray() : new char[0];
            kmf.init(ks, password);
            sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());
            HttpsServer httpsServer = HttpsServer.create(addr, Math.max(setting.getBacklog(), 8192));
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            return httpsServer;
        } catch (Exception e) {
            throw new RuntimeException("SSL 配置失败", e);
        }
    }

    private KeyStore loadKeyStore(ServerSetting.SslConfig ssl) throws Exception {
        if (ssl.getKeyStorePath() != null) {
            String type = ssl.getKeyStorePath().toLowerCase().endsWith(".p12") ? "PKCS12" : "JKS";
            KeyStore ks = KeyStore.getInstance(type);
            char[] password = ssl.getKeyStorePassword() != null
                    ? ssl.getKeyStorePassword().toCharArray() : new char[0];
            try (FileInputStream in = new FileInputStream(ssl.getKeyStorePath())) {
                ks.load(in, password);
            }
            return ks;
        }
        if (ssl.getCertPath() != null && ssl.getKeyPath() != null) {
            return loadPemKeyStore(ssl);
        }
        throw new IllegalArgumentException("SSL 已启用但未配置 KeyStore 或 Cert/Key 文件");
    }

    private KeyStore loadPemKeyStore(ServerSetting.SslConfig ssl) throws Exception {
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate cert;
        try (FileInputStream in = new FileInputStream(ssl.getCertPath())) {
            cert = cf.generateCertificate(in);
        }
        byte[] keyBytes = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(ssl.getKeyPath()));
        String keyContent = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
        keyContent = keyContent.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = java.util.Base64.getDecoder().decode(keyContent);
        java.security.spec.PKCS8EncodedKeySpec spec =
                new java.security.spec.PKCS8EncodedKeySpec(decoded);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.security.PrivateKey privateKey = kf.generatePrivate(spec);
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null);
        ks.setKeyEntry("server", privateKey,
                ssl.getKeyPassword() != null ? ssl.getKeyPassword().toCharArray() : new char[0],
                new java.security.cert.Certificate[]{cert});
        return ks;
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