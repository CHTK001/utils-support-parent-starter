package com.chua.common.support.network.server.nio;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.websocket.WebSocketProtocol;
import com.chua.common.support.network.ssl.SslUtils;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 NIO {@link ServerSocketChannel} 的 HTTP/1.1 服务器实现。
 *
 * <p>替代 {@code com.sun.net.httpserver.HttpServer}，从根本上解决单 acceptor + 无法调优的限制。
 * 使用 ServerSocketChannel（阻塞模式）accept 连接，每个连接分配一个虚拟线程处理请求，
 * 支持 HTTP/1.1 Keep-Alive 连接复用。</p>
 *
 * <p>特性：
 * <ul>
 *   <li>Selector 无关 — 阻塞 accept + 虚拟线程阻塞 I/O，简洁高效</li>
 *   <li>完全可控的 backlog / SO_REUSEADDR / TCP_NODELAY / bufferSize</li>
 *   <li>HTTP/1.1 Keep-Alive 连接复用</li>
 *   <li>SSE (Server-Sent Events) chunked transfer 流式推送</li>
 *   <li>WebSocket 升级 — 请求头携带 {@code Upgrade: websocket} 时自动切换为帧协议，
 *       支持 {@code @OnMessage} 注解方法与 {@link #onSubscribe(String, ServerHandler)} 订阅</li>
 *   <li>SSL/TLS — 自签名证书一键生成（{@code selfSignedAuto}）或 KeyStore/PEM 加载</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/12
 */
@Slf4j
@Spi({"nio", "nio-http"})
public class NioHttpServer extends AbstractServer {

    private ServerSocketChannel serverChannel;
    private ExecutorService executor;
    private SSLContext sslContext;

    /**
     * WebSocket 主题处理器映射（topic -> handlers）。
     */
    private final Map<String, List<ServerHandler>> wsTopicHandlers = new ConcurrentHashMap<>();

    /**
     * 当前活跃的 WebSocket 连接。
     */
    private final List<WsConnection> wsConnections = new CopyOnWriteArrayList<>();

    public NioHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            ServerSetting.SslConfig ssl = setting.getSsl();
            sslContext = SslUtils.autoSsl(ssl);
            if (sslContext != null) {
                log.info("NIO HttpServer SSL enabled (selfSigned={})", ssl.isSelfSigned());
            }

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, Math.max(setting.getBufferSize(), 16384));
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    Math.max(setting.getBacklog(), 4096));

            // 回填实际端口（port=0 时由系统分配）
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(bound.getPort());

            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.submit(this::acceptLoop);

            log.info("NIO HttpServer started on {}:{} (backlog={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), Math.max(setting.getBacklog(), 4096));
        } catch (Exception e) {
            throw new RuntimeException("NIO HttpServer 启动失败", e);
        }
    }

    /**
     * 接收连接循环
     */
    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel accepted = serverChannel.accept();
                if (accepted != null) {
                    accepted.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
                    if (setting.getReadTimeout() > 0) {
                        accepted.socket().setSoTimeout(setting.getReadTimeout());
                    }
                    // SSL 启用时包装为 TLS 通道（构造时完成阻塞式握手）
                    SocketChannel client = accepted;
                    if (sslContext != null) {
                        try {
                            client = new SslSocketChannel(accepted, sslContext.createSSLEngine());
                        } catch (IOException e) {
                            log.warn("TLS 握手失败，关闭连接: {}", e.getMessage());
                            closeQuietly(accepted);
                            continue;
                        }
                    }
                    SocketChannel finalClient = client;
                    executor.submit(() -> handleConnection(finalClient));
                }
            } catch (IOException e) {
                if (running) {
                    log.warn("Accept failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 处理连接：循环解析请求并响应，支持Keep-Alive，异常或结束则关闭连接
     */
    private void handleConnection(SocketChannel channel) {
        try {
            NioServerRequest request = new NioServerRequest(channel,
                    setting.getMaxRequestSize(), setting.getCharset());
            while (running && channel.isConnected()) {
                if (!request.parse()) {
                    break; // 连接关闭或解析失败
                }
                // WebSocket 升级：Upgrade: websocket 时切换为帧协议
                if (WebSocketProtocol.isUpgradeRequest(request)) {
                    handleWebSocketUpgrade(channel, request);
                    break;
                }
                NioServerResponse response = new NioServerResponse(channel);
                try {
                    handleRequest(request, response);
                } catch (Exception e) {
                    log.warn("Request handling failed: {}", e.getMessage());
                    if (!response.isCommitted()) {
                        response.sendError(500, "Internal Server Error");
                    }
                } finally {
                    response.complete();
                }
                // Keep-Alive 判断
                if (!shouldKeepAlive(request, response)) {
                    break;
                }
                request.resetForNextRequest();
            }
        } catch (Exception e) {
            log.debug("Connection handling failed: {}", e.getMessage());
        } finally {
            closeQuietly(channel);
        }
    }

    // ==================== WebSocket 支持 ====================

    /**
     * 处理 WebSocket 升级：握手后进入帧循环，按主题分发消息。
     */
    private void handleWebSocketUpgrade(SocketChannel channel, NioServerRequest request) {
        OutputStream out = null;
        try {
            String key = request.getHeader("Sec-WebSocket-Key");
            if (key == null) {
                return;
            }
            // RFC 6455 握手响应
            String accept = WebSocketProtocol.computeAccept(key);
            ByteBuffer handshake = ByteBuffer.wrap(WebSocketProtocol.handshakeResponse(accept));
            while (handshake.hasRemaining()) {
                channel.write(handshake);
            }
            InputStream in = Channels.newInputStream(channel);
            out = Channels.newOutputStream(channel);
            WsConnection conn = new WsConnection(out);
            wsConnections.add(conn);
            try {
                while (running && channel.isConnected()) {
                    WebSocketProtocol.Frame frame = WebSocketProtocol.readFrame(in);
                    if (frame == null) {
                        break; // 对端关闭
                    }
                    switch (frame.opcode()) {
                        case 0x8 -> { // close：回发关闭帧
                            out.write(WebSocketProtocol.closeFrame("bye"));
                            out.flush();
                            return;
                        }
                        case 0x9 -> { // ping -> pong
                            out.write(WebSocketProtocol.textFrame("pong"));
                            out.flush();
                        }
                        case 0x1, 0x2 -> dispatchWsMessage(frame, conn); // 文本/二进制
                        default -> { // 其他控制帧忽略
                        }
                    }
                }
            } finally {
                wsConnections.remove(conn);
            }
        } catch (IOException e) {
            log.debug("WebSocket 连接结束: {}", e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.flush();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 按主题分发 WebSocket 消息（{@code topic\nbody} 约定，与 JdkWebSocketServer 一致）。
     */
    private void dispatchWsMessage(WebSocketProtocol.Frame frame, WsConnection conn) {
        String text = new String(frame.payload(), StandardCharsets.UTF_8);
        String topic = "default";
        String body = text;
        int idx = text.indexOf('\n');
        if (idx > 0) {
            topic = text.substring(0, idx).trim();
            body = text.substring(idx + 1);
        } else if (idx == 0) {
            body = text.substring(1);
        }
        List<ServerHandler> handlers = wsTopicHandlers.get(topic);
        if (handlers == null) {
            handlers = wsTopicHandlers.get("default");
        }
        if (handlers == null) {
            return;
        }
        for (ServerHandler handler : handlers) {
            WsServerRequest wsRequest = new WsServerRequest(topic, body);
            WsServerResponse wsResponse = new WsServerResponse(conn);
            try {
                handler.handle(wsRequest, wsResponse);
            } catch (Exception e) {
                log.warn("WebSocket handler error: {}", e.getMessage(), e);
            }
            if (wsResponse.getResult() != null) {
                conn.send(wsResponse.getResult().toString());
            }
        }
    }

    /**
     * 订阅指定主题的 WebSocket 消息。
     *
     * @param topic   主题
     * @param handler 消息处理器
     * @return 当前服务器实例
     */
    public NioHttpServer onSubscribe(String topic, ServerHandler handler) {
        wsTopicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 向指定主题广播消息。
     *
     * @param topic   主题
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        String message = topic + "\n" + payload;
        byte[] frame = WebSocketProtocol.textFrame(message);
        for (WsConnection conn : wsConnections) {
            conn.sendRaw(frame);
        }
    }

    @Override
    public NioHttpServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        // 扫描 @OnMessage 注解方法注册为 WebSocket 主题处理器
        for (Method method : handler.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnMessage.class)) {
                method.setAccessible(true);
                OnMessage ann = method.getAnnotation(OnMessage.class);
                String topic = ann.value();
                if (topic == null || topic.isEmpty()) {
                    topic = "default";
                }
                wsTopicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                        .add(createWsMessageHandler(handler, method));
            }
        }
        return this;
    }

    /**
     * 构建基于 {@code @OnMessage} 注解方法的处理器。
     */
    private ServerHandler createWsMessageHandler(Object bean, Method method) {
        return (request, response) -> {
            try {
                Class<?>[] paramTypes = method.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                String body = request.getBodyString();
                for (int i = 0; i < paramTypes.length; i++) {
                    Class<?> type = paramTypes[i];
                    if (type == ServerRequest.class) {
                        args[i] = request;
                    } else if (type == ServerResponse.class) {
                        args[i] = response;
                    } else if (type == String.class) {
                        args[i] = body;
                    } else if (type == byte[].class) {
                        args[i] = body != null ? body.getBytes(setting.getCharset()) : null;
                    } else {
                        throw new IllegalArgumentException("Unsupported param: " + type.getName());
                    }
                }
                Object result = method.invoke(bean, args);
                if (result != null) {
                    response.setResult(result);
                }
            } catch (Exception e) {
                if (!response.isEnded()) {
                    response.sendError(500, "Internal Server Error");
                }
            }
        };
    }

    // ==================== WebSocket 内部类 ====================

    /**
     * WebSocket 连接封装，负责向对端发送帧。
     */
    private static final class WsConnection {
        private final OutputStream out;

        WsConnection(OutputStream out) {
            this.out = out;
        }

        /**
         * 发送文本消息。
         */
        void send(String text) {
            sendRaw(WebSocketProtocol.textFrame(text));
        }

        /**
         * 发送原始帧数据。
         */
        void sendRaw(byte[] frame) {
            try {
                synchronized (out) {
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * WebSocket 消息请求（与 JdkWebSocketServer.SimpleServerRequest 行为一致）。
     */
    private static final class WsServerRequest implements ServerRequest {
        private final String topic;
        private final String body;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        WsServerRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        @Override public String getUri() { return "/ws/" + topic; }
        @Override public String getPath() { return "/ws/" + topic; }
        @Override public HttpMethod getMethod() { return HttpMethod.POST; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return HttpHeader.create(); }
        @Override public Map<String, String> getParams() { return Collections.emptyMap(); }
        @Override public String getParam(String name) { return null; }
        @Override public String getContentType() { return "text/plain"; }
        @Override public long getContentLength() { return body != null ? body.getBytes(StandardCharsets.UTF_8).length : -1; }
        @Override public byte[] getBody() { return body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]; }
        @Override public String getBodyString() { return body; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(getBody()); }
        @Override public String getRemoteAddress() { return "127.0.0.1"; }
        @Override public int getRemotePort() { return 0; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    }

    /**
     * WebSocket 消息响应（与 JdkWebSocketServer.SimpleServerResponse 行为一致）。
     */
    private static final class WsServerResponse implements ServerResponse {
        private final WsConnection connection;
        private int status = 200;
        private boolean ended;
        private boolean committed;
        private Object result;

        WsServerResponse(WsConnection connection) {
            this.connection = connection;
        }

        @Override public int getStatus() { return status; }
        @Override public ServerResponse setStatus(int statusCode) { this.status = statusCode; return this; }
        @Override public ServerResponse setBody(byte[] body) { this.result = body; return this; }
        @Override public ServerResponse setBody(String body) { this.result = body; return this; }
        @Override public ServerResponse setHeader(String name, String value) { return this; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return HttpHeader.create(); }
        @Override public String getContentType() { return null; }
        @Override public ServerResponse setContentType(String contentType) { return this; }
        @Override public byte[] getBody() { return result instanceof byte[] b ? b : null; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public ServerResponse sendRedirect(String location) { return this; }
        @Override public ServerResponse sendError(int code, String message) {
            this.status = code;
            this.result = message;
            this.ended = true;
            return this;
        }
        @Override public void flush() { }
        @Override public boolean isCommitted() { return committed; }
        @Override public boolean isEnded() { return ended; }
        @Override public void end() { this.ended = true; }
        @Override public ServerResponse reset() {
            if (!committed) {
                status = 200;
                result = null;
                ended = false;
            }
            return this;
        }
        @Override public void writeRaw(byte[] bytes) {
            connection.sendRaw(bytes);
        }
        @Override public ServerResponse setResult(Object result) { this.result = result; return this; }
        @Override public Object getResult() { return result; }
        @Override public ServerResponse sse() { return this; }
        @Override public void sseEvent(String event, String data) { }
        @Override public void sseClose() { }
    }

    /**
     * 判断是否保持连接
     */
    private boolean shouldKeepAlive(NioServerRequest request, NioServerResponse response) {
        if (response.isChannelClosed()) {
            return false;
        }
        String connHeader = request.getHeader("Connection");
        if (connHeader != null) {
            return "keep-alive".equalsIgnoreCase(connHeader.trim());
        }
        // HTTP/1.1 默认 Keep-Alive
        return "HTTP/1.1".equalsIgnoreCase(request.getHttpVersion());
    }

    /** 安静关闭SocketChannel */
    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void doStopAccepting() {
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void doStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
        wsConnections.clear();
        log.info("NIO HttpServer stopped");
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }
}
