package com.chua.jrebel.support.server;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiSupport;
import com.chua.jrebel.support.service.JRebelLicenseService;
import com.sun.net.httpserver.HttpExchange;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JRebel 许可证 HTTP 服务器。
 * <p>
 * 基于 JDK HttpServer 实现，通过 {@link AbstractServer#registerMapping(String, HttpMethod, com.chua.common.support.network.server.ServerHandler)}
 * 直接注册 JRebel/XRebel 许可证服务端点。
 * </p>
 *
 * <pre>{@code
 * ServerSetting setting = ServerSetting.builder()
 *     .host("0.0.0.0")
 *     .port(8080)
 *     .build();
 * AbstractServer server = new JRebelHttpServer(setting);
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("jrebel")
@SpiDescribe("JRebel 许可证 HTTP 服务器")
@SpiSupport("http")
public class JRebelHttpServer extends AbstractServer {

    /**
     * 许可证服务
     */
    private final JRebelLicenseService licenseService;

    public JRebelHttpServer(ServerSetting setting) {
        this(setting, new JRebelLicenseService());
    }

    public JRebelHttpServer(ServerSetting setting, JRebelLicenseService licenseService) {
        super(setting);
        this.licenseService = licenseService != null ? licenseService : new JRebelLicenseService();
        registerRoutes();
    }

    /**
     * 注册 JRebel 所有端点路由
     */
    private void registerRoutes() {
        registerMapping("/jrebel/leases", HttpMethod.POST, this::handleCreateLease);
        registerMapping("/agent/leases", HttpMethod.POST, this::handleCreateLease);
        registerMapping("/jrebel/validate-connection", HttpMethod.GET, this::handleValidateConnection);
        registerMapping("/rpc/ping.action", HttpMethod.GET, this::handlePing);
        registerMapping("/guid", HttpMethod.GET, this::handleGenerateGuid);
        registerMapping("/rpc/obtainTicket.action", HttpMethod.GET, this::handleObtainTicket);
        registerMapping("/rpc/releaseTicket.action", HttpMethod.GET, this::handleReleaseTicket);
    }

    // ==================== 创建租约 ====================

    private void handleCreateLease(ServerRequest request, ServerResponse response) throws Exception {
        String body = request.getBodyString();
        Map<String, Object> params = parseBody(body);

        String username = getString(params, "username", request.getParam("username"));
        String guid = getString(params, "guid", request.getParam("guid"));
        boolean offline = getBoolean(params, "offline", getBoolean(params, "offline1", true));
        long clientRandomness = getLong(params, "clientRandomness", System.currentTimeMillis());

        if (guid == null || guid.isEmpty()) {
            guid = licenseService.generateGuid(username);
        }

        Map<String, Object> result = licenseService.createLeaseResponse(
                clientRandomness, username, guid, offline);

        response.setStatus(200)
                .setContentType("application/json")
                .end(Json.toJson(result));
    }

    // ==================== 验证连接 ====================

    private void handleValidateConnection(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("serverVersion", "3.2.4");
        result.put("serverProtocolVersion", 2);
        result.put("serverGuid", "a]\\%Qq@Yq/4~}Z^sT");
        result.put("groupType", "managed");
        result.put("statusCode", "SUCCESS");
        result.put("company", "JRebel");
        result.put("canGetLease", true);
        result.put("licenseType", 1);
        result.put("evaluationLicense", false);
        result.put("seatPoolType", "standalone");

        response.setStatus(200)
                .setContentType("application/json")
                .end(Json.toJson(result));
    }

    // ==================== Ping ====================

    private void handlePing(ServerRequest request, ServerResponse response) throws Exception {
        Map<String, Object> result = licenseService.createPingResponse();

        response.setStatus(200)
                .setContentType("application/json")
                .end(Json.toJson(result));
    }

    // ==================== 生成 GUID ====================

    private void handleGenerateGuid(ServerRequest request, ServerResponse response) throws Exception {
        String clientId = request.getParam("clientId");
        String guid = licenseService.generateGuid(clientId);

        Map<String, Object> result = new HashMap<>();
        result.put("guid", guid);
        result.put("status", "SUCCESS");

        response.setStatus(200)
                .setContentType("application/json")
                .end(Json.toJson(result));
    }

    // ==================== 获取票据 ====================

    private void handleObtainTicket(ServerRequest request, ServerResponse response) throws Exception {
        String salt = request.getParam("salt");
        String username = request.getParam("userName");

        if (salt == null || salt.isEmpty()) {
            salt = UUID.randomUUID().toString();
        }

        String xml = """
                <ObtainTicketResponse>
                    <message></message>
                    <responseCode>OK</responseCode>
                    <salt>%s</salt>
                    <ticketId>%d</ticketId>
                    <ticketProperties>
licensee=%s
licenseType=0
                    </ticketProperties>
                </ObtainTicketResponse>
                """.formatted(salt, System.currentTimeMillis(),
                username != null ? username : "JRebel User");

        response.setStatus(200)
                .setContentType("text/xml")
                .end(xml);
    }

    // ==================== 释放票据 ====================

    private void handleReleaseTicket(ServerRequest request, ServerResponse response) throws Exception {
        String ticketId = request.getParam("ticketId");

        String xml = """
                <ReleaseTicketResponse>
                    <message></message>
                    <responseCode>OK</responseCode>
                </ReleaseTicketResponse>
                """;

        response.setStatus(200)
                .setContentType("text/xml")
                .end(xml);
    }

    @Override
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            httpServer = com.sun.net.httpserver.HttpServer.create(addr, setting.getBacklog());
            httpServer.createContext(setting.getContextPath(), this::handleExchange);
            httpServer.setExecutor(createExecutor());
            httpServer.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("JRebel HttpServer shutting down...");
                stop();
            }));

            System.out.println("JRebel HttpServer started on http://" + setting.getHost() + ":" + setting.getPort());
        } catch (Exception e) {
            throw new RuntimeException("启动 JRebel HttpServer 失败", e);
        }
    }

    private com.sun.net.httpserver.HttpServer httpServer;

    private java.util.concurrent.Executor createExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(
                setting.getWorkerThreads(),
                setting.getWorkerThreads(),
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>()
        );
    }

    private void handleExchange(HttpExchange exchange) {
        com.chua.common.support.network.server.impl.HttpServerRequest request =
                new com.chua.common.support.network.server.impl.HttpServerRequest(
                        exchange, setting.getMaxRequestSize(), setting.getCharset());
        com.chua.common.support.network.server.impl.HttpServerResponse response =
                new com.chua.common.support.network.server.impl.HttpServerResponse(exchange);
        try {
            handleRequest(request, response);
        } finally {
            response.complete();
        }
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }

    @Override
    protected void doStop() {
        if (httpServer != null) {
            httpServer.stop(0);
            System.out.println("JRebel HttpServer stopped");
        }
    }

    // ==================== 参数解析辅助方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(String body) {
        if (body == null || body.isEmpty()) {
            return Map.of();
        }
        try {
            return Json.fromJson(body, Map.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String getString(Map<String, Object> params, String key, String defaultValue) {
        if (params.containsKey(key)) {
            Object value = params.get(key);
            return value != null ? value.toString() : defaultValue;
        }
        return defaultValue;
    }

    private boolean getBoolean(Map<String, Object> params, String key, boolean defaultValue) {
        if (params.containsKey(key)) {
            Object value = params.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            } else if (value != null) {
                return "true".equalsIgnoreCase(value.toString());
            }
        }
        return defaultValue;
    }

    private long getLong(Map<String, Object> params, String key, long defaultValue) {
        if (params.containsKey(key)) {
            Object value = params.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            } else if (value != null) {
                try {
                    return Long.parseLong(value.toString());
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }
}
