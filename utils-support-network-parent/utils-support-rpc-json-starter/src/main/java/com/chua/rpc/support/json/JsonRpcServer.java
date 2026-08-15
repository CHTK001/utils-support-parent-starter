package com.chua.rpc.support.json;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcServer;
import com.chua.common.support.network.rpc.RpcService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcBasicServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON-RPC 2.0 服务端实现（JDK {@link HttpServer} + {@link JsonRpcBasicServer} 多服务模式）。
 *
 * <p><b>传输</b>：使用 JDK 内建 {@code com.sun.net.httpserver.HttpServer} 监听 HTTP POST，
 * 与 {@link JsonRpcClient}（{@code JsonRpcHttpClient}，HTTP POST）天然互通。</p>
 *
 * <p><b>路由</b>：每个注册的服务对应一个独立的 {@link JsonRpcBasicServer}（单服务 handler）。
 * 客户端通过在 JSON-RPC 请求体中携带 {@code service} 字段（接口全限定名）选择目标服务，
 * 服务端据此路由到对应的 handler。当且仅当只注册了一个服务时，兼容「裸方法名」调用
 * （不带 {@code service} 字段），保持旧客户端与跨语言客户端的互通。</p>
 *
 * <p><b>服务治理</b>：若注册的 bean 标注了 {@link RpcService} 注解并配置了
 * {@code version} / {@code group} / {@code token}，则服务端会校验请求头
 * {@code X-RPC-Version} / {@code X-RPC-Group} / {@code X-RPC-Token}，
 * 不匹配时拒绝请求（HTTP 403）；未配置时放行，保持向后兼容。</p>
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
     * JSON-RPC 请求体中用于路由的服务标识字段名（与 {@link JsonRpcClient} 约定一致）
     */
    static final String SERVICE_FIELD = "service";

    /**
     * 未配置端口时的默认监听端口
     */
    private static final int DEFAULT_PORT = 8080;

    /**
     * 未配置线程数时的默认 HTTP 工作线程数
     */
    private static final int DEFAULT_THREADS = 8;

    /**
     * 请求头：版本号（与 {@link JsonRpcClient} 约定一致，包内可见）
     */
    static final String HEADER_VERSION = "X-RPC-Version";

    /**
     * 请求头：分组（与 {@link JsonRpcClient} 约定一致，包内可见）
     */
    static final String HEADER_GROUP = "X-RPC-Group";

    /**
     * 请求头：安全令牌（与 {@link JsonRpcClient} 约定一致，包内可见）
     */
    static final String HEADER_TOKEN = "X-RPC-Token";

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
     * 服务名（接口全限定名）→ 独立 JSON-RPC 处理器
     */
    private final Map<String, JsonRpcBasicServer> rpcServerMap = new ConcurrentHashMap<>();

    /**
     * 服务名（接口全限定名）→ 服务实现对象（用于读取 {@link RpcService} 治理元数据）
     */
    private final Map<String, Object> serviceMap = new ConcurrentHashMap<>();

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
        if (name == null || bean == null) {
            log.warn("JsonRpcServer ignore invalid register: name={}, bean={}", name, bean);
            return this;
        }
        rpcServerMap.put(name, new JsonRpcBasicServer(objectMapper, bean));
        serviceMap.put(name, bean);
        log.info("Registered JSON-RPC service: {} -> {}", name, bean.getClass().getName());
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
                log.info("JsonRpcServer started: port={}, threads={}, services={}", port, threads, rpcServerMap.size());
            } catch (Exception e) {
                log.error("JsonRpcServer start failed", e);
                state.set(false);
            }
        }
    }

    /**
     * HTTP 请求处理：仅接受 POST，先整体读取请求体，解析 {@code service} 字段完成多服务路由，
     * 再校验服务治理请求头（版本 / 分组 / 令牌），最后转发给对应的 {@link JsonRpcBasicServer} 处理。
     *
     * @param exchange HTTP 交换对象
     * @throws IOException 响应写出失败时抛出
     */
    private void handleHttp(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] body = readRequestBody(exchange.getRequestBody());
        if (body.length == 0) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        String serviceName = resolveServiceName(body);
        JsonRpcBasicServer server = resolveServer(serviceName);
        if (server == null) {
            log.warn("JSON-RPC 无法路由到服务: service={}, 已注册={}", serviceName, rpcServerMap.keySet());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        if (!checkServiceHeaders(exchange, serviceName)) {
            log.warn("JSON-RPC 服务治理校验失败: service={}, version={}, group={}, token={}",
                    serviceName,
                    exchange.getRequestHeaders().getFirst(HEADER_VERSION),
                    exchange.getRequestHeaders().getFirst(HEADER_GROUP),
                    exchange.getRequestHeaders().getFirst(HEADER_TOKEN) != null ? "***" : null);
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
            server.handleRequest(new ByteArrayInputStream(body), buffer);
            byte[] response = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Exception e) {
            log.error("JSON-RPC handle error", e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    /**
     * 完整读取请求体到字节数组（服务端需要「解析路由 + 转发处理」两次消费同一请求体）。
     *
     * @param input 请求体输入流
     * @return 请求体字节数组，不会为 {@code null}
     * @throws IOException 读取失败时抛出
     */
    private static byte[] readRequestBody(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        byte[] chunk = new byte[4096];
        int len;
        while ((len = input.read(chunk)) != -1) {
            out.write(chunk, 0, len);
        }
        return out.toByteArray();
    }

    /**
     * 从 JSON-RPC 请求体中解析 {@code service} 路由字段。
     *
     * @param body 请求体字节数组
     * @return 服务标识；请求体未包含该字段或解析失败时返回 {@code null}
     */
    private String resolveServiceName(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node != null && node.hasNonNull(SERVICE_FIELD)) {
                return node.get(SERVICE_FIELD).asText();
            }
        } catch (IOException e) {
            log.debug("JSON-RPC 请求体解析失败，按无 service 字段处理: {}", e.toString());
        }
        return null;
    }

    /**
     * 根据服务标识选择对应的 JSON-RPC 处理器。
     *
     * <ul>
     *   <li>指定了 {@code service}：按接口全限定名精确匹配；未注册返回 {@code null}</li>
     *   <li>未指定 {@code service}（裸方法名调用）：仅当恰好注册一个服务时返回该服务，
     *       否则返回 {@code null}（无法唯一确定目标）</li>
     * </ul>
     *
     * @param serviceName 服务标识，可为 {@code null}
     * @return 对应的处理器；无法确定时返回 {@code null}
     */
    private JsonRpcBasicServer resolveServer(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) {
            if (rpcServerMap.size() == 1) {
                return rpcServerMap.values().iterator().next();
            }
            return null;
        }
        return rpcServerMap.get(serviceName);
    }

    /**
     * 校验服务治理请求头（版本 / 分组 / 令牌）。
     *
     * <p>仅当注册的 bean 标注了 {@link RpcService} 且显式配置了对应治理字段时才严格校验；
     * 未配置治理元数据或请求头缺失（未设置治理要求）时放行，保证向后兼容。</p>
     *
     * @param exchange    HTTP 交换对象
     * @param serviceName 服务标识
     * @return 校验通过返回 {@code true}
     */
    private boolean checkServiceHeaders(HttpExchange exchange, String serviceName) {
        Object bean = serviceName != null ? serviceMap.get(serviceName) : null;
        RpcService meta = bean == null ? null : bean.getClass().getAnnotation(RpcService.class);
        if (meta == null) {
            return true;
        }
        String version = exchange.getRequestHeaders().getFirst(HEADER_VERSION);
        if (hasText(meta.version()) && !meta.version().equals(version)) {
            return false;
        }
        String group = exchange.getRequestHeaders().getFirst(HEADER_GROUP);
        if (hasText(meta.group()) && !meta.group().equals(group)) {
            return false;
        }
        String token = exchange.getRequestHeaders().getFirst(HEADER_TOKEN);
        if (hasText(meta.token()) && !meta.token().equals(token)) {
            return false;
        }
        return true;
    }

    /**
     * 判断字符串是否非空（{@code null} / 空串 / 纯空白均视为空）。
     *
     * @param value 待判断字符串
     * @return 非空返回 {@code true}
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
        rpcServerMap.clear();
        serviceMap.clear();
        log.info("JsonRpcServer closed");
    }
}
