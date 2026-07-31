package com.chua.remote.support.gateway.core.firewall;

import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.remote.support.spi.GatewayFirewallProvider;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;
import io.netty.channel.Channel;

/**
 * 网关防火墙管理器（默认 SPI 实现）
 * 整合 IP 过滤、访问日志、限流

 * @author CH
 */@Slf4j
@SpiDefault
public class GatewayFirewall implements GatewayFirewallProvider {

    /** 访问日志管理器 */
    @Getter
    private final AccessLogManager accessLog;
    /** IP 过滤管理器（黑名单/白名单） */
    @Getter
    private final IpFilterManager ipFilter;
    /** 网关限流器 */
    private final GatewayRateLimiter rateLimiter;
    /** 限流功能是否启用 */
    private volatile boolean rateLimitEnabled = false;

    /**
     * 创建默认防火墙实例（使用默认的访问日志管理器和 IP 过滤器，不限流）
     */
    public GatewayFirewall() {
        this(new AccessLogManager(), new IpFilterManager(), null);
    }

    /**
     * 构造防火墙实例
     *
     * @param accessLog   访问日志管理器
     * @param ipFilter    IP 过滤管理器
     * @param rateLimiter 限流器（可为 null）
     */
    public GatewayFirewall(AccessLogManager accessLog, IpFilterManager ipFilter, GatewayRateLimiter rateLimiter) {
        this.accessLog = accessLog;
        this.ipFilter = ipFilter;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 检查请求是否被防火墙拦截
     * @return null 表示放行，非 null 为拦截响应
     */
    public FullHttpResponse check(ChannelHandlerContext ctx, FullHttpRequest req) {
        String ip = getClientIp(ctx);
        String uri = req.uri();
        int qIdx = uri.indexOf('?');
        String path = qIdx >= 0 ? uri.substring(0, qIdx) : uri;

        // 1. IP 过滤
        if (!ipFilter.isAllowed(ip)) {
            log.warn("[Firewall] 拦截 IP: {} path={}", ip, path);
            return createBlockResponse("IP 已被封禁");
        }

        // 2. 限流
        if (rateLimitEnabled && rateLimiter != null && !rateLimiter.tryAcquire()) {
            log.warn("[Firewall] 限流 IP: {} path={}", ip, path);
            return createRateLimitResponse();
        }

        return null;
        // 放行;
    }

    /**
     * 记录访问日志（应在请求完成后调用）
     */
    public void record(String ip, String path, String method, int statusCode, long responseTimeMs, String userAgent) {
        accessLog.log(ip, path, method, statusCode, responseTimeMs, userAgent);
    }

    /**
     * 从请求中记录访问日志
     */
    public void recordFromRequest(ChannelHandlerContext ctx, FullHttpRequest req, int statusCode, long responseTimeMs) {
        String ip = getClientIp(ctx);
        String path = req.uri();
        String method = req.method().toString();
        CharSequence ua = req.headers().get("User-Agent");
        record(ip, path, method, statusCode, responseTimeMs, ua != null ? ua.toString() : null);
    }

    /**
     * 从请求中获取客户端 IP 地址
     *
     * @param ctx Channel 上下文
     * @return 客户端 IP 字符串，无法获取时返回 "unknown"
     */
    public static String getClientIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getHostString();
        }
        return "unknown";
    }

    /** 启用/禁用限流 */
    @Override
    public void setRateLimitEnabled(boolean enabled) {
        this.rateLimitEnabled = enabled;
        log.info("[Firewall] 限流{}", enabled ? "已启用" : "已禁用");
    }

    /**
     * isRateLimitEnabled
     * @return isRateLimitEnabled结果
     */
    @Override
    public boolean isRateLimitEnabled() { return rateLimitEnabled; }

    // ===== GatewayFirewallProvider SPI =====

    /**
     * checkRequest
     * @param clientIp 参数
     * @param path 参数
     * @return checkRequest结果
     */
    @Override
    public String checkRequest(String clientIp, String path) {
        if (!ipFilter.isAllowed(clientIp)) { return "IP 已被封禁"; }
        if (rateLimitEnabled && rateLimiter != null && !rateLimiter.tryAcquire()) { return "请求过于频繁"; }
        return null;
    }

    /**
     * recordAccess
     * @param clientIp 参数
     * @param path 参数
     * @param method 参数
     * @param statusCode 参数
     * @param responseTimeMs 参数
     */
    @Override
    public void recordAccess(String clientIp, String path, String method, int statusCode, long responseTimeMs) {
        accessLog.log(clientIp, path, method, statusCode, responseTimeMs, null);
    }

    /**
     * getIpFilterMode
     * @return getIpFilterMode结果
     */
    @Override
    public String getIpFilterMode() { return ipFilter.getMode().name(); }

    /**
     * setIpFilterMode
     * @param mode 参数
     */
    @Override
    public void setIpFilterMode(String mode) { ipFilter.setMode(IpFilterManager.FilterMode.valueOf(mode)); }

    /**
     * blockIp
     * @param ip 参数
     * @param reason 参数
     */
    @Override
    public void blockIp(String ip, String reason) { ipFilter.blockIp(ip, reason); }

    /**
     * unblockIp
     * @param ip 参数
     * @return unblockIp结果
     */
    @Override
    public boolean unblockIp(String ip) { return ipFilter.unblockIp(ip); }

    /**
     * allowIp
     * @param ip 参数
     */
    @Override
    public void allowIp(String ip) { ipFilter.allowIp(ip); }

    /**
     * removeAllowedIp
     * @param ip 参数
     * @return removeAllowedIp结果
     */
    @Override
    public boolean removeAllowedIp(String ip) { return ipFilter.removeAllowedIp(ip); }

    /**
     * getBlockedList
     * @return getBlockedList结果
     */
    @Override
    public List<Map<String, Object>> getBlockedList() { return ipFilter.getBlockedList(); }

    /**
     * getAllowedList
     * @return getAllowedList结果
     */
    @Override
    public List<String> getAllowedList() { return ipFilter.getAllowedList(); }

    /**
     * getAccessStats
     * @return getAccessStats结果
     */
    @Override
    public Map<String, Object> getAccessStats() {
        Map<String, Object> stats = new LinkedHashMap<>(accessLog.getStats());
        stats.put("topIps", accessLog.getTopIps(10));
        stats.put("topPaths", accessLog.getTopPaths(10));
        return stats;
    }

    /**
     * getAccessLogs
     * @param ipFilter 参数
     * @param count 参数
     * @return getAccessLogs结果
     */
    @Override
    public List<Map<String, Object>> getAccessLogs(String ipFilter, int count) {
        List<AccessLogManager.AccessEntry> entries = (ipFilter != null && !ipFilter.isEmpty()) ?
                accessLog.getLogsByIp(ipFilter, count) : accessLog.getRecentLogs(count);
        return entries.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", e.ip);
            m.put("path", e.path);
            m.put("method", e.method);
            m.put("statusCode", e.statusCode);
            m.put("responseTimeMs", e.responseTimeMs);
            m.put("timestamp", e.timestamp.toString());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * clearAccessLogs
     */
    @Override
    public void clearAccessLogs() { accessLog.clear(); }

    /** 获取防火墙状态 */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("rateLimitEnabled", rateLimitEnabled);
        status.put("ipFilter", ipFilter.getStatus());
        status.put("accessLog", Map.of(
                "totalRequests", accessLog.getStats().get("totalRequests"),
                "logCount", accessLog.getStats().get("logCount")
        ));
        return status;
    }

    /**
     * getRealtimeMetrics
     * @return getRealtimeMetrics结果
     */
    @Override
    public Map<String, Object> getRealtimeMetrics() {
        return accessLog.getRealtimeMetrics();
    }

    /**
     * 创建 IP 封禁拦截响应（HTTP 403）
     *
     * @param message 封禁原因描述
     * @return HTTP 403 响应，JSON 格式
     */
    private FullHttpResponse createBlockResponse(String message) {
        String json = "{\"error\":\"blocked\",\"msg\":\"" + message + "\"}";
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.copiedBuffer(json, java.nio.charset.StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, buf);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
        resp.headers().set("Access-Control-Allow-Origin", "*");
        return resp;
    }

    /**
     * 创建限流拦截响应（HTTP 429）
     *
     * @return HTTP 429 响应，JSON 格式
     */
    private FullHttpResponse createRateLimitResponse() {
        String json = "{\"error\":\"rate_limited\",\"msg\":\"请求过于频繁，请稍后重试\"}";
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.copiedBuffer(json, java.nio.charset.StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.TOO_MANY_REQUESTS, buf);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
        resp.headers().set("Access-Control-Allow-Origin", "*");
        return resp;
    }
}
