package com.chua.remote.support.gateway.transport.http;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.protocol.support.network.protocol.server.ProtocolServer;
import com.chua.protocol.support.network.protocol.utils.ProtocolServerUtils;
import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayConfigService;
import com.chua.remote.support.gateway.core.auth.TokenVerifier;
import com.chua.remote.support.gateway.core.firewall.AccessLogManager;
import com.chua.remote.support.gateway.core.firewall.GatewayFirewall;
import com.chua.remote.support.gateway.core.firewall.IpFilterManager;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetEntry;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.ControllerRegistry;
import com.chua.remote.support.gateway.ssh.Socks5AgentBootstrapManager;
import com.chua.remote.support.gateway.transport.socks5.ReverseSocks5TunnelManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import io.netty.handler.codec.http.HttpVersion;

/**
 * HTTP 管理端口请求处理器
 * <p>
 * 运行在独立的管理端口上，提供网关的管理 REST API。
 * 功能包括：
 * <ul>
 *   <li>健康检查 (/health, /healthz)</li>
 *   <li>Agent/Target/Controller 列表查询与管理</li>
 *   <li>Token 验证与管理 (/api/token/verify)</li>
 *   <li>防火墙状态查看与配置 (IP封禁/白名单/限流)</li>
 *   <li>代理服务器生命周期管理 (创建/启动/停止/删除)</li>
 *   <li>SPI 提供商信息查询</li>
 *   <li>路径负载均衡策略配置</li>
 * </ul>
 * 所有管理端点均需通过 Token 认证（health 和 token/verify 路径豁免）。
 * 支持 CORS 跨域访问。
 *
 * @author CH
 * @since 2025
 */
@Slf4j
public class HttpManagementHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    /** 目标注册表，维护所有注册的 Agent 和 Target */
    private final TargetRegistry targetRegistry;
    /** 网关限流器 */
    private final GatewayRateLimiter rateLimiter;
    /** 网关配置服务 */
    private final GatewayConfigService configService;
    /** 管理端点的 URL 路径前缀 */
    /**
     * 管理后台路径
     */
    private final String adminPath;
    /** 控制端注册表，管理已连接的控制端 */
    private final ControllerRegistry controllerRegistry;
    /** Agent 注册表，管理 Agent 启禁状态 */
    private final AgentRegistry agentRegistry;
    /** 网关防火墙，提供 IP 过滤与访问日志 */
    private final GatewayFirewall firewall;
    /** SOCKS5 反向隧道管理器 */
    private final ReverseSocks5TunnelManager socks5TunnelManager;
    /** SSH bootstrap 临时 SOCKS5 Agent 管理器 */
    private final Socks5AgentBootstrapManager socks5AgentBootstrapManager;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /** 运行中的代理服务器实例（static 共享跨请求） */
    private static final java.util.concurrent.ConcurrentHashMap<String, ProxyInstance> proxyInstances = new java.util.concurrent.ConcurrentHashMap<>();
    /** API 路径 → 负载均衡策略 映射 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> pathStrategies = new java.util.concurrent.ConcurrentHashMap<>();

    /** 代理服务器实例记录 */
    private static class ProxyInstance {
        final String id;
        final String name;
        final String type;
        final int port;
        final String targetHost;
        final int targetPort;
        volatile boolean running;
        ProtocolServer server;

        ProxyInstance(String id, String name, String type, int port, String targetHost, int targetPort) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.port = port;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }
    }

    /**
     * CORS 跨域响应头常量：允许的来源
     */
    private static final String CORS_HEADER_ORIGIN = "Access-Control-Allow-Origin";
    /**
     * CORS 跨域响应头常量：允许的 HTTP 方法
     */
    private static final String CORS_HEADER_METHODS = "Access-Control-Allow-Methods";
    /**
     * CORS 跨域响应头常量：允许的请求头
     */
    private static final String CORS_HEADER_HEADERS = "Access-Control-Allow-Headers";

    /**
     * 构造一个管理端请求处理器（不含控制端注册表、Agent 注册表和防火墙）
     *
     * @param tr        目标注册表
     * @param rl        网关限流器
     * @param cs        网关配置服务
     * @param adminPath 管理端点的 URL 路径前缀
     */
    public HttpManagementHandler(TargetRegistry tr, GatewayRateLimiter rl, GatewayConfigService cs, String adminPath) {
        this(tr, rl, cs, adminPath, null, null, null, null);
    }

    /**
     * 构造一个管理端请求处理器（含控制端注册表）
     *
     * @param tr                   目标注册表
     * @param rl                   网关限流器
     * @param cs                   网关配置服务
     * @param adminPath            管理端点的 URL 路径前缀
     * @param controllerRegistry   控制端注册表
     */
    public HttpManagementHandler(TargetRegistry tr, GatewayRateLimiter rl, GatewayConfigService cs,
                                  String adminPath, ControllerRegistry controllerRegistry) {
        this(tr, rl, cs, adminPath, controllerRegistry, null, null, null);
    }

    /**
     * 构造一个管理端请求处理器（含控制端注册表和 Agent 注册表）
     *
     * @param tr                   目标注册表
     * @param rl                   网关限流器
     * @param cs                   网关配置服务
     * @param adminPath            管理端点的 URL 路径前缀
     * @param controllerRegistry   控制端注册表
     * @param agentRegistry        Agent 注册表
     */
    public HttpManagementHandler(TargetRegistry tr, GatewayRateLimiter rl, GatewayConfigService cs,
                                  String adminPath, ControllerRegistry controllerRegistry, AgentRegistry agentRegistry) {
        this(tr, rl, cs, adminPath, controllerRegistry, agentRegistry, null, null);
    }

    /**
     * 构造一个完整的管理端请求处理器
     *
     * @param tr                   目标注册表
     * @param rl                   网关限流器
     * @param cs                   网关配置服务
     * @param adminPath            管理端点的 URL 路径前缀
     * @param controllerRegistry   控制端注册表
     * @param agentRegistry        Agent 注册表
     * @param firewall             网关防火墙
     */
    public HttpManagementHandler(TargetRegistry tr, GatewayRateLimiter rl, GatewayConfigService cs,
                                  String adminPath, ControllerRegistry controllerRegistry, AgentRegistry agentRegistry,
                                  GatewayFirewall firewall) {
        this(tr, rl, cs, adminPath, controllerRegistry, agentRegistry, firewall, null);
    }

    public HttpManagementHandler(TargetRegistry tr, GatewayRateLimiter rl, GatewayConfigService cs,
                                  String adminPath, ControllerRegistry controllerRegistry, AgentRegistry agentRegistry,
                                  GatewayFirewall firewall, ReverseSocks5TunnelManager socks5TunnelManager) {
        this(tr, rl, cs, adminPath, controllerRegistry, agentRegistry, firewall, socks5TunnelManager, null);
    }

    public HttpManagementHandler(TargetRegistry tr, GatewayRateLimiter rl, GatewayConfigService cs,
                                  String adminPath, ControllerRegistry controllerRegistry, AgentRegistry agentRegistry,
                                  GatewayFirewall firewall, ReverseSocks5TunnelManager socks5TunnelManager,
                                  Socks5AgentBootstrapManager socks5AgentBootstrapManager) {
        this.targetRegistry = tr;
        this.rateLimiter = rl;
        this.configService = cs;
        this.adminPath = adminPath;
        this.controllerRegistry = controllerRegistry;
        this.agentRegistry = agentRegistry;
        this.firewall = firewall;
        this.socks5TunnelManager = socks5TunnelManager;
        this.socks5AgentBootstrapManager = socks5AgentBootstrapManager;
    }

    /**
     * 接收并处理完整的 HTTP 请求
     * <p>
     * 路由逻辑：
     * <ul>
     *   <li>先做 CORS 预检处理（OPTIONS）</li>
     *   <li>防火墙检查（IP 过滤 + 限流）</li>
     *   <li>Token 验证（豁免路径除外）</li>
     *   <li>按 URI 分发到对应的处理逻辑</li>
     * </ul>
     * 支持的管理端点包括：健康检查、Agent/Target 查询、Controller 管理、
     * 防火墙管理、代理服务器管理、SPI 提供商信息、路径策略配置等。
     *
     * @param ctx Channel 上下文
     * @param req 完整的 HTTP 请求
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.decoderResult().isSuccess()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"bad request\"}"); return; }

        // CORS 预检请求
        if (req.method() == HttpMethod.OPTIONS) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
            setCorsHeaders(resp.headers());
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            String rawUri = req.uri();
            int qIdx = rawUri.indexOf('?');
            String uri = qIdx >= 0 ? rawUri.substring(0, qIdx) : rawUri;

            // 防火墙检查: IP 过滤 + 限流
            if (firewall != null) {
                FullHttpResponse blockResp = firewall.check(ctx, req);
                if (blockResp != null) {
                    ctx.writeAndFlush(blockResp).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
            }

            // Token 验证（管理端口始终需要 token，health 和 token/verify 豁免）
            if (configService != null && configService.isApiTokenEnabled()) {
                if (!isExemptRoute(uri, adminPath)) {
                    if (!TokenVerifier.requireToken(ctx, req, configService)) { return; }
                }
            }

            String body;

            // 健康检查：同时响应 /admin/health, /api/health, /health 等
            if (match(uri, adminPath, "/health") || match(uri, adminPath, "/healthz")
                    || "/health".equals(uri) || "/healthz".equals(uri)
                    || "/api/health".equals(uri)) {
                body = "{\"status\":\"UP\",\"agents\":" + targetRegistry.agentCount() + ",\"targets\":" + targetRegistry.targetCount() + "}";
            } else if (match(uri, adminPath, "/api/targets") || "/api/targets".equals(uri)
                    || "/api/targets/detail".equals(uri)) {
                List<TargetEntry> targets = targetRegistry.allTargets();
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < targets.size(); i++) {
                    TargetEntry t = targets.get(i);
                    if (i > 0) { json.append(","); }
                    json.append("{\"targetId\":\"").append(t.getTargetId()).append("\",\"host\":\"").append(t.getHost())
                            .append("\",\"port\":").append(t.getPort()).append(",\"protocol\":\"").append(t.getProtocol())
                            .append("\",\"agentId\":\"").append(t.getAgentId()).append("\",\"online\":true}");
                }
                json.append("]");
                body = json.toString();
            } else if (match(uri, adminPath, "/api/socks5/tunnel") || "/api/socks5/tunnel".equals(uri)) {
                body = handleSocks5TunnelConfig(ctx, req);
                if (body == null) { return; }
            } else if (isSocks5BootstrapRoute(uri)) {
                body = handleSocks5Bootstrap(ctx, req, uri);
                if (body == null) { return; }
            } else if (match(uri, adminPath, "/api/socks5/clients") || "/api/socks5/clients".equals(uri)
                    || uri.startsWith(adminPath + "/api/socks5/clients/") || uri.startsWith("/api/socks5/clients/")) {
                body = handleSocks5Clients(ctx, req, uri);
                if (body == null) { return; }
            } else if (match(uri, adminPath, "/api/socks5/agents") || "/api/socks5/agents".equals(uri)) {
                if (req.method() != HttpMethod.GET) {
                    sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                    return;
                }
                body = handleSocks5Agents(req);
            } else if (isSocks5LifecycleRoute(uri)) {
                body = handleSocks5Lifecycle(ctx, req, uri);
                if (body == null) { return; }
            } else if (match(uri, adminPath, "/api/agents") || "/api/agents".equals(uri)) {
                List<AgentInfo> agents = targetRegistry.onlineAgents();
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < agents.size(); i++) {
                    AgentInfo a = agents.get(i);
                    if (i > 0) { json.append(","); }
                    json.append("{\"agentId\":\"").append(a.getAgentId()).append("\"")
                            .append(",\"online\":").append(a.isOnline())
                            .append(",\"disabled\":").append(agentRegistry != null && agentRegistry.isDisabled(a.getAgentId()))
                            .append(",\"weight\":").append(a.getWeight())
                            .append(",\"strategy\":\"").append(escapeJson(a.getStrategy())).append("\"")
                            .append(",\"apiPath\":\"").append(escapeJson(a.getApiPath())).append("\"")
                            .append(",\"agentType\":\"").append(escapeJson(a.getAgentType())).append("\"")
                            .append(",\"ipAddress\":\"").append(escapeJson(a.getIpAddress() != null ? a.getIpAddress() : "unknown")).append("\"")
                            .append(",\"remotePort\":").append(a.getRemotePort())
                            .append(",\"verifyCode\":\"").append(escapeJson(a.getVerifyCode() != null ? a.getVerifyCode() : "")).append("\"")
                            .append(",\"lastHeartbeatAt\":\"").append(a.getLastHeartbeatAt() != null ? a.getLastHeartbeatAt().toString() : "").append("\"")
                            .append(",\"lastRttMs\":").append(a.getLastRttMs())
                            .append(",\"protocols\":[");
                    if (a.getProtocols() != null) {
                        for (int j = 0; j < a.getProtocols().size(); j++) {
                            if (j > 0) { json.append(","); }
                            json.append("\"").append(a.getProtocols().get(j)).append("\"");
                        }
                    }
                    json.append("]")
                    .append(",\"codecs\":[");
                    if (a.getCodecs() != null) {
                        for (int j = 0; j < a.getCodecs().size(); j++) {
                            if (j > 0) { json.append(","); }
                            json.append("\"").append(a.getCodecs().get(j)).append("\"");
                        }
                    }
                    json.append("]")
                    .append(",\"capabilities\":{");
                    if (a.getCapabilities() != null) {
                        boolean first = true;
                        for (var entry : a.getCapabilities().entrySet()) {
                            if (!first) { json.append(","); }
                            json.append("\"").append(entry.getKey()).append("\":\"")
                                    .append(escapeJson(entry.getValue())).append("\"");
                            first = false;
                        }
                    }
                    json.append("}");
                    json.append("}");
                }
                json.append("]");
                body = json.toString();
            } else if (match(uri, adminPath, "/api/stats") || "/api/stats".equals(uri)) {
                body = "{\"agents\":" + targetRegistry.agentCount() + ",\"targets\":" + targetRegistry.targetCount() + "}";
            } else if (match(uri, adminPath, "/api/controllers") || "/api/controllers".equals(uri)) {
                if (controllerRegistry != null) {
                    StringBuilder cj = new StringBuilder("[");
                    var controllers = controllerRegistry.allControllers();
                    for (int i = 0; i < controllers.size(); i++) {
                        var c = controllers.get(i);
                        if (i > 0) { cj.append(","); }
                        cj.append("{\"id\":\"").append(escapeJson(c.id()))
                                .append("\",\"ipAddress\":\"").append(escapeJson(c.ipAddress()))
                                .append("\",\"connectedAt\":\"").append(c.connectedAt().toString())
                                .append("\",\"active\":").append(c.active())
                                .append(",\"type\":\"").append(escapeJson(c.type())).append("\"}");
                    }
                    cj.append("]");
                    body = "{\"count\":" + controllerRegistry.count() + ",\"controllers\":" + cj + "}";
                }
 else {
                    body = "{\"count\":0,\"controllers\":[]}";
                }
            } else if (uri.startsWith(adminPath + "/api/controllers/") || uri.startsWith("/api/controllers/")) {
                // DELETE /admin/api/controllers/{id} — 踢出控制端
                if (req.method() == HttpMethod.DELETE && controllerRegistry != null) {
                    String prefix = uri.startsWith(adminPath) ? adminPath + "/api/controllers/" : "/api/controllers/";
                    String ctrlId = uri.substring(prefix.length());
                    if (ctrlId.isEmpty()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"missing controller id\"}"); return; }
                    boolean kicked = controllerRegistry.kickController(ctrlId);
                    body = "{\"kicked\":" + kicked + ",\"controllerId\":\"" + escapeJson(ctrlId) + "\"}";
                }
 else {
                    sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                    return;
                }
            } else if (uri.matches(".*(/admin)?/api/agents/[^/]+/(disable|enable|weight|strategy|apiPath)")) {
                // Agent 管理端点: PUT .../agents/{id}/disable|enable|weight|strategy|apiPath
                if (agentRegistry == null) {
                    sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "{\"error\":\"agent registry not available\"}");
                    return;
                }
                // 提取 agentId 和 action
                String[] parts = uri.split("/");
                String action = parts[parts.length - 1];
                String agentId = parts[parts.length - 2];
                if (req.method() != HttpMethod.PUT) {
                    sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                    return;
                }
                switch (action) {
                    case "disable" -> {
                        agentRegistry.disableAgent(agentId);
                        body = "{\"agentId\":\"" + escapeJson(agentId) + "\",\"disabled\":true}";
                    }
                    case "enable" -> {
                        agentRegistry.enableAgent(agentId);
                        body = "{\"agentId\":\"" + escapeJson(agentId) + "\",\"disabled\":false}";
                    }
                    case "weight" -> {
                        String putBody = req.content().toString(io.netty.util.CharsetUtil.UTF_8);
                        int w = 1;
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
                            @SuppressWarnings("unchecked")
                            var map = m.readValue(putBody, java.util.Map.class);
                            w = map.containsKey("weight") ? ((Number) map.get("weight")).intValue() : 1;
                        }
 catch (Exception e) { log.debug("解析权重失败", e); }
                        AgentInfo a = targetRegistry.getAgent(agentId);
                        if (a != null) { a.setWeight(w); }
                        body = "{\"agentId\":\"" + escapeJson(agentId) + "\",\"weight\":" + w + "}";
                    }
                    case "strategy" -> {
                        String putBody = req.content().toString(io.netty.util.CharsetUtil.UTF_8);
                        String strategy = "round_robin";
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
                            @SuppressWarnings("unchecked")
                            var map = m.readValue(putBody, java.util.Map.class);
                            strategy = map.containsKey("strategy") ? (String) map.get("strategy") : "round_robin";
                        }
 catch (Exception e) { log.debug("解析策略失败", e); }
                        AgentInfo a = targetRegistry.getAgent(agentId);
                        if (a != null) { a.setStrategy(strategy); }
                        body = "{\"agentId\":\"" + escapeJson(agentId) + "\",\"strategy\":\"" + escapeJson(strategy) + "\"}";
                    }
                    case "apiPath" -> {
                        String putBody = req.content().toString(io.netty.util.CharsetUtil.UTF_8);
                        String apiPath = "";
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
                            @SuppressWarnings("unchecked")
                            var map = m.readValue(putBody, java.util.Map.class);
                            apiPath = map.containsKey("apiPath") ? (String) map.get("apiPath") : "";
                        }
 catch (Exception e) { log.debug("解析 API 路径失败", e); }
                        AgentInfo a = targetRegistry.getAgent(agentId);
                        if (a != null) { a.setApiPath(apiPath); }
                        body = "{\"agentId\":\"" + escapeJson(agentId) + "\",\"apiPath\":\"" + escapeJson(apiPath) + "\"}";
                    }
                    default -> {
                        sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}");
                        return;
                    }
                }
            } else if ("/api/path-strategies".equals(uri) || match(uri, adminPath, "/api/path-strategies")) {
                if (req.method() == HttpMethod.GET) {
                    StringBuilder sb = new StringBuilder("{");
                    boolean first = true;
                    for (var entry : pathStrategies.entrySet()) {
                        if (!first) { sb.append(","); }
                        sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"").append(escapeJson(entry.getValue())).append("\"");
                        first = false;
                    }
                    sb.append("}");
                    body = sb.toString();
                } else if (req.method() == HttpMethod.PUT) {
                    String pBody = req.content().toString(CharsetUtil.UTF_8);
                    var map = mapper.readValue(pBody, java.util.Map.class);
                    String path = (String) map.get("path");
                    String strategy = (String) map.getOrDefault("strategy", "round_robin");
                    if (path == null || path.isEmpty()) {
                        sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"missing path\"}");
                        return;
                    }
                    pathStrategies.put(path, strategy);
                    log.info("[Admin] 更新路径策略: path={} strategy={}", path, strategy);
                    body = "{\"path\":\"" + escapeJson(path) + "\",\"strategy\":\"" + escapeJson(strategy) + "\"}";
                }
 else {
                    sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                    return;
                }
            } else if ("/api/token/verify".equals(uri) || match(uri, adminPath, "/api/token/verify")) {
                // 从 query string 提取 token 参数
                String query = qIdx >= 0 ? rawUri.substring(qIdx + 1) : "";
                String token = "";
                for (String param : query.split("&")) {
                    if (param.startsWith("token=")) { token = param.substring(6); break; }
                }
                boolean valid = configService != null && configService.verifyToken(token);
                body = "{\"valid\":" + valid + "}";
            } else if (firewall != null && (match(uri, adminPath, "/api/firewall/status") || "/api/firewall/status".equals(uri))) {
                body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(firewall.getStatus());
            } else if (firewall != null && (match(uri, adminPath, "/api/firewall/stats") || "/api/firewall/stats".equals(uri))) {
                body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(firewall.getAccessLog().getStats());
            } else if (firewall != null && (match(uri, adminPath, "/api/firewall/metrics") || "/api/firewall/metrics".equals(uri))) {
                body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(firewall.getRealtimeMetrics());
            } else if (firewall != null && (match(uri, adminPath, "/api/firewall/logs") || "/api/firewall/logs".equals(uri))) {
                String logIp = extractParam(rawUri, "ip");
                int logCount = 100;
                try { logCount = Integer.parseInt(extractParam(rawUri, "count")); }
 catch (Exception e) { log.debug("解析日志数量失败", e); }
                List<AccessLogManager.AccessEntry> logEntries = logIp != null ?
                        firewall.getAccessLog().getLogsByIp(logIp, logCount) :
                        firewall.getAccessLog().getRecentLogs(logCount);
                StringBuilder lb = new StringBuilder("[");
                boolean first = true;
                for (var e : logEntries) {
                    if (!first) { lb.append(","); }
                    lb.append("{\"ip\":\"").append(escapeJson(e.ip))
                            .append("\",\"path\":\"").append(escapeJson(e.path))
                            .append("\",\"method\":\"").append(escapeJson(e.method))
                            .append("\",\"statusCode\":").append(e.statusCode)
                            .append(",\"responseTimeMs\":").append(e.responseTimeMs)
                            .append(",\"timestamp\":\"").append(e.timestamp.toString())
                            .append("\",\"userAgent\":\"").append(escapeJson(e.userAgent != null ? e.userAgent : "")).append("\"}");
                    first = false;
                }
                lb.append("]");
                body = lb.toString();
            } else if (firewall != null && (match(uri, adminPath, "/api/firewall/blocked") || "/api/firewall/blocked".equals(uri))) {
                body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(firewall.getIpFilter().getBlockedList());
            } else if (firewall != null && (match(uri, adminPath, "/api/firewall/allowed") || "/api/firewall/allowed".equals(uri))) {
                body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(firewall.getIpFilter().getAllowedList());
            } else if (firewall != null && uri.matches(".*(/admin)?/api/firewall/(mode|block|allow|rate-limit|logs/clear)")) {
                String action = uri.substring(uri.lastIndexOf('/') + 1);
                switch (action) {
                    case "mode" -> {
                        if (req.method() != HttpMethod.PUT) { sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}"); return; }
                        String pBody = req.content().toString(CharsetUtil.UTF_8);
                        var map = mapper.readValue(pBody, java.util.Map.class);
                        String mode = (String) map.get("mode");
                        firewall.getIpFilter().setMode(IpFilterManager.FilterMode.valueOf(mode));
                        body = "{\"mode\":\"" + mode + "\"}";
                    }
                    case "block" -> {
                        if (req.method() == HttpMethod.PUT) {
                            String pBody = req.content().toString(CharsetUtil.UTF_8);
                            var map = mapper.readValue(pBody, java.util.Map.class);
                            String blIp = (String) map.get("ip");
                            String reason = (String) map.getOrDefault("reason", "手动封禁");
                            if (blIp == null || blIp.isEmpty()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"missing ip\"}"); return; }
                            firewall.getIpFilter().blockIp(blIp, reason);
                            body = "{\"ip\":\"" + blIp + "\",\"blocked\":true}";
                        } else if (req.method() == HttpMethod.DELETE) {
                            String blIp = extractParam(rawUri, "ip");
                            if (blIp == null || blIp.isEmpty()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"missing ip\"}"); return; }
                            boolean unb = firewall.getIpFilter().unblockIp(blIp);
                            body = "{\"ip\":\"" + blIp + "\",\"unblocked\":" + unb + "}";
                        }
 else { sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}"); return; }
                    }
                    case "allow" -> {
                        if (req.method() == HttpMethod.PUT) {
                            String pBody = req.content().toString(CharsetUtil.UTF_8);
                            var map = mapper.readValue(pBody, java.util.Map.class);
                            String alIp = (String) map.get("ip");
                            if (alIp == null || alIp.isEmpty()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"missing ip\"}"); return; }
                            firewall.getIpFilter().allowIp(alIp);
                            body = "{\"ip\":\"" + alIp + "\",\"allowed\":true}";
                        } else if (req.method() == HttpMethod.DELETE) {
                            String alIp = extractParam(rawUri, "ip");
                            if (alIp == null || alIp.isEmpty()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"missing ip\"}"); return; }
                            boolean rem = firewall.getIpFilter().removeAllowedIp(alIp);
                            body = "{\"ip\":\"" + alIp + "\",\"removed\":" + rem + "}";
                        }
 else { sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}"); return; }
                    }
                    case "rate-limit" -> {
                        if (req.method() != HttpMethod.PUT) { sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}"); return; }
                        String pBody = req.content().toString(CharsetUtil.UTF_8);
                        var map = mapper.readValue(pBody, java.util.Map.class);
                        boolean enabled = Boolean.TRUE.equals(map.get("enabled"));
                        firewall.setRateLimitEnabled(enabled);
                        body = "{\"rateLimitEnabled\":" + enabled + "}";
                    }
                    case "clear" -> {
                        firewall.getAccessLog().clear();
                        body = "{\"cleared\":true}";
                    }
                    default -> { sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}"); return; }
                }
            } else if (match(uri, adminPath, "/api/proxies") || "/api/proxies".equals(uri)) {
                // 代理服务器管理
                body = handleProxyRoutes(ctx, req, rawUri, qIdx);
                if (body == null) { return; }
            } else if (uri.matches(".*(/admin)?/api/proxies/[^/]+/(start|stop)")) {
                body = handleProxyLifecycle(ctx, req, uri);
                if (body == null) { return; }
            } else if (match(uri, adminPath, "/api/proxy/types") || "/api/proxy/types".equals(uri)) {
                body = handleProxyTypes();
            } else if (match(uri, adminPath, "/api/spi/providers") || "/api/spi/providers".equals(uri)) {
                body = handleSpiProviders();
            }
 else {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}");
                return;
            }
            sendJson(ctx, OK, body);
            if (firewall != null) { firewall.recordFromRequest(ctx, req, 200, System.currentTimeMillis() - startTime); }
        }
 catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"" + e.getMessage() + "\"}");
            if (firewall != null) { firewall.recordFromRequest(ctx, req, 500, System.currentTimeMillis() - startTime); }
        }
    }

    /**
     * 判断 URI 是否匹配 "adminPath + suffix" 的组合
     *
     * @param uri       请求 URI
     * @param adminPath 管理路径前缀
     * @param suffix    路径后缀
     * @return 匹配返回 true
     */
    private static boolean match(String uri, String adminPath, String suffix) {
        if ("/".equals(adminPath)) { return uri.equals(suffix); }
        return uri.equals(adminPath + suffix);
    }

    /**
     * 判断请求路径是否为 Token 豁免路径（无需 Token 认证即可访问）
     * <p>
     * 当前豁免路径包括：/health、/healthz、/api/health、/api/token/verify
     *
     * @param uri       请求 URI
     * @param adminPath 管理路径前缀
     * @return 如果是豁免路径返回 true
     */
    private static boolean isExemptRoute(String uri, String adminPath) {
        return match(uri, adminPath, "/health") || match(uri, adminPath, "/healthz")
                || "/health".equals(uri) || "/healthz".equals(uri) || "/api/health".equals(uri)
                || uri.startsWith("/api/token/verify") || uri.startsWith(adminPath + "/api/token/verify")
                || "/api/socks5/bootstrap".equals(uri) || uri.startsWith("/api/socks5/bootstrap/")
                || match(uri, adminPath, "/api/socks5/bootstrap") || uri.startsWith(adminPath + "/api/socks5/bootstrap/");
    }

    /**
     * 对 JSON 字符串中的特殊字符进行转义，防止 JSON 注入
     *
     * @param s 原始字符串
     * @return 转义后的字符串，null 输入返回空字符串
     */
    private static String escapeJson(String s) {
        if (s == null) { return ""; }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 从 URI 查询参数中提取指定名称的参数值
     *
     * @param uri  完整的请求 URI（含查询参数）
     * @param name 参数名称
     * @return 参数值，未找到返回 null
     */
    private static String extractParam(String uri, String name) {
        int qIdx = uri.indexOf('?');
        if (qIdx < 0) { return null; }
        String query = uri.substring(qIdx + 1);
        for (String param : query.split("&")) {
            if (param.startsWith(name + "=")) {
                return java.net.URLDecoder.decode(param.substring(name.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String handleSocks5TunnelConfig(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (configService == null) {
            sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "{\"error\":\"config service not available\"}");
            return null;
        }
        if (req.method() == HttpMethod.GET) {
            return socks5TunnelConfigJson();
        }
        if (req.method() != HttpMethod.PUT && req.method() != HttpMethod.POST) {
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
            return null;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> updates = mapper.readValue(body, Map.class);
        Map<String, Object> allowed = new java.util.LinkedHashMap<>();
        copyIfPresent(updates, allowed, "sshReverseTunnelEnabled");
        copyIfPresent(updates, allowed, "sshRemoteHost");
        copyIfPresent(updates, allowed, "sshRemotePort");
        copyIfPresent(updates, allowed, "sshReverseLocalPort");
        copyIfPresent(updates, allowed, "sshReverseRemotePort");
        copyIfPresent(updates, allowed, "sshUsername");
        copyIfPresent(updates, allowed, "sshPassword");
        copyIfPresent(updates, allowed, "sshKeyFile");
        configService.updateConfig(allowed);
        return socks5TunnelConfigJson();
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String socks5TunnelConfigJson() {
        Map<String, Object> config = configService.toMap();
        return "{\"sshReverseTunnelEnabled\":" + config.getOrDefault("sshReverseTunnelEnabled", false)
                + ",\"sshRemoteHost\":\"" + escapeJson(String.valueOf(config.getOrDefault("sshRemoteHost", ""))) + "\""
                + ",\"sshRemotePort\":" + config.getOrDefault("sshRemotePort", 22)
                + ",\"sshReverseLocalPort\":" + config.getOrDefault("sshReverseLocalPort", 0)
                + ",\"sshReverseRemotePort\":" + config.getOrDefault("sshReverseRemotePort", 0)
                + ",\"sshUsername\":\"" + escapeJson(String.valueOf(config.getOrDefault("sshUsername", ""))) + "\""
                + ",\"sshKeyFile\":\"" + escapeJson(String.valueOf(config.getOrDefault("sshKeyFile", ""))) + "\""
                + ",\"restartRequired\":true}";
    }

    private String handleSocks5Bootstrap(ChannelHandlerContext ctx, FullHttpRequest req, String uri) throws Exception {
        if (socks5AgentBootstrapManager == null) {
            sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "{\"error\":\"socks5 bootstrap manager not available\"}");
            return null;
        }
        Map<String, Object> body = java.util.Collections.emptyMap();
        if (req.method() == HttpMethod.POST && req.content().isReadable()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(req.content().toString(CharsetUtil.UTF_8), Map.class);
            body = parsed;
        }
        String managementToken = firstString(body, "managementToken", "token");
        if (managementToken == null || managementToken.isBlank()) {
            managementToken = TokenVerifier.extractToken(req);
        }
        if (configService == null || !configService.verifyToken(managementToken)) {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, "{\"error\":\"invalid managementToken\"}");
            return null;
        }
        if (match(uri, adminPath, "/api/socks5/bootstrap") || "/api/socks5/bootstrap".equals(uri)) {
            if (req.method() == HttpMethod.GET) {
                String token = managementToken;
                return mapper.writeValueAsString(socks5AgentBootstrapManager.list().values().stream()
                        .filter(item -> configService.canAccessAgent(token, item.agentId()))
                        .toList());
            }
            if (req.method() != HttpMethod.POST) {
                sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                return null;
            }
            String agentId = firstString(body, "agentId", "id");
            if (agentId.isBlank()) {
                agentId = "socks5-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }
            if (!configService.canAccessAgent(managementToken, agentId)) {
                sendJson(ctx, HttpResponseStatus.FORBIDDEN, "{\"error\":\"token cannot access agent\"}");
                return null;
            }
            boolean forceUpdate = firstBoolean(body, false, "forceUpdate", "force_update");
            Socks5AgentBootstrapManager.BootstrapRequest request =
                    new Socks5AgentBootstrapManager.BootstrapRequest(
                            firstString(body, "sshHost", "sshRemoteHost"),
                            firstInt(body, 22, "sshPort", "sshRemotePort"),
                            firstString(body, "sshUsername", "username"),
                            firstString(body, "sshPassword", "password"),
                            firstString(body, "sshKeyFile", "keyFile"),
                            agentId,
                            firstString(body, "verifyCode", "verify_code"),
                            managementToken,
                            firstString(body, "remoteBaseDir", "agentRemoteBaseDir"),
                            firstInt(body, 0, "remoteListenPort", "sshReverseRemotePort"),
                            firstInt(body, 0, "remotePortStart"),
                            firstInt(body, 0, "remotePortEnd"),
                            firstInt(body, 0, "localAgentPort", "sshReverseLocalPort"),
                            firstString(body, "agentJarPath"),
                            firstString(body, "javaBin"),
                            forceUpdate
                    );
            Socks5AgentBootstrapManager.BootstrapResult result = socks5AgentBootstrapManager.bootstrap(request);
            return mapper.writeValueAsString(result);
        }
        if (uri.matches(".*(/admin)?/api/socks5/bootstrap/[^/]+")) {
            if (req.method() != HttpMethod.DELETE) {
                sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                return null;
            }
            String prefix = uri.startsWith(adminPath) ? adminPath + "/api/socks5/bootstrap/" : "/api/socks5/bootstrap/";
            String agentId = java.net.URLDecoder.decode(uri.substring(prefix.length()), StandardCharsets.UTF_8);
            if (!configService.canAccessAgent(managementToken, agentId)) {
                sendJson(ctx, HttpResponseStatus.FORBIDDEN, "{\"error\":\"token cannot access agent\"}");
                return null;
            }
            boolean stopped = socks5AgentBootstrapManager.stop(agentId);
            return "{\"agentId\":\"" + escapeJson(agentId) + "\",\"stopped\":" + stopped + "}";
        }
        sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}");
        return null;
    }

    private boolean isSocks5BootstrapRoute(String uri) {
        return match(uri, adminPath, "/api/socks5/bootstrap") || "/api/socks5/bootstrap".equals(uri)
                || uri.matches(".*(/admin)?/api/socks5/bootstrap/[^/]+");
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                String text = String.valueOf(value);
                if (!text.isBlank()) { return text; }
            }
        }
        return "";
    }

    private static int firstInt(Map<String, Object> map, int fallback, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                try {
                    return Integer.parseInt(String.valueOf(value));
                }
 catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    private static boolean firstBoolean(Map<String, Object> map, boolean fallback, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                String text = String.valueOf(value).trim();
                if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) {
                    return false;
                }
            }
        }
        return fallback;
    }

    private String handleSocks5Clients(ChannelHandlerContext ctx, FullHttpRequest req, String uri) {
        if (socks5TunnelManager == null) {
            sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "{\"error\":\"socks5 tunnel manager not available\"}");
            return null;
        }
        if (match(uri, adminPath, "/api/socks5/clients") || "/api/socks5/clients".equals(uri)) {
            if (req.method() != HttpMethod.GET) {
                sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                return null;
            }
            return socks5ClientsJson(req);
        }
        if (req.method() != HttpMethod.DELETE) {
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
            return null;
        }
        String prefix = uri.startsWith(adminPath) ? adminPath + "/api/socks5/clients/" : "/api/socks5/clients/";
        if (!uri.startsWith(prefix)) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"client not found\"}");
            return null;
        }
        String streamId = java.net.URLDecoder.decode(uri.substring(prefix.length()), StandardCharsets.UTF_8);
        ReverseSocks5TunnelManager.ClientInfo client = socks5TunnelManager.listClients().stream()
                .filter(item -> item.streamId().equals(streamId))
                .findFirst()
                .orElse(null);
        if (client == null) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"client not found\"}");
            return null;
        }
        if (!canAccessSocks5Agent(req, client.agentId())) {
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, "{\"error\":\"forbidden\"}");
            return null;
        }
        boolean disconnected = socks5TunnelManager.disconnectClient(streamId, "manual_disconnect");
        return "{\"streamId\":\"" + escapeJson(streamId) + "\",\"disconnected\":" + disconnected + "}";
    }

    private String socks5ClientsJson(FullHttpRequest req) {
        StringBuilder json = new StringBuilder("{\"clients\":[");
        boolean first = true;
        for (ReverseSocks5TunnelManager.ClientInfo client : socks5TunnelManager.listClients()) {
            if (!canAccessSocks5Agent(req, client.agentId())) {
                continue;
            }
            if (!first) { json.append(","); }
            json.append("{\"streamId\":\"").append(escapeJson(client.streamId())).append("\"")
                    .append(",\"targetId\":\"").append(escapeJson(client.targetId())).append("\"")
                    .append(",\"agentId\":\"").append(escapeJson(client.agentId())).append("\"")
                    .append(",\"verifyCode\":\"").append(escapeJson(client.verifyCode())).append("\"")
                    .append(",\"agentIpAddress\":\"").append(escapeJson(client.agentIpAddress())).append("\"")
                    .append(",\"agentRemoteAddress\":\"").append(escapeJson(client.agentRemoteAddress())).append("\"")
                    .append(",\"clientAddress\":\"").append(escapeJson(client.clientAddress())).append("\"")
                    .append(",\"targetHost\":\"").append(escapeJson(client.targetHost())).append("\"")
                    .append(",\"targetPort\":").append(client.targetPort())
                    .append(",\"createdAt\":\"").append(client.createdAt()).append("\"")
                    .append(",\"connected\":").append(client.connected())
                    .append(",\"tunnelEnabled\":").append(client.tunnelEnabled())
                    .append("}");
            first = false;
        }
        return json.append("]}").toString();
    }

    private String handleSocks5Agents(FullHttpRequest req) {
        if (agentRegistry == null) {
            return "{\"socks5GatewayPort\":1080,\"agents\":[]}";
        }
        Object port = configService != null ? configService.toMap().getOrDefault("socks5GatewayPort", 1080) : 1080;
        StringBuilder json = new StringBuilder("{\"socks5GatewayPort\":").append(port).append(",\"agents\":[");
        boolean first = true;
        for (AgentInfo agent : agentRegistry.allAgents()) {
            if (!supportsSocks5(agent) || agent.getVerifyCode() == null || agent.getVerifyCode().isBlank()) {
                continue;
            }
            if (!canAccessSocks5Agent(req, agent.getAgentId())) {
                continue;
            }
            if (!first) { json.append(","); }
            appendSocks5Agent(json, agent);
            first = false;
        }
        return json.append("]}").toString();
    }

    private String handleSocks5Lifecycle(ChannelHandlerContext ctx, FullHttpRequest req, String uri) {
        if (req.method() != HttpMethod.POST) {
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
            return null;
        }
        if (agentRegistry == null) {
            sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "{\"error\":\"agent registry not available\"}");
            return null;
        }
        String[] parts = uri.split("/");
        String action = parts[parts.length - 1];
        String agentId = java.net.URLDecoder.decode(parts[parts.length - 2], java.nio.charset.StandardCharsets.UTF_8);
        AgentInfo agent = agentRegistry.getAgent(agentId);
        if (agent == null) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"agent not found\"}");
            return null;
        }
        if (!supportsSocks5(agent)) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"agent does not support socks5\"}");
            return null;
        }
        if (!canAccessSocks5Agent(req, agentId)) {
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, "{\"error\":\"forbidden\"}");
            return null;
        }
        boolean enabled = "start".equals(action);
        agentRegistry.setSocks5AccessEnabled(agentId, enabled);
        return "{\"agentId\":\"" + escapeJson(agentId) + "\",\"socks5Enabled\":" + enabled + "}";
    }

    private boolean isSocks5LifecycleRoute(String uri) {
        return uri.matches(".*(/admin)?/api/socks5/agents/[^/]+/(start|stop)");
    }

    private boolean canAccessSocks5Agent(FullHttpRequest req, String agentId) {
        if (configService == null) { return true; }
        String token = TokenVerifier.extractToken(req);
        return configService.canAccessAgent(token, agentId);
    }

    private boolean supportsSocks5(AgentInfo agent) {
        return agent != null && agent.getProtocols() != null
                && agent.getProtocols().stream().anyMatch(protocol -> "SOCKS5".equalsIgnoreCase(protocol));
    }

    private void appendSocks5Agent(StringBuilder json, AgentInfo agent) {
        json.append("{\"agentId\":\"").append(escapeJson(agent.getAgentId())).append("\"")
                .append(",\"online\":").append(agent.isOnline())
                .append(",\"socks5Enabled\":").append(agentRegistry.isSocks5AccessEnabled(agent.getAgentId()))
                .append(",\"agentType\":\"").append(escapeJson(agent.getAgentType())).append("\"")
                .append(",\"ipAddress\":\"").append(escapeJson(agent.getIpAddress() != null ? agent.getIpAddress() : "unknown")).append("\"")
                .append(",\"remotePort\":").append(agent.getRemotePort())
                .append(",\"verifyCode\":\"").append(escapeJson(agent.getVerifyCode())).append("\"")
                .append(",\"lastHeartbeatAt\":\"").append(agent.getLastHeartbeatAt() != null ? agent.getLastHeartbeatAt().toString() : "").append("\"")
                .append(",\"lastRttMs\":").append(agent.getLastRttMs())
                .append(",\"protocols\":[");
        if (agent.getProtocols() != null) {
            for (int i = 0; i < agent.getProtocols().size(); i++) {
                if (i > 0) { json.append(","); }
                json.append("\"").append(escapeJson(agent.getProtocols().get(i))).append("\"");
            }
        }
        json.append("]").append(",\"capabilities\":{");
        if (agent.getCapabilities() != null) {
            boolean first = true;
            for (var entry : agent.getCapabilities().entrySet()) {
                if (!first) { json.append(","); }
                json.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                        .append(escapeJson(entry.getValue())).append("\"");
                first = false;
            }
        }
        json.append("}}");
    }

    /**
     * 发送 JSON 格式的 HTTP 响应
     *
     * @param ctx    Channel 上下文
     * @param status HTTP 状态码
     * @param json   JSON 字符串
     */
    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        ByteBuf buf = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        resp.headers().set(CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
        setCorsHeaders(resp.headers());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 设置 CORS 跨域响应头，允许任意来源跨域访问
     *
     * @param headers HTTP 响应头
     */
    private static void setCorsHeaders(HttpHeaders headers) {
        headers.set(CORS_HEADER_ORIGIN, "*");
        headers.set(CORS_HEADER_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        headers.set(CORS_HEADER_HEADERS, "Content-Type, Authorization, X-Requested-With");
    }

    // ==================== 代理服务器管理 ====================

    /**
     * 处理代理服务器 CRUD 请求
     * <ul>
     *   <li>GET - 列出所有代理实例</li>
     *   <li>POST - 创建新的代理实例</li>
     *   <li>DELETE - 删除指定的代理实例</li>
     * </ul>
     *
     * @param ctx    Channel 上下文
     * @param req    完整的 HTTP 请求
     * @param rawUri 原始 URI（含查询参数）
     * @param qIdx   查询参数起始位置索引
     * @return JSON 响应字符串，如果请求已被处理（发送了错误响应）则返回 null
     * @throws Exception JSON 解析或 IO 异常
     */
    private String handleProxyRoutes(ChannelHandlerContext ctx, FullHttpRequest req, String rawUri, int qIdx) throws Exception {
        if (req.method() == HttpMethod.GET) {
            // GET /admin/api/proxies — 列出所有代理
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (ProxyInstance p : proxyInstances.values()) {
                if (!first) { sb.append(","); }
                sb.append("{\"id\":\"").append(escapeJson(p.id))
                  .append("\",\"name\":\"").append(escapeJson(p.name))
                  .append("\",\"type\":\"").append(escapeJson(p.type))
                  .append("\",\"port\":").append(p.port)
                  .append(",\"targetHost\":\"").append(escapeJson(p.targetHost))
                  .append("\",\"targetPort\":").append(p.targetPort)
                  .append(",\"running\":").append(p.running).append("}");
                first = false;
            }
            sb.append("]");
            return sb.toString();
        } else if (req.method() == HttpMethod.POST) {
            // POST /admin/api/proxies — 创建代理
            String pBody = req.content().toString(CharsetUtil.UTF_8);
            @SuppressWarnings("unchecked")
            var map = mapper.readValue(pBody, Map.class);
            String name = (String) map.getOrDefault("name", "proxy-" + proxyInstances.size());
            String type = (String) map.getOrDefault("type", "netty-socks5");
            int port = map.containsKey("port") ? ((Number) map.get("port")).intValue() : 1080;
            String targetHost = (String) map.getOrDefault("targetHost", "0.0.0.0");
            int targetPort = map.containsKey("targetPort") ? ((Number) map.get("targetPort")).intValue() : 0;

            String id = UUID.randomUUID().toString().substring(0, 8);
            ProxyInstance inst = new ProxyInstance(id, name, type, port, targetHost, targetPort);
            proxyInstances.put(id, inst);
            log.info("[Admin] 创建代理: id={} type={} port={} target={}:{}", id, type, port, targetHost, targetPort);

            return "{\"id\":\"" + escapeJson(id) + "\",\"name\":\"" + escapeJson(name) + "\",\"type\":\"" + escapeJson(type)
                  + "\",\"port\":" + port + ",\"targetHost\":\"" + escapeJson(targetHost) + "\",\"targetPort\":" + targetPort + ",\"running\":false}";
        } else if (req.method() == HttpMethod.DELETE) {
            // DELETE /admin/api/proxies?id=xxx — 删除代理
            String id = extractParam(rawUri, "id");
            if (id == null || id.isEmpty()) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"error\":\"missing id\"}");
                return null;
            }
            ProxyInstance inst = proxyInstances.remove(id);
            if (inst == null) {
                sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"proxy not found\"}");
                return null;
            }
            if (inst.server != null) {
                try { inst.server.stop(); }
 catch (Exception e) { log.debug("停止代理服务器失败", e); }
            }
            return "{\"deleted\":true,\"id\":\"" + escapeJson(id) + "\"}";
        }
        sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
        return null;
    }

    /**
     * 处理代理服务器生命周期请求（启动/停止）
     *
     * @param ctx Channel 上下文
     * @param req 完整的 HTTP 请求
     * @param uri 请求 URI，格式为 .../proxies/{id}/start 或 .../proxies/{id}/stop
     * @return JSON 响应字符串，如果请求已被处理则返回 null
     * @throws Exception 协议服务器创建或启动异常
     */
    private String handleProxyLifecycle(ChannelHandlerContext ctx, FullHttpRequest req, String uri) throws Exception {
        if (req.method() != HttpMethod.POST) {
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
            return null;
        }
        // Extract id and action from URI: .../proxies/{id}/start or .../proxies/{id}/stop
        String[] parts = uri.split("/");
        String action = parts[parts.length - 1];
        String id = parts[parts.length - 2];

        ProxyInstance inst = proxyInstances.get(id);
        if (inst == null) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"proxy not found\"}");
            return null;
        }

        switch (action) {
            case "start" -> {
                if (inst.running) { return "{\"id\":\"" + escapeJson(id) + "\",\"status\":\"already_running\"}"; }
                try {
                    ServerSetting setting = ServerSetting.builder()
                            .protocol(inst.type)
                            .port(inst.port)
                            .host("0.0.0.0")
                            .build();
                    inst.server = ProtocolServerUtils.openProxyServer(inst.type, setting);
                    if (inst.server == null) {
                        sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                "{\"error\":\"未找到协议实现: " + escapeJson(inst.type) + "\"}");
                        return null;
                    }
                    inst.running = true;
                    log.info("[Admin] 启动代理: id={} type={} port={}", id, inst.type, inst.port);
                    return "{\"id\":\"" + escapeJson(id) + "\",\"status\":\"started\"}";
                }
 catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("[Admin] 启动代理失败: id={} error={}", id, cause.toString());
                    sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"启动失败: " + escapeJson(cause.toString()) + "\"}");
                    return null;
                }
            }
            case "stop" -> {
                if (!inst.running) { return "{\"id\":\"" + escapeJson(id) + "\",\"status\":\"already_stopped\"}"; }
                try {
                    if (inst.server != null) { inst.server.stop(); }
                    inst.running = false;
                    inst.server = null;
                    log.info("[Admin] 停止代理: id={}", id);
                    return "{\"id\":\"" + escapeJson(id) + "\",\"status\":\"stopped\"}";
                }
 catch (Exception e) {
                    log.warn("[Admin] 停止代理失败: id={} error={}", id, e.getMessage());
                    sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"停止失败: " + escapeJson(e.getMessage()) + "\"}");
                    return null;
                }
            }
        }
        sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"unknown action\"}");
        return null;
    }

    /**
     * 获取可用的代理服务器类型列表（通过 SPI 发现）
     * <p>
     * 仅返回名称包含 socks、proxy 或 tcp 的协议服务器实现。
     *
     * @return 代理类型名称的 JSON 数组
     */
    private String handleProxyTypes() {
        return ProtocolServerUtils.proxyProtocolTypesJson();
    }

    /**
     * 获取网关相关的 SPI 提供商信息
     * <p>
     * 返回 GatewayConfigStore、GatewayTokenVerifier、ProtocolServer
     * 三个接口的所有 SPI 实现列表。
     *
     * @return SPI 提供商信息的 JSON 字符串
     */
    private String handleSpiProviders() {
        return ProtocolServerUtils.gatewaySpiProvidersJson();
    }
}
