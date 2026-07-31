package com.chua.remote.support.gateway.transport.ws;

import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * LiveKit WebSocket 代理处理器。
 * <p>浏览器只连接 Gateway，Gateway 通过已注册的 Agent TCP 管道把 LiveKit 信令帧发回被控端 Agent。</p>
 *
 * @author CH
 */
@Slf4j
public class LiveKitProxyHandler extends SimpleChannelInboundHandler<Object> {

    // m a p p e r
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // r e l a y s
    private static final Map<String, LiveKitProxyHandler> RELAYS = new ConcurrentHashMap<>();

    private final AgentRegistry agentRegistry;
    /**
     * LiveKit SFU 地址
     */
    private final String livekitSfuUrl;
    private final String relayId = UUID.randomUUID().toString().replace("-", "");
    private final List<byte[]> pendingPayloads = new ArrayList<>();

    private WebSocketServerHandshaker handshaker;
    private Channel wsChannel;
    private Channel agentChannel;
    private Promise<Void> readyPromise;
    private boolean handshakeComplete;
    private boolean relayReady;
    private boolean closing;

    public LiveKitProxyHandler(AgentRegistry agentRegistry, String livekitSfuUrl) {
        this.agentRegistry = agentRegistry;
        this.livekitSfuUrl = livekitSfuUrl;
    }

    /**
     * handleAgentRelayMessage
     * @param type 参数
     * @param relayId 参数
     * @param Map<String 参数
     * @param msg 参数
     * @return handleAgentRelayMessage结果
     */
    public static boolean handleAgentRelayMessage(String type, String relayId, Map<String, Object> msg) {
        LiveKitProxyHandler relay = RELAYS.get(relayId);
        if (relay == null) {
            return false;
        }
        Channel channel = relay.wsChannel;
        if (channel == null) {
            return true;
        }
        channel.eventLoop().execute(() -> relay.handleAgentMessage(type, msg));
        return true;
    }

    /**
     * channelRead0
     * @param ctx 参数
     * @param msg 参数
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        String upgrade = headerValue(req, "Upgrade");
        String connection = headerValue(req, "Connection");
        String wsKey = headerValue(req, "Sec-WebSocket-Key");
        boolean websocketUpgrade = "websocket".equalsIgnoreCase(upgrade)
                || wsKey != null
                || (connection != null && connection.toLowerCase(java.util.Locale.ROOT).contains("upgrade"))
                || (uri.contains("/rtc/v1") && !uri.contains("/rtc/v1/validate") && uri.contains("access_token="));
        if (!websocketUpgrade) {
            if (uri.contains("/rtc/v1/validate")) {
                FullHttpResponse resp = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(
                                "{\"success\":true,\"server_info\":{\"version\":\"1.12.0\",\"region\":\"\",\"node_id\":\"gateway-proxy\"}}",
                                StandardCharsets.UTF_8)
                );
                setHeader(resp, "Content-Type", "application/json");
                setHeader(resp, "Content-Length", String.valueOf(resp.content().readableBytes()));
                setHeader(resp, "Access-Control-Allow-Origin", "*");
                setHeader(resp, "Connection", "keep-alive");
                ctx.writeAndFlush(resp);
                return;
            }
            ctx.close();
            return;
        }

        QueryStringDecoder qs = new QueryStringDecoder(uri);
        String token = qs.parameters().get("access_token") != null
                ? qs.parameters().get("access_token").get(0) : "";
        String joinRequestEncoded = extractRawQueryParam(uri, "join_request");
        if (token.isEmpty()) {
            log.warn("LiveKit 代理: 缺少 access_token");
            ctx.close();
            return;
        }

        String host = headerValue(req, "Host");
        String wsUrl = "ws://" + (host != null ? host : "localhost") + req.uri();
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsUrl, null, false, 10485760);
        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }

        wsChannel = ctx.channel();
        req.retain();
        startAgentRelay(ctx, req, token, joinRequestEncoded);
    }

    private void startAgentRelay(ChannelHandlerContext ctx, FullHttpRequest req, String token, String joinRequestEncoded) {
        AgentInfo agent = agentRegistry.findAgentByProtocol(Protocol.CONFERENCE);
        if (agent == null || agent.getChannel() == null || !agent.getChannel().isActive()) {
            log.warn("LiveKit 代理: 没有在线 CONFERENCE Agent");
            req.release();
            ctx.close();
            return;
        }

        agentChannel = agent.getChannel();
        readyPromise = ctx.executor().newPromise();
        RELAYS.put(relayId, this);

        Map<String, Object> start = new java.util.LinkedHashMap<>();
        start.put("type", "livekit_relay_start");
        start.put("sessionId", relayId);
        start.put("token", token);
        start.put("livekit_url", livekitSfuUrl);
        if (!joinRequestEncoded.isEmpty()) {
            start.put("join_request", joinRequestEncoded);
        }
        writeAgentJson(start);

        readyPromise.addListener((GenericFutureListener<Future<Void>>) f -> {
            if (f.isSuccess()) {
                relayReady = true;
                handshaker.handshake(ctx.channel(), req).addListener((ChannelFutureListener) hf -> {
                    req.release();
                    if (hf.isSuccess()) {
                        handshakeComplete = true;
                        removeHttpHandlers(ctx.pipeline());
                        flushPending();
                        log.info("LiveKit 代理: WS 升级成功 relayId={} agent={}", relayId, agent.getAgentId());
                    }
 else {
                        log.warn("LiveKit 代理: WS 升级失败: {}", hf.cause().getMessage());
                        cleanup();
                    }
                });
            }
 else {
                req.release();
                log.warn("LiveKit 代理: Agent ready 失败: {}", f.cause().getMessage());
                cleanup();
                ctx.close();
            }
        });

        ctx.executor().schedule(() -> {
            if (!readyPromise.isDone()) {
                readyPromise.setFailure(new RuntimeException("Agent ready 超时"));
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            cleanup();
            if (handshaker != null) {
                handshaker.close(ctx.channel(), ((CloseWebSocketFrame) frame).retain());
            }
 else {
                ctx.close();
            }
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (!relayReady || agentChannel == null || !agentChannel.isActive()) {
            return;
        }

        ByteBuf content = frame.content();
        byte[] payload = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), payload);
        sendRelayFrame(payload);
    }

    private void handleAgentMessage(String type, Map<String, Object> msg) {
        switch (type) {
            case "livekit_relay_ready":
                if (readyPromise != null && !readyPromise.isDone()) {
                    readyPromise.setSuccess(null);
                }
                break;
            case "livekit_relay_frame": {
                String data = (String) msg.get("data");
                if (data == null) { return; }
                byte[] payload = Base64.getDecoder().decode(data);
                if (handshakeComplete && wsChannel != null && wsChannel.isActive()) {
                    wsChannel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)));
                }
 else {
                    pendingPayloads.add(payload);
                }
                break;
            }
            case "livekit_relay_closed":
                if (wsChannel != null && wsChannel.isActive()) {
                    wsChannel.writeAndFlush(new CloseWebSocketFrame());
                }
                cleanup();
                break;
            case "livekit_relay_error":
                log.warn("LiveKit 代理: Agent relay 错误 relayId={} msg={}", relayId, msg.get("msg"));
                if (readyPromise != null && !readyPromise.isDone()) {
                    readyPromise.setFailure(new RuntimeException(String.valueOf(msg.get("msg"))));
                } else if (wsChannel != null && wsChannel.isActive()) {
                    wsChannel.writeAndFlush(new CloseWebSocketFrame());
                }
                cleanup();
                break;
            default:
                log.debug("LiveKit 代理: 忽略 Agent 消息 type={} relayId={}", type, relayId);
        }
    }

    private void sendRelayFrame(byte[] payload) {
        Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("type", "livekit_relay_frame");
        msg.put("sessionId", relayId);
        msg.put("data", Base64.getEncoder().encodeToString(payload));
        writeAgentJson(msg);
    }

    private void sendRelayClose() {
        Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("type", "livekit_relay_close");
        msg.put("sessionId", relayId);
        writeAgentJson(msg);
    }

    private void writeAgentJson(Map<String, Object> msg) {
        try {
            if (agentChannel != null && agentChannel.isActive()) {
                agentChannel.writeAndFlush(Unpooled.copiedBuffer(MAPPER.writeValueAsString(msg) + "\n", StandardCharsets.UTF_8));
            }
        }
 catch (Exception e) {
            log.warn("LiveKit 代理: 写入 Agent 失败 relayId={}: {}", relayId, e.getMessage());
        }
    }

    private void flushPending() {
        if (pendingPayloads.isEmpty() || wsChannel == null || !wsChannel.isActive()) {
            return;
        }
        for (byte[] payload : pendingPayloads) {
            wsChannel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)));
        }
        pendingPayloads.clear();
    }

    private void removeHttpHandlers(ChannelPipeline pipe) {
        if (pipe.get(HttpServerCodec.class) != null) {
            pipe.remove(HttpServerCodec.class);
        }
        if (pipe.get(HttpObjectAggregator.class) != null) {
            pipe.remove(HttpObjectAggregator.class);
        }
    }

    private void cleanup() {
        if (closing) { return; }
        closing = true;
        RELAYS.remove(relayId);
        if (agentChannel != null && agentChannel.isActive()) {
            sendRelayClose();
        }
    }

    /**
     * channelInactive
     * @param ctx 参数
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cleanup();
    }

    /**
     * exceptionCaught
     * @param ctx 参数
     * @param cause 参数
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("LiveKit 代理: 异常 ({}: {})", cause.getClass().getSimpleName(), cause.getMessage());
        cleanup();
        ctx.close();
    }

    private static String headerValue(Object message, String name) {
        Object headers;
        try {
            headers = message.getClass().getMethod("headers").invoke(message);
        }
 catch (Exception e) {
            return null;
        }

        try {
            Object value;
            try {
                value = headers.getClass().getMethod("get", String.class).invoke(headers, name);
            }
 catch (NoSuchMethodException e) {
                value = headers.getClass().getMethod("get", CharSequence.class).invoke(headers, name);
            }
            if (value != null) { return value.toString(); }
        }
 catch (Exception ignored) {
        }

        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        for (String line : headers.toString().split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) { continue; }
            if (line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT).equals(lowerName)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static void setHeader(Object message, String name, String value) {
        try {
            Object headers = message.getClass().getMethod("headers").invoke(message);
            try {
                headers.getClass().getMethod("set", String.class, Object.class).invoke(headers, name, value);
            }
 catch (NoSuchMethodException e) {
                headers.getClass().getMethod("set", CharSequence.class, Object.class).invoke(headers, name, value);
            }
        }
 catch (Exception e) {
            log.debug("LiveKit 代理: 设置响应头失败 name={}", name, e);
        }
    }

    private static String extractRawQueryParam(String uri, String paramName) {
        String key = paramName + "=";
        int idx = uri.indexOf(key);
        if (idx < 0) { return ""; }
        idx += key.length();
        int end = uri.indexOf('&', idx);
        if (end < 0) { end = uri.length(); }
        return uri.substring(idx, end);
    }

    /**
     * initialPipeline
     * @return initialPipeline结果
     */
    public static ChannelHandler[] initialPipeline() {
        return new ChannelHandler[]{
                new io.netty.handler.logging.LoggingHandler(io.netty.handler.logging.LogLevel.DEBUG),
                new io.netty.handler.codec.http.HttpServerCodec(65536, 8192, 8192),
                new io.netty.handler.codec.http.HttpObjectAggregator(65536),
                new WebSocketServerCompressionHandler(),
        };
    }
}
