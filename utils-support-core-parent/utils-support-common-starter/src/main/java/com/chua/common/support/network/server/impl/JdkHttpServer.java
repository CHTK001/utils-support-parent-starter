// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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
 * <h2>连接层指标的边界</h2>
 * <p>{@code com.sun.net.httpserver} 不对外暴露 accept 钩子，连接的生命周期完全在其内部
 * （{@code ServerImpl}）管理，因此本实现<b>无法</b>做「按 {@code maxConnections} 判上限」的
 * 连接预算，也拿不到真实连接数 —— 连接层容量只能交给底层 HttpServer 与操作系统参数。</p>
 *
 * <p>能观测到的部分仍然准确：{@link com.chua.common.support.network.server.ServerMetrics}
 * 里的请求数、活跃请求数、错误数、延迟直方图都走 {@code handleRequest} 统一入口，
 * 与其它实现口径一致；但 {@code getActiveConnections()} / {@code getPeakConnections()}
 * 在本实现上恒为 0。若需要按连接数限流与观测（百万连接目标），请改用
 * {@code NioHttpServer} / {@code AioHttpServer}：它们走自己的 accept 路径，
 * 连接预算与峰值统计都是真实值。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
@Spi({"jdk", "jdk-http"})
public class JdkHttpServer extends AbstractServer {

    /**
     * 服务器
     */
    private HttpServer server;
    /**
     * 执行器
     */
    private ExecutorService executor;

    /**
     * 创建 JdkHttpServer 实例
     * @param setting setting
     */
    public JdkHttpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        rejectStreamEncryption("HTTP 实现请改用 ServerSetting#ssl（SslConfig.auto 或 keyStore/pem）启用 TLS");
        // JDK 内置 HttpServer 的 keep-alive 空闲连接缓存默认上限 200
        // （sun.net.httpserver.maxIdleConnections），超出即被空闲回收线程关闭，
        // 万级长连接下连接数会被钳制在 200。该值仅在 ServerConfig 类加载时读取一次、
        // 无公开 API，只能在创建 HttpServer 前注入系统属性；宿主已显式配置时不覆盖。
        tuneMaxIdleConnections();
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            ServerSetting.SslConfig ssl = setting.getSsl();
            SSLContext sslCtx = SslUtils.autoSsl(ssl);
            if (sslCtx != null) {
                server = createHttpsServer(addr, sslCtx);
            } else {
                int backlog = setting.resolveBacklog(8192);
                server = HttpServer.create(addr, backlog);
            }
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(server.getAddress().getPort());
// [P3C C1 豁免] 场景化创建线程，需文档化
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext(setting.getContextPath(), this::handleExchange);
            server.start();
            log.info("JDK HttpServer started on {}:{} (backlog={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), setting.resolveBacklog(8192));
        } catch (IOException e) {
            throw new RuntimeException("HTTP 服务启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 放宽 JDK 内置 HttpServer 的空闲 keep-alive 连接缓存上限。
     *
     * <p>默认 200：长连接数一旦超过 200，空闲回收线程会持续关闭最久未活动的连接，
     * 高并发长连接场景下连接数被钳制在 200。这里在 ServerConfig 类加载前注入 65536
     * 的上限；空闲连接由 dispatcher 统一持有、不占线程（虚拟线程 per exchange），
     * 放大缓存无额外线程开销。宿主已显式设置该系统属性时尊重宿主配置。</p>
     */
    private static void tuneMaxIdleConnections() {
        final String key = "sun.net.httpserver.maxIdleConnections";
        if (System.getProperty(key) == null) {
            System.setProperty(key, "65536");
        }
    }

    /**
     * 创建HttpsServer
     * @param addr 方法入参 addr
     * @param sslContext ssl上下文，不允许为 null
     * @return Http服务端 对象
     */
    private HttpServer createHttpsServer(InetSocketAddress addr, SSLContext sslContext) {
        try {
            HttpsServer httpsServer = HttpsServer.create(addr, setting.resolveBacklog(8192));
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            return httpsServer;
        } catch (IOException e) {
            throw new RuntimeException("创建 HTTPS 服务器失败", e);
        }
    }

    /**
     * Do停止
     */
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

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    /**
     * 处理Exchange
     * @param exchange 方法入参 exchange
     */
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
    }}