package com.chua.rpc.support.json;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcBasicServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON-RPC 2.0 服务端实现（JDK {@link HttpServer} + {@link JsonRpcBasicServer} 单服务模式）。
 *
 * <p><b>传输</b>：使用 JDK 内建 {@code com.sun.net.httpserver.HttpServer} 监听 HTTP POST，
 * 与 {@link JsonRpcClient}（{@code JsonRpcHttpClient}，HTTP POST）天然互通；
 * 不再使用 {@code StreamServer}（原始 TCP）——旧实现与 HTTP 客户端两层不匹配。</p>
 *
 * <p><b>路由</b>：{@link JsonRpcBasicServer} 单服务模式（handler 直接绑定业务 bean），
 * 客户端以<b>裸方法名</b>调用（{@code getServiceName()==null}），与 {@code JsonRpcHttpClient}
 * 的 {@code invoke(methodName, args, returnType)} 一致。</p>
 *
 * <p><b>并发</b>：{@link HttpServer#setExecutor(ExecutorService)} 设置独立线程池，
 * 避免默认单线程串行处理；{@link #close()} 优雅停止服务并释放线程池。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("json")
@Slf4j
public class JsonRpcServer implements RpcServer {

    /**
     * 未配置端口时的默认监听端口
     */
    private static final int DEFAULT_PORT = 8080;

    /**
     * 未配置线程数时的默认 HTTP 工作线程数
     */
    private static final int DEFAULT_THREADS = 8;

    /**
     * 启动状态（防止 {@link #afterPropertiesSet()} 重复启动）
     */
    private final AtomicBoolean state = new AtomicBoolean(false);

    /**
     * 协议配置（端口 / 线程数）
     */
    private final RpcProtocolConfig protocolConfig;

    /**
     * JSON 序列化器（jsonrpc4j 复用）
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 单服务模式的 JSON-RPC 处理器（{@code register()} 首次调用时懒创建，volatile 保证跨线程可见）
     */
    private volatile JsonRpcBasicServer rpcServer;

    /**
     * JDK HTTP 服务（{@link #afterPropertiesSet()} 启动）
     */
    private HttpServer httpServer;

    /**
     * HTTP 工作线程池
     */
    private ExecutorService executorService;

    /**
     * 构造器。
     *
     * @param rpcRegistryConfigs 注册中心配置（json 实现仅取地址，如 {@code http://127.0.0.1:8080}）
     * @param protocolConfig     协议配置（端口 / 线程数），可为 {@code null}
     * @param name               应用名（json 实现不使用，保留 SPI 构造契约）
     */
    public JsonRpcServer(List<RpcRegistryConfig> rpcRegistryConfigs, RpcProtocolConfig protocolConfig, String name) {
        this.protocolConfig = protocolConfig;
    }

    @Override
    public RpcServer register(String name, Object bean) {
        if (rpcServer == null) {
            synchronized (this) {
                if (rpcServer == null) {
                    rpcServer = new JsonRpcBasicServer(objectMapper, bean);
                    log.info("Registered JSON-RPC service: {}", name);
                }
            }
        } else {
            log.warn("JsonRpcServer already bound, ignore duplicate register: {}", name);
        }
        return this;
    }

    @Override
    public void afterPropertiesSet() {
        if (state.compareAndSet(false, true)) {
            try {
                int port = protocolConfig != null && protocolConfig.port() != null
                        ? protocolConfig.port() : DEFAULT_PORT;
                int threads = protocolConfig != null && protocolConfig.threads() != null
                        ? protocolConfig.threads() : DEFAULT_THREADS;
                httpServer = HttpServer.create(new InetSocketAddress(port), 0);
                executorService = Executors.newFixedThreadPool(threads, r -> {
                    Thread thread = new Thread(r, "json-rpc-http");
                    thread.setDaemon(true);
                    return thread;
                });
                httpServer.setExecutor(executorService);
                httpServer.createContext("/", this::handleHttp);
                httpServer.start();
                log.info("JsonRpcServer started: port={}, threads={}", port, threads);
            } catch (Exception e) {
                log.error("JsonRpcServer start failed", e);
                state.set(false);
            }
        }
    }

    /**
     * HTTP 请求处理：仅接受 POST，转发给 {@link JsonRpcBasicServer} 处理 JSON-RPC 2.0 请求体。
     *
     * @param exchange HTTP 交换对象
     * @throws IOException 响应写出失败时抛出
     */
    private void handleHttp(HttpExchange exchange) throws IOException {
        JsonRpcBasicServer server = rpcServer;
        if (server == null) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
            server.handleRequest(exchange.getRequestBody(), buffer);
            byte[] body = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception e) {
            log.error("JSON-RPC handle error", e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        state.set(false);
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        rpcServer = null;
        log.info("JsonRpcServer closed");
    }
}
