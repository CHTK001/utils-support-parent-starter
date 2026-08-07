package com.chua.gateway.server.api;

import com.chua.common.support.network.annotations.RequestMethod;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.store.ConnectionStore;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 连接鉴权 + 配置查询的 REST Controller。
 *
 * <p>前端 3 步流程的 HTTP 入口（第二步 authenticate）：</p>
 * <pre>
 *   POST /api/connections/authenticate
 *     body: JSON
 *       key 模式:    {"mode":"key",    "key":"server01-key-aaa"}
 *       custom 模式: {"mode":"custom", "protocol":"vnc", "host":"192.168.1.100",
 *                       "port":5900, "user":"admin", "password":"xxx"}
 *     resp: {"tunnelId":"...", "wsUrl":"/ws/vnc/...", "protocol":"vnc", "host":"...", "port":5900}
 * </pre>
 *
 * <p>其他端点：
 *   <ul>
 *     <li>{@code GET /api/connections/keys} — 列出预配置 key（key 模式下拉框用）</li>
 *     <li>{@code GET /api/connections/list} — 列出 SPI 协议名（前端协议下拉框用）</li>
 *   </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class ConnectionController {

    /**
     * JSON 序列化器（线程安全复用）
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 鉴权模式：key
     */
    private static final String MODE_KEY = "key";

    /**
     * 鉴权模式：custom
     */
    private static final String MODE_CUSTOM = "custom";

    /**
     * authenticate 端点路径
     */
    private static final String PATH_AUTHENTICATE = "/api/connections/authenticate";

    /**
     * 列出预配置 key 端点
     */
    private static final String PATH_KEYS = "/api/connections/keys";

    /**
     * 列出 SPI 协议端点
     */
    private static final String PATH_PROTOCOLS = "/api/connections/list";

    /**
     * 默认响应 Content-Type
     */
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

    /**
     * 依赖的连接存储（构造注入）
     */
    private final ConnectionStore connectionStore;

    /**
     * 依赖的 Tunnel Registry（构造注入）
     */
    private final TunnelRegistry tunnelRegistry;

    /**
     * SPI 协议扫描器（构造注入）
     */
    private final ProtocolScanner protocolScanner;

    /**
     * 构造。
     *
     * @param connectionStore  连接存储
     * @param tunnelRegistry   Tunnel 注册中心
     * @param protocolScanner  协议扫描器
     */
    public ConnectionController(ConnectionStore connectionStore,
                                TunnelRegistry tunnelRegistry,
                                ProtocolScanner protocolScanner) {
        this.connectionStore = connectionStore;
        this.tunnelRegistry = tunnelRegistry;
        this.protocolScanner = protocolScanner;
    }

    /**
     * 鉴权端点：key 或 custom 模式。
     *
     * @param request  ServerRequest
     * @param response ServerResponse
     */
    @RequestMethod(value = PATH_AUTHENTICATE, method = "POST")
    public void authenticate(ServerRequest request, ServerResponse response) {
        try {
            AuthRequest req = JSON.readValue(request.getBody(), AuthRequest.class);
            log.info("收到鉴权请求: mode={} key={} protocol={}", req.mode(), req.key(), req.protocol());
            Connection conn = resolveConnection(req);
            if (conn == null) {
                writeJson(response, 401, "{\"error\":\"invalid credentials\"}");
                return;
            }
            String tunnelId = tunnelRegistry.open(conn);
            String wsUrl = "/ws/" + conn.protocol() + "/" + tunnelId;
            AuthResponse out = new AuthResponse(
                    tunnelId,
                    wsUrl,
                    conn.protocol(),
                    conn.host(),
                    conn.port());
            writeJson(response, 200, JSON.writeValueAsString(out));
        } catch (Exception e) {
            log.warn("鉴权处理失败: {}", e.getMessage());
            writeJson(response, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 列出预配置 key。
     *
     * @param request  ServerRequest
     * @param response ServerResponse
     */
    @RequestMethod(value = PATH_KEYS, method = "GET")
    public void listKeys(ServerRequest request, ServerResponse response) {
        try {
            List<String> keys = connectionStore.listKeys();
            writeJson(response, 200, JSON.writeValueAsString(keys));
        } catch (Exception e) {
            log.warn("列出 key 失败: {}", e.getMessage());
            writeJson(response, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 列出可用协议（SPI 扫描得到）。
     *
     * @param request  ServerRequest
     * @param response ServerResponse
     */
    @RequestMethod(value = PATH_PROTOCOLS, method = "GET")
    public void listProtocols(ServerRequest request, ServerResponse response) {
        try {
            List<String> protocols = protocolScanner.listProtocols();
            writeJson(response, 200, JSON.writeValueAsString(protocols));
        } catch (Exception e) {
            log.warn("列出协议失败: {}", e.getMessage());
            writeJson(response, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 解析两种模式为 {@link Connection}。
     *
     * @param req 前端请求
     * @return Connection；未命中返回 {@code null}
     */
    private Connection resolveConnection(AuthRequest req) {
        if (req == null || req.mode() == null) {
            return null;
        }
        if (MODE_KEY.equalsIgnoreCase(req.mode())) {
            if (req.key() == null || req.key().isBlank()) {
                return null;
            }
            Optional<Connection> found = connectionStore.findByKey(req.key());
            return found.orElse(null);
        }
        if (MODE_CUSTOM.equalsIgnoreCase(req.mode())) {
            if (req.protocol() == null || req.host() == null || req.port() == null) {
                return null;
            }
            return connectionStore.upsertByTarget(
                    req.protocol(),
                    req.host(),
                    req.port(),
                    req.user(),
                    req.password());
        }
        return null;
    }

    /**
     * 写 JSON 响应（链式注入 Content-Type / status / body）。
     *
     * @param response   ServerResponse
     * @param statusCode HTTP 状态码
     * @param body       响应体 JSON
     */
    private void writeJson(ServerResponse response, int statusCode, String body) {
        response.setContentType(CONTENT_TYPE_JSON);
        response.setStatus(statusCode);
        response.end(body);
    }
}
