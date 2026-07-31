package com.chua.remote.support.gateway.transport.http;

import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayConfigService;
import com.chua.remote.support.gateway.config.Protocol;
import com.chua.remote.support.gateway.core.auth.FileBasedTokenVerifier;
import com.chua.remote.support.gateway.core.auth.TokenVerifier;
import com.chua.remote.support.gateway.core.firewall.GatewayFirewall;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetEntry;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.GatewaySession;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.core.session.SessionStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * HTTP API 网关处理器
 * 独立端口运行，支持自定义 path 前缀（默认 /api）
 * 提供 RESTful 接口操作 Agent/Target/Session

 * @author CH
 */@Slf4j
public class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /** 目标注册表，维护所有注册的 Agent 和 Target */
    private final TargetRegistry targetRegistry;
    /** API 代理转发链 */
    private final ApiGatewayProxyFilterForwarder proxyForwarder;
    /** 网关限流器 */
    private final GatewayRateLimiter rateLimiter;
    /** API 端点的 URL 路径前缀 */
    /**
     * API 路径
     */
    private final String apiPath;
    /** 会话管理器，管理所有活跃的网关会话 */
    private final SessionManager sessionManager;
    /** 网关配置服务 */
    private final GatewayConfigService configService;
    /** 网关防火墙，提供 IP 过滤与访问日志 */
    private final GatewayFirewall firewall;
    /** Agent 注册表 */
    private AgentRegistry agentRegistry;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** CORS 响应头常量：允许的来源 */
    private static final String CORS_ORIGIN = "Access-Control-Allow-Origin";
    /** CORS 响应头常量：允许的 HTTP 方法 */
    private static final String CORS_METHODS = "Access-Control-Allow-Methods";
    /** CORS 响应头常量：允许的请求头 */
    private static final String CORS_HEADERS = "Access-Control-Allow-Headers";

    /**
     * 构造一个 API 请求处理器（不含会话管理、配置服务和防火墙）
     *
     * @param tr      目标注册表
     * @param rl      网关限流器
     * @param apiPath API 端点路径前缀
     */
    public HttpApiHandler(TargetRegistry tr, GatewayRateLimiter rl, String apiPath) {
        this(tr, rl, apiPath, null, null, null);
    }

    /**
     * 构造一个 API 请求处理器（含会话管理和配置服务）
     *
     * @param tr      目标注册表
     * @param rl      网关限流器
     * @param apiPath API 端点路径前缀
     * @param sm      会话管理器
     * @param cs      网关配置服务
     */
    public HttpApiHandler(TargetRegistry tr, GatewayRateLimiter rl, String apiPath,
                           SessionManager sm, GatewayConfigService cs) {
        this(tr, rl, apiPath, sm, cs, null);
    }

    /**
     * 构造一个完整的 API 请求处理器
     *
     * @param tr      目标注册表
     * @param rl      网关限流器
     * @param apiPath API 端点路径前缀
     * @param sm      会话管理器
     * @param cs      网关配置服务
     * @param fw      网关防火墙
     */
    public HttpApiHandler(TargetRegistry tr, GatewayRateLimiter rl, String apiPath,
                           SessionManager sm, GatewayConfigService cs, GatewayFirewall fw) {
        this.targetRegistry = tr;
        this.proxyForwarder = new ApiGatewayProxyFilterForwarder(tr);
        this.rateLimiter = rl;
        this.apiPath = apiPath;
        this.sessionManager = sm;
        this.configService = cs;
        this.firewall = fw;
    }

    /**
     * 设置 Agent 注册表（链式调用）
     *
     * @param ar Agent 注册表
     * @return 当前处理器实例
     */
    public HttpApiHandler withAgentRegistry(AgentRegistry ar) {
        this.agentRegistry = ar;
        return this;
    }

    /**
     * 接收并处理 API 端口的 HTTP 请求
     * <p>
     * 提供以下 RESTful API 端点：
     * <ul>
     *   <li>/health, /healthz - 健康检查</li>
     *   <li>/stats - 统计信息</li>
     *   <li>/targets, /targets/detail - 目标列表与详情</li>
     *   <li>/agents - 在线 Agent 列表</li>
     *   <li>/config - 配置查询与更新 (GET/PUT)</li>
     *   <li>/sessions - 会话列表与统计</li>
     *   <li>/token/verify - Token 验证</li>
     *   <li>/tokens - 令牌管理 (GET/POST/PUT/DELETE)</li>
     *   <li>/targets/protocol/{protocol} - 按协议查询目标</li>
     *   <li>/sessions/{id} - 单个会话查询与关闭</li>
     * </ul>
     *
     * @param ctx Channel 上下文
     * @param req 完整的 HTTP 请求
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            sendJson(ctx, BAD_REQUEST, "{\"error\":\"bad request\"}");
            return;
        }

        // CORS 预检
        if (req.method() == HttpMethod.OPTIONS) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
            setCorsHeaders(resp.headers());
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            String rawUri = req.uri();

            // 防火墙检查: IP 过滤 + 限流
            if (firewall != null) {
                FullHttpResponse blockResp = firewall.check(ctx, req);
                if (blockResp != null) {
                    ctx.writeAndFlush(blockResp).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
            }

            // 去掉 apiPath 前缀，得到路由路径
            String uri = stripPrefix(rawUri, apiPath);
            if (uri == null) { uri = rawUri; }
            // 去掉 query string（仅用于路由匹配）
            int qIdx = uri.indexOf('?');
            String route = qIdx >= 0 ? uri.substring(0, qIdx) : uri;

            // Token 验证（health 和 token/verify 豁免）
            if (configService != null && configService.isApiTokenEnabled()) {
                if (!isExemptRoute(route)) {
                    if (!TokenVerifier.requireToken(ctx, req, configService)) { return; }
                }
            }

            HttpMethod method = req.method();
            String body;

            switch (route) {
                // ===== 健康检查 =====
                case "/health":
                case "/healthz":
                    body = "{\"status\":\"UP\",\"agents\":" + targetRegistry.agentCount()
                            + ",\"targets\":" + targetRegistry.targetCount() + "}";
                    sendJson(ctx, OK, body);
                    break;

                // ===== 统计 =====
                case "/stats":
                    body = "{\"agents\":" + targetRegistry.agentCount()
                            + ",\"targets\":" + targetRegistry.targetCount() + "}";
                    sendJson(ctx, OK, body);
                    break;

                // ===== 目标列表 =====
                case "/targets":
                    List<TargetEntry> all = targetRegistry.allTargets();
                    body = toTargetsJson(all, false);
                    sendJson(ctx, OK, body);
                    break;

                // ===== 目标详情 =====
                case "/targets/detail":
                    List<TargetEntry> detailed = targetRegistry.allTargets();
                    body = toTargetsJson(detailed, true);
                    sendJson(ctx, OK, body);
                    break;

                // ===== 在线 Agent =====
                case "/agents":
                    List<AgentInfo> agents = targetRegistry.onlineAgents();
                    StringBuilder ja = new StringBuilder("[");
                    for (int i = 0; i < agents.size(); i++) {
                        AgentInfo a = agents.get(i);
                        if (i > 0) { ja.append(","); }
                        ja.append("{\"agentId\":\"").append(a.getAgentId())
                                .append("\",\"online\":").append(a.isOnline())
                                .append(",\"agentType\":\"").append(escapeJson(a.getAgentType() != null ? a.getAgentType() : "unknown"))
                                .append("\",\"protocols\":[\"")
                                .append(a.getProtocols() != null ? String.join("\",\"", a.getProtocols()) : "")
                                .append("\"],\"transports\":[\"")
                                .append(a.getTransports() != null ? String.join("\",\"", a.getTransports()) : "TCP")
                                .append("\"],\"codecs\":[\"")
                                .append(a.getCodecs() != null ? String.join("\",\"", a.getCodecs()) : "")
                                .append("\"]");
                        // capabilities
                        ja.append(",\"capabilities\":{");
                        if (a.getCapabilities() != null) {
                            boolean first = true;
                            for (var entry : a.getCapabilities().entrySet()) {
                                if (!first) { ja.append(","); }
                                ja.append("\"").append(entry.getKey()).append("\":\"")
                                        .append(escapeJson(entry.getValue())).append("\"");
                                first = false;
                            }
                        }
                        ja.append("}");
                        // ipAddress + remotePort + verifyCode
                        ja.append(",\"ipAddress\":\"").append(escapeJson(a.getIpAddress() != null ? a.getIpAddress() : "unknown")).append("\"");
                        ja.append(",\"remotePort\":").append(a.getRemotePort());
                        ja.append(",\"verifyCode\":\"").append(escapeJson(a.getVerifyCode() != null ? a.getVerifyCode() : "")).append("\"");
                        ja.append(",\"weight\":").append(a.getWeight());
                        ja.append(",\"strategy\":\"").append(escapeJson(a.getStrategy())).append("\"");
                        ja.append(",\"apiPath\":\"").append(escapeJson(a.getApiPath())).append("\"");
                        ja.append(",\"disabled\":").append(agentRegistry != null && agentRegistry.isDisabled(a.getAgentId()));
                        ja.append(",\"lastHeartbeatAt\":\"").append(a.getLastHeartbeatAt() != null ? a.getLastHeartbeatAt().toString() : "").append("\"");
                        ja.append(",\"lastRttMs\":").append(a.getLastRttMs());
                        ja.append("}");
                    }
                    ja.append("]");
                    body = ja.toString();
                    sendJson(ctx, OK, body);
                    break;

                // ===== 配置管理 =====
                case "/config":
                    if (configService == null) {
                        sendJson(ctx, SERVICE_UNAVAILABLE, "{\"error\":\"config service not available\"}");
                    } else if (method == HttpMethod.GET) {
                        body = mapper.writeValueAsString(configService.toMap());
                        sendJson(ctx, OK, body);
                    } else if (method == HttpMethod.PUT) {
                        String putBody = req.content().toString(CharsetUtil.UTF_8);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> updates = mapper.readValue(putBody, Map.class);
                        Map<String, Object> result = configService.updateConfig(updates);
                        body = mapper.writeValueAsString(result);
                        sendJson(ctx, OK, body);
                    }
 else {
                        sendJson(ctx, METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                    }
                    break;

                // ===== 会话列表 + 统计 =====
                case "/sessions":
                    if (sessionManager == null) {
                        sendJson(ctx, SERVICE_UNAVAILABLE, "{\"error\":\"session manager not available\"}");
                    }
 else {
                        body = toJsonSessionsList();
                        sendJson(ctx, OK, body);
                    }
                    break;

                // ===== Token 验证 =====
                case "/token/verify": {
                    String query = rawUri.contains("?") ? rawUri.substring(rawUri.indexOf('?') + 1) : "";
                    String token = "";
                    for (String param : query.split("&")) {
                        if (param.startsWith("token=")) { token = param.substring(6); break; }
                    }
                    boolean valid = configService != null && configService.verifyToken(token);
                    sendJson(ctx, OK, "{\"valid\":" + valid + "}");
                    break;
                }

                // ===== 令牌管理（SPI） =====
                case "/tokens":
                    if (configService == null) {
                        sendJson(ctx, SERVICE_UNAVAILABLE, "{\"error\":\"config service not available\"}");
                    } else if (method == HttpMethod.GET) {
                        Map<String, ? extends com.chua.remote.support.spi.GatewayTokenVerifier.TokenAuth> tokens = configService.getTokenVerifier().listTokens();
                        StringBuilder tb = new StringBuilder("[");
                        boolean first = true;
                        for (var ta : tokens.values()) {
                            if (!first) { tb.append(","); }
                            tb.append("{\"token\":\"").append(escapeJson(ta.getToken()));
                            tb.append("\",\"userId\":\"").append(escapeJson(ta.getUserId()));
                            tb.append("\",\"displayName\":\"").append(escapeJson(ta.getDisplayName()));
                            tb.append("\",\"accessibleAgentIds\":");
                            tb.append(ta.getAccessibleAgentIds() != null ? mapper.writeValueAsString(ta.getAccessibleAgentIds()) : "null");
                            tb.append(",\"accessibleTargetIds\":");
                            tb.append(ta.getAccessibleTargetIds() != null ? mapper.writeValueAsString(ta.getAccessibleTargetIds()) : "null");
                            tb.append(",\"expiresAt\":");
                            tb.append(ta.getExpiresAt() != null ? "\"" + escapeJson(ta.getExpiresAt()) + "\"" : "null");
                            tb.append("}");
                            first = false;
                        }
                        tb.append("]");
                        sendJson(ctx, OK, tb.toString());
                    } else if (method == HttpMethod.POST) {
                        String postBody = req.content().toString(CharsetUtil.UTF_8);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> tokenData = mapper.readValue(postBody, Map.class);
                        String userId = (String) tokenData.getOrDefault("userId", "user");
                        String displayName = (String) tokenData.getOrDefault("displayName", userId);
                        @SuppressWarnings("unchecked")
                        List<String> agentIds = (List<String>) tokenData.get("accessibleAgentIds");
                        @SuppressWarnings("unchecked")
                        List<String> targetIds = (List<String>) tokenData.get("accessibleTargetIds");
                        String expiresAt = (String) tokenData.get("expiresAt");
                        FileBasedTokenVerifier.TokenAuthImpl auth = new FileBasedTokenVerifier.TokenAuthImpl(
                                null, userId, displayName, agentIds, targetIds);
                        if (expiresAt != null && !expiresAt.isEmpty()) {
                            try { auth.setExpiresAt(java.time.Instant.parse(expiresAt)); }
 catch (Exception ignored) { log.warn("解析过期时间失败", ignored); }
                        }
                        String newToken = configService.getTokenVerifier().createToken(auth);
                        sendJson(ctx, OK, "{\"token\":\"" + newToken + "\",\"userId\":\"" + escapeJson(userId) + "\"}");
                    } else if (method == HttpMethod.PUT) {
                        // PUT /tokens?target=xxx — 编辑已有令牌
                        String editQuery = rawUri.contains("?") ? rawUri.substring(rawUri.indexOf('?') + 1) : "";
                        String editToken = "";
                        for (String param : editQuery.split("&")) {
                            if (param.startsWith("target=")) { editToken = java.net.URLDecoder.decode(param.substring(7), "UTF-8"); break; }
                        }
                        if (editToken.isEmpty()) {
                            sendJson(ctx, BAD_REQUEST, "{\"error\":\"missing target token\"}");
                        }
 else {
                            String putBody = req.content().toString(CharsetUtil.UTF_8);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> editData = mapper.readValue(putBody, Map.class);
                            boolean edited = configService.getTokenVerifier().editToken(editToken, editData);
                            sendJson(ctx, edited ? OK : NOT_FOUND, "{\"edited\":" + edited + "}");
                        }
                    } else if (method == HttpMethod.DELETE) {
                        // ?target=xxx 指定要删除的令牌（token= 用于认证）
                        String delQuery = rawUri.contains("?") ? rawUri.substring(rawUri.indexOf('?') + 1) : "";
                        String delToken = "";
                        for (String param : delQuery.split("&")) {
                            if (param.startsWith("target=")) { delToken = java.net.URLDecoder.decode(param.substring(7), "UTF-8"); break; }
                        }
                        if (delToken.isEmpty()) {
                            log.warn("[HttpApi] DELETE /tokens: missing target parameter, query={}", delQuery);
                            sendJson(ctx, BAD_REQUEST, "{\"error\":\"missing target token\"}");
                        }
 else {
                            log.info("[HttpApi] DELETE /tokens: target={}...", delToken.substring(0, Math.min(8, delToken.length())));
                            boolean deleted = configService.getTokenVerifier().deleteToken(delToken);
                            sendJson(ctx, deleted ? OK : NOT_FOUND, "{\"deleted\":" + deleted + "}");
                        }
                    }
 else {
                        sendJson(ctx, METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                    }
                    break;

                // ===== 按协议查询 Target + 动态路由 =====
                default:
                    if (uri.startsWith("/targets/protocol/")) {
                        String proto = uri.substring("/targets/protocol/".length()).toUpperCase();
                        try {
                            Protocol p = Protocol.valueOf(proto);
                            List<TargetEntry> filtered = targetRegistry.lookupByProtocol(p);
                            body = toTargetsJson(filtered, false);
                            sendJson(ctx, OK, body);
                        }
 catch (IllegalArgumentException e) {
                            sendJson(ctx, NOT_FOUND, "{\"error\":\"unknown protocol: " + proto + "\"}");
                        }
                    } else if (uri.startsWith("/sessions/") && sessionManager != null) {
                        String sessionId = uri.substring("/sessions/".length());
                        if (method == HttpMethod.GET) {
                            handleGetSession(ctx, sessionId);
                        } else if (method == HttpMethod.DELETE) {
                            handleDeleteSession(ctx, sessionId);
                        }
 else {
                            sendJson(ctx, METHOD_NOT_ALLOWED, "{\"error\":\"method not allowed\"}");
                        }
                    }
 else {
                        // 尝试代理转发：读取 X-Target-Id header → TargetRegistry 查找目标 → 转发请求
                        String targetId = req.headers().get("X-Target-Id");
                        if (targetId != null && !targetId.isEmpty()) {
                            forwardToTarget(ctx, req, targetId);
                        }
 else {
                            sendJson(ctx, NOT_FOUND, "{\"error\":\"not found\"}");
                        }
                    }
            }
        }
 catch (Exception e) {
            log.warn("[HttpApi] error: {}", e.getMessage());
            sendJson(ctx, INTERNAL_SERVER_ERROR, "{\"error\":\"" + e.getMessage() + "\"}");
            if (firewall != null) { firewall.recordFromRequest(ctx, req, 500, System.currentTimeMillis() - startTime); }
        }
    }

    /**
     * 判断请求路径是否为 Token 豁免路径（无需 Token 认证）
     *
     * @param route 路由路径（已去除 apiPath 前缀）
     * @return 如果是豁免路径返回 true
     */
    private static boolean isExemptRoute(String route) {
        return "/health".equals(route) || "/healthz".equals(route) || "/token/verify".equals(route);
    }

    /**
     * 去掉 URI 中的 apiPath 前缀，得到路由路径
     *
     * @param uri    原始请求 URI
     * @param prefix API 路径前缀
     * @return 去掉前缀后的路径，不匹配返回 null
     */
    private static String stripPrefix(String uri, String prefix) {
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)) { return uri; }
        if (uri.startsWith(prefix)) {
            String stripped = uri.substring(prefix.length());
            if (stripped.isEmpty()) { return "/"; }
            return stripped;
        }
        return null;
    }

    // ====== Session helper methods ======

    /**
     * 将会话列表转换为 JSON 格式，含统计摘要
     *
     * @return 会话列表与统计的 JSON 字符串
     * @throws Exception JSON 序列化异常
     */
    private String toJsonSessionsList() throws Exception {
        List<GatewaySession> sessions = sessionManager.allSessions();
        SessionStats stats = sessionManager.getStats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSessions", stats.totalSessions());
        result.put("activeSessions", stats.activeSessions());
        result.put("totalBytesSent", stats.totalBytesSent());
        result.put("totalBytesReceived", stats.totalBytesReceived());
        result.put("totalFramesTransferred", stats.totalFramesTransferred());
        result.put("sessions", sessions.stream().map(this::toSessionMap).toList());
        return mapper.writeValueAsString(result);
    }

    /**
     * 处理单个会话的 GET 请求，返回会话详情
     *
     * @param ctx       Channel 上下文
     * @param sessionId 会话 ID
     * @throws Exception JSON 序列化异常
     */
    private void handleGetSession(ChannelHandlerContext ctx, String sessionId) throws Exception {
        GatewaySession s = sessionManager.getSession(sessionId);
        if (s == null) {
            sendJson(ctx, NOT_FOUND, "{\"error\":\"session not found: " + sessionId + "\"}");
            return;
        }
        sendJson(ctx, OK, mapper.writeValueAsString(toSessionMap(s)));
    }

    /**
     * 处理单个会话的 DELETE 请求，强制关闭指定会话
     *
     * @param ctx       Channel 上下文
     * @param sessionId 会话 ID
     */
    private void handleDeleteSession(ChannelHandlerContext ctx, String sessionId) {
        boolean closed = sessionManager.forceCloseSession(sessionId);
        // 幂等：会话已关闭/不存在也返回成功
        sendJson(ctx, OK, "{\"status\":" + (closed ? "\"closed\"" : "\"already_closed\"") + ",\"sessionId\":\"" + sessionId + "\"}");
    }

    /**
     * 代理转发请求到目标 Agent 的 HTTP 服务。
     *
     * @param ctx     Channel 上下文
     * @param req     原始 HTTP 请求
     * @param targetId 目标 ID（通过 X-Target-Id header 传入）
     */
    private void forwardToTarget(ChannelHandlerContext ctx, FullHttpRequest req, String targetId) {
        proxyForwarder.forward(ctx, req, targetId);
    }

    /**
     * 将会话对象转换为 Map，用于 JSON 序列化
     *
     * @param s 网关会话
     * @return 包含会话各字段的 Map
     */
    private Map<String, Object> toSessionMap(GatewaySession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getSessionId());
        m.put("agentId", s.getAgentId());
        m.put("targetId", s.getTargetId());
        m.put("clientId", s.getClientId());
        m.put("protocol", s.getProtocol() != null ? s.getProtocol().name() : null);
        m.put("mode", s.getMode() != null ? s.getMode().name() : null);
        m.put("status", s.getStatus() != null ? s.getStatus().name() : null);
        m.put("bytesSent", s.getBytesSent());
        m.put("bytesReceived", s.getBytesReceived());
        m.put("framesTransferred", s.getFramesTransferred());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        m.put("lastActivityAt", s.getLastActivityAt() != null ? s.getLastActivityAt().toString() : null);
        return m;
    }

    /**
     * 将目标条目列表转换为 JSON 字符串
     *
     * @param targets 目标条目列表
     * @param detail  是否包含 Agent 在线状态的详细信息
     * @return JSON 数组字符串
     */
    private String toTargetsJson(List<TargetEntry> targets, boolean detail) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < targets.size(); i++) {
            TargetEntry t = targets.get(i);
            if (i > 0) { json.append(","); }
            json.append("{\"targetId\":\"").append(t.getTargetId())
                    .append("\",\"host\":\"").append(t.getHost())
                    .append("\",\"port\":").append(t.getPort())
                    .append(",\"protocol\":\"").append(t.getProtocol())
                    .append("\",\"transport\":\"").append(t.getTransport() != null ? t.getTransport() : "TCP")
                    .append("\",\"agentId\":\"").append(t.getAgentId()).append("\"");
            if (detail) {
                json.append(",\"agentOnline\":")
                        .append(t.getAgentId() != null &&
                                targetRegistry.getAgent(t.getAgentId()) != null &&
                                targetRegistry.getAgent(t.getAgentId()).isOnline());
            }
            json.append("}");
        }
        json.append("]");
        return json.toString();
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
     * 对 JSON 字符串中的特殊字符进行转义
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeJson(String s) {
        if (s == null) { return ""; }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 设置 CORS 跨域响应头
     *
     * @param headers HTTP 响应头
     */
    private static void setCorsHeaders(HttpHeaders headers) {
        headers.set(CORS_ORIGIN, "*");
        headers.set(CORS_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        headers.set(CORS_HEADERS, "Content-Type, Authorization, X-Requested-With, X-Target-Id");
    }
}
