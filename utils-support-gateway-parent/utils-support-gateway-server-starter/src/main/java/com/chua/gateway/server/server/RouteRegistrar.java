package com.chua.gateway.server.server;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.gateway.server.api.AuthRequest;
import com.chua.gateway.server.api.ProtocolScanner;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.store.ConnectionStore;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手工注册路由（绕过 common-starter 的反射扫描 bug）。
 *
 * <p>关键：直接由 RouteRegistrar 处理 controller 逻辑，
 * <b>不要</b>反射调用 controller 方法（controller 内部会写 response，导致双写）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
final class RouteRegistrar {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 私有构造，禁止实例化。
     */
    private RouteRegistrar() {
    }

    /**
     * 注册全部端点
     */
    static void register(GatewayUrlMappingFilter filter,
                         ConnectionStore store,
                         ProtocolScanner scanner,
                         TunnelRegistry registry) {

        // GET /api/connections/keys
        filter.route("/api/connections/keys", HttpMethod.GET, (java.util.function.BiConsumer<ServerRequest, ServerResponse>) (req, resp) -> {
            try {
                List<String> keys = store.listKeys();
                writeOk(resp, keys);
            } catch (Exception e) {
                writeError(resp, 500, e.getMessage());
            }
        });

        // GET /api/connections/list
        filter.route("/api/connections/list", HttpMethod.GET, (java.util.function.BiConsumer<ServerRequest, ServerResponse>) (req, resp) -> {
            try {
                List<String> protocols = scanner.listProtocols();
                writeOk(resp, protocols);
            } catch (Exception e) {
                writeError(resp, 500, e.getMessage());
            }
        });

        // POST /api/connections/authenticate
        filter.route("/api/connections/authenticate", HttpMethod.POST, (java.util.function.BiConsumer<ServerRequest, ServerResponse>) (req, resp) -> {
            try {
                AuthRequest payload = JSON.readValue(req.getBody(), AuthRequest.class);
                Connection conn = resolveConnection(store, payload);
                if (conn == null) {
                    writeError(resp, 401, "invalid credentials");
                    return;
                }
                String tunnelId = registry.open(conn);
                String wsUrl = "/ws/" + conn.protocol() + "/" + tunnelId;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("tunnelId", tunnelId);
                out.put("wsUrl", wsUrl);
                out.put("protocol", conn.protocol());
                out.put("host", conn.host());
                out.put("port", conn.port());
                writeOk(resp, out);
            } catch (Exception e) {
                writeError(resp, 500, e.getMessage());
            }
        });

        // POST /api/connections/disconnect
        filter.route("/api/connections/disconnect", HttpMethod.POST,
                (com.chua.common.support.network.server.request.ServerRequest req,
                 com.chua.common.support.network.server.response.ServerResponse resp) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> body = JSON.readValue(req.getBody(), Map.class);
                String tunnelId = body.get("tunnelId");
                if (tunnelId == null || tunnelId.isBlank()) {
                    writeError(resp, 400, "tunnelId required");
                    return;
                }
                registry.close(tunnelId);
                log.info("[gateway-server] 客户端断开: tunnelId={}", tunnelId);
                writeOk(resp, Map.of("closed", tunnelId));
            } catch (Exception e) {
                writeError(resp, 500, e.getMessage());
            }
        });
    }

    /**
     * 自实现 Connection 解析（key 或 custom 模式）
     */
    private static com.chua.gateway.server.store.Connection resolveConnection(
            ConnectionStore store, AuthRequest req) {
        if (req == null || req.mode() == null) {
            return null;
        }
        if ("key".equalsIgnoreCase(req.mode())) {
            if (req.key() == null || req.key().isBlank()) {
                return null;
            }
            return store.findByKey(req.key()).orElse(null);
        }
        if ("custom".equalsIgnoreCase(req.mode())) {
            if (req.protocol() == null || req.host() == null || req.port() == null) {
                return null;
            }
            return store.upsertByTarget(req.protocol(), req.host(), req.port(), req.user(), req.password());
        }
        return null;
    }

    /**
     * 写成功响应（包裹为前端期望的 { status: 0, data: ... } 格式）。
     */
    private static void writeOk(ServerResponse resp, Object data) {
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("status", 0);
            wrapper.put("data", data);
            resp.setContentType("application/json; charset=utf-8");
            resp.setStatus(200);
            resp.end(JSON.writeValueAsString(wrapper));
        } catch (Exception e) {
            log.warn("[gateway-server] writeOk 失败: {}", e.getMessage());
        }
    }

    /**
     * 写错误响应。
     */
    private static void writeError(ServerResponse resp, int httpStatus, String msg) {
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("status", httpStatus);
            wrapper.put("msg", msg);
            resp.setContentType("application/json; charset=utf-8");
            resp.setStatus(httpStatus);
            resp.end(JSON.writeValueAsString(wrapper));
        } catch (Exception e) {
            log.warn("[gateway-server] writeError 失败: {}", e.getMessage());
        }
    }
}
