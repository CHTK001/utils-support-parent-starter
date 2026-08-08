package com.chua.gateway.server.server;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.gateway.server.api.AuthRequest;
import com.chua.gateway.server.api.AuthResponse;
import com.chua.gateway.server.api.ProtocolScanner;
import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.store.ConnectionStore;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

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
    static void register(UrlMappingServerFilter filter,
                         ConnectionStore store,
                         ProtocolScanner scanner,
                         TunnelRegistry registry) {

        log.info("[DEBUG] RouteRegistrar 开始注册，filter={} count={}",
                filter.getClass().getSimpleName(), filter.routeCount());

        // 用 anyMethod 探测所有进入请求的实际 path/method
        filter.route("/__debug__", (req, resp) -> {
            log.info("[DEBUG-ANY] method={} path={} uri={}",
                    req.getMethod(), req.getPath(), req.getUri());
            writeJson(resp, 200, "{\"ok\":true}");
        });

        // GET /api/connections/keys
        filter.route("/api/connections/keys", HttpMethod.GET, (req, resp) -> {
            log.info("[DEBUG-HIT] /api/connections/keys 被访问 method={} path={}",
                    req.getMethod(), req.getPath());
            try {
                List<String> keys = store.listKeys();
                writeJson(resp, 200, JSON.writeValueAsString(keys));
            } catch (Exception e) {
                writeJson(resp, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
        log.info("[DEBUG] keys 注册完成 count={}", filter.routeCount());

        // GET /api/connections/list
        filter.route("/api/connections/list", HttpMethod.GET, (req, resp) -> {
            try {
                List<String> protocols = scanner.listProtocols();
                writeJson(resp, 200, JSON.writeValueAsString(protocols));
            } catch (Exception e) {
                writeJson(resp, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // POST /api/connections/authenticate
        filter.route("/api/connections/authenticate", HttpMethod.POST, (req, resp) -> {
            try {
                AuthRequest payload = JSON.readValue(req.getBody(), AuthRequest.class);
                com.chua.gateway.server.store.Connection conn = resolveConnection(store, payload);
                if (conn == null) {
                    writeJson(resp, 401, "{\"error\":\"invalid credentials\"}");
                    return;
                }
                String tunnelId = registry.open(conn);
                String wsUrl = "/ws/" + conn.protocol() + "/" + tunnelId;
                AuthResponse out = new AuthResponse(tunnelId, wsUrl, conn.protocol(), conn.host(), conn.port());
                writeJson(resp, 200, JSON.writeValueAsString(out));
            } catch (Exception e) {
                writeJson(resp, 500, "{\"error\":\"" + e.getMessage() + "\"}");
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

    private static void writeJson(ServerResponse resp, int status, String body) {
        try {
            resp.setContentType("application/json; charset=utf-8");
            resp.setStatus(status);
            resp.end(body);
        } catch (Exception e) {
            log.warn("[gateway-server] writeJson 失败: {}", e.getMessage());
        }
    }
}
