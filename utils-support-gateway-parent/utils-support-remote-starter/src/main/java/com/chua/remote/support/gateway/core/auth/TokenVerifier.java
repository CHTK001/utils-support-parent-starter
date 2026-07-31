package com.chua.remote.support.gateway.core.auth;

import com.chua.remote.support.gateway.config.GatewayConfigService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.CharsetUtil;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * 统一 Token 验证工具。
 * <p>支持从 HTTP 请求中提取和验证访问令牌（Token），
 * 兼容 {@code Authorization: Bearer xxx} 请求头
 * 和 {@code ?token=xxx} URL 查询参数两种方式。
 * 验证失败时自动返回 401 JSON 响应并关闭连接。</p>
 *
 * @author CH
 */
public class TokenVerifier {

    /**
     * 从 HTTP 请求中提取 Token。
     * <p>优先从 {@code Authorization: Bearer xxx} 请求头中提取，
     * 未找到时从 URL 查询参数 {@code ?token=xxx} 中提取。</p>
     *
     * @param req 完整的 HTTP 请求
     * @return 提取到的 Token 字符串，不存在时返回 {@code null}
     */
    public static String extractToken(FullHttpRequest req) {
        // 1. Authorization: Bearer xxx
        CharSequence authCs = req.headers().get("Authorization");
        String auth = authCs != null ? authCs.toString() : null;
        if (auth != null && auth.startsWith("Bearer ")) {
            String t = auth.substring(7).trim();
            if (!t.isEmpty()) { return t; }
        }
        // 2. ?token=xxx
        String uri = req.uri();
        int qIdx = uri.indexOf('?');
        if (qIdx >= 0) {
            String query = uri.substring(qIdx + 1);
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    return java.net.URLDecoder.decode(param.substring(6), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /**
     * 验证 Token，失败时发送 401 响应并关闭连接。
     *
     * @param ctx           Netty 通道处理器上下文
     * @param req           完整的 HTTP 请求
     * @param configService 配置服务，用于验证 Token 有效性
     * @return true 表示验证通过，false 表示已发送 401 响应
     */
    public static boolean requireToken(ChannelHandlerContext ctx, FullHttpRequest req,
                                        GatewayConfigService configService) {
        return requireToken(ctx, req, configService, true);
    }

    /**
     * 带开关的 Token 验证。
     * <p>当 {@code enabled} 为 {@code false} 或配置服务为 {@code null} 时跳过验证直接返回通过。</p>
     *
     * @param ctx           Netty 通道处理器上下文
     * @param req           完整的 HTTP 请求
     * @param configService 配置服务，用于验证 Token 有效性
     * @param enabled       是否启用 Token 验证
     * @return true 表示验证通过或已跳过，false 表示已发送 401 响应
     */
    public static boolean requireToken(ChannelHandlerContext ctx, FullHttpRequest req,
                                        GatewayConfigService configService, boolean enabled) {
        if (!enabled) { return true; }
        if (configService == null) { return true; }

        String token = extractToken(req);
        if (configService.verifyToken(token)) { return true; }

        // 返回 401
        ByteBuf buf = Unpooled.copiedBuffer("{\"error\":\"unauthorized\",\"msg\":\"valid token required\"}", CharsetUtil.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED, buf);
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
        resp.headers().set("Access-Control-Allow-Origin", "*");
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        return false;
    }

    /**
     * 从 WebSocket 握手请求中提取 Token。
     * <p>委托给 {@link #extractToken(FullHttpRequest)}，保持语义清晰。</p>
     *
     * @param req WebSocket 握手阶段的 HTTP 请求
     * @return 提取到的 Token 字符串，不存在时返回 {@code null}
     */
    public static String extractTokenFromWsReq(FullHttpRequest req) {
        return extractToken(req);
    }
}
