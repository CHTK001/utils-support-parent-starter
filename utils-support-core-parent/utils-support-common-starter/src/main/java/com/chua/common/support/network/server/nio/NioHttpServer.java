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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
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
    /** 多 Selector 分片:每分片一个事件循环线程,解决单事件循环在高并发下的瓶颈 */
    private Selector[] selectors;
    /** 每分片对应的待写 key 队列(worker 只入队,由对应分片事件循环统一注册 OP_WRITE) */
    private java.util.Queue<SelectionKey>[] pendingWriteQueues;
    private ExecutorService executor;
    private ExecutorService acceptorPool;
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
            // 非阻塞 accept:Selector 事件循环驱动,真正的 NIO 响应式接入,
            // 避免阻塞 accept 单点瓶颈,提升高并发连接接纳吞吐
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, setting.isSoReuseAddr());
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, Math.max(setting.getBufferSize(), 16384));
            // 高并发连接接纳：backlog 下限 8192（与 JdkHttpServer 对齐），5000 并发下避免连接被内核拒绝
            int backlog = Math.max(setting.getBacklog(), 8192);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), backlog);

            // 回填实际端口（port=0 时由系统分配）
            InetSocketAddress bound = (InetSocketAddress) serverChannel.getLocalAddress();
            setting.setPort(bound.getPort());

            executor = Executors.newVirtualThreadPerTaskExecutor();
            // 多 Selector 分片:每分片一个事件循环线程,连接按 hash 分散注册,
            // 解决单事件循环在高并发(2000+)下成为吞吐瓶颈的问题(类 Netty 主从模型)
            int eventLoops = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));
            selectors = new Selector[eventLoops];
            pendingWriteQueues = new java.util.Queue[eventLoops];
            @SuppressWarnings("unchecked")
            java.util.Queue<SelectionKey>[] queues = new java.util.concurrent.ConcurrentLinkedQueue[eventLoops];
            for (int i = 0; i < eventLoops; i++) {
                selectors[i] = Selector.open();
                queues[i] = new java.util.concurrent.ConcurrentLinkedQueue<>();
            }
            pendingWriteQueues = queues;
            // serverChannel 注册到分片 0 的 OP_ACCEPT
            serverChannel.register(selectors[0], SelectionKey.OP_ACCEPT);
            acceptorPool = Executors.newFixedThreadPool(eventLoops, r -> {
                Thread t = new Thread(r, "nio-event-loop");
                t.setDaemon(true);
                return t;
            });
            for (int i = 0; i < eventLoops; i++) {
                final int idx = i;
                acceptorPool.submit(() -> eventLoop(idx));
            }

            log.info("NIO HttpServer started on {}:{} (backlog={}, eventLoops={}, reactive=true)",
                    setting.getHost(), setting.getPort(), backlog, eventLoops);
        } catch (Exception e) {
            throw new RuntimeException("NIO HttpServer 启动失败", e);
        }
    }

    /**
     * 事件循环(分片版):每分片一个 Selector + 线程,处理该分片连接的 OP_READ/OP_WRITE。
     * 分片 0 额外承载 OP_ACCEPT。连接不占线程;完整请求解析后提交虚拟线程 worker 池执行 handler 链。
     */
    private void eventLoop(int idx) {
        Selector sel = selectors[idx];
        java.util.Queue<SelectionKey> writeQueue = pendingWriteQueues[idx];
        log.info("nio event-loop[{}] started, selector={}", idx, sel);
        while (running) {
            try {
                sel.select(1000L);
                // 统一在本分片事件循环线程注册 OP_WRITE(worker 只入队 + wakeup,避免跨线程 interestOps 竞态)
                SelectionKey wk;
                while ((wk = writeQueue.poll()) != null) {
                    if (wk.isValid()) {
                        wk.interestOps(SelectionKey.OP_WRITE);
                    }
                }
                java.util.Iterator<SelectionKey> it = sel.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log.warn("Event loop[{}] error: {}", idx, e.getMessage());
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        SocketChannel accepted = serverChannel.accept();
        if (accepted == null) {
            return;
        }
        // SSL 场景:回退虚拟线程阻塞路径(SSL 通道无法注册 Selector)
        if (sslContext != null) {
            accepted.configureBlocking(true);
            accepted.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
            try {
                SocketChannel client = new SslSocketChannel(accepted, sslContext.createSSLEngine());
                executor.submit(() -> handleConnection(client));
            } catch (IOException e) {
                log.warn("TLS 握手失败，关闭连接: {}", e.getMessage());
                closeQuietly(accepted);
            }
            return;
        }
        // 非阻塞注册:连接按 hash 分散到各分片 Selector,避免单事件循环瓶颈
        accepted.configureBlocking(false);
        accepted.setOption(StandardSocketOptions.TCP_NODELAY, setting.isTcpNoDelay());
        int shard = (accepted.hashCode() & Integer.MAX_VALUE) % selectors.length;
        ConnectionState st = new ConnectionState(accepted, setting.getMaxRequestSize(), setting.getCharset());
        st.shard = shard;
        // 跨线程注册:accept 在分片 0 线程执行,注册到其他分片后必须 wakeup 该分片,
        // 否则其 select() 阻塞中的事件循环线程感知不到新连接就绪,请求卡死
        accepted.register(selectors[shard], SelectionKey.OP_READ, st);
        selectors[shard].wakeup();
        log.info("nio accepted -> shard={}", shard);
    }

    private void handleRead(SelectionKey key) throws IOException {
        ConnectionState st = (ConnectionState) key.attachment();
        // 请求已在 worker 处理中:摘除读兴趣,避免事件循环空转与重复提交 worker;
        // worker 完成后经 pendingWrites 由事件循环重新注册 OP_WRITE
        if (st.inWorker) {
            key.interestOps(0);
            return;
        }
        ByteBuffer buf = st.readBuf;
        int n = st.channel.read(buf);
        if (n < 0) {
            closeConn(key, st);
            return;
        }
        if (n == 0) {
            return;
        }
        buf.flip();
        int r = st.request.feed(buf);
        buf.compact(); // 保留未消费数据
        if (r == 1) {
            // 完整请求解析完成:摘除 OP_READ,提交 worker 池执行 handler 链
            key.interestOps(0);
            st.inWorker = true;
            executor.submit(() -> processRequest(st, key));
        } else if (r < 0) {
            closeConn(key, st);
        }
    }

    /**
     * worker(虚拟线程)执行 handler 链,响应通过 asyncWriter 交给事件循环 OP_WRITE 写出。
     */
    private void processRequest(ConnectionState st, SelectionKey key) {
        try {
            // WebSocket 升级:回退到虚拟线程帧协议处理
            if (WebSocketProtocol.isUpgradeRequest(st.request)) {
                st.channel.configureBlocking(true);
                handleWebSocketUpgrade(st.channel, st.request);
                closeConn(key, st);
                return;
            }
            NioServerResponse response = new NioServerResponse(st.channel);
            response.setAsyncWriter((header, body) -> {
                synchronized (st.writeQueue) {
                    st.writeQueue.add(header);
                    if (body != null && body.hasRemaining()) {
                        st.writeQueue.add(body);
                    }
                }
                // 交由所属分片事件循环线程统一注册 OP_WRITE,避免 worker 线程跨线程改 interestOps 竞态
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            });
            try {
                handleRequest(st.request, response);
            } catch (Exception e) {
                log.warn("Request handling failed: {}", e.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(500, "Internal Server Error");
                }
            } finally {
                response.complete();
            }
            st.keepAlive = shouldKeepAlive(st.request, response);
            st.request.resetForNextRequest();
            // 触发写:入队待写 key,由所属分片事件循环线程统一注册 OP_WRITE
            if (!st.writeQueue.isEmpty()) {
                pendingWriteQueues[st.shard].add(key);
                selectors[st.shard].wakeup();
            }
        } catch (Exception e) {
            log.warn("Worker handling failed: {} -> {}", e.getClass().getSimpleName(), e.getMessage());
            closeConn(key, st);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        ConnectionState st = (ConnectionState) key.attachment();
        // 无锁队列:事件循环线程作为唯一消费者
        while (true) {
            ByteBuffer bb = st.writeQueue.peek();
            if (bb == null) {
                break;
            }
            int w = st.channel.write(bb);
            if (w < 0) {
                closeConn(key, st);
                return;
            }
            if (bb.hasRemaining()) {
                // 未写完,等待下次 OP_WRITE
                return;
            }
            st.writeQueue.poll();
        }
        // 写完:Keep-Alive 则重新注册 OP_READ,否则关闭
        if (st.keepAlive && running) {
            key.interestOps(SelectionKey.OP_READ);
            st.inWorker = false;
        } else {
            closeConn(key, st);
        }
    }

    private void closeConn(SelectionKey key, ConnectionState st) {
        try {
            key.cancel();
        } catch (Exception ignored) {
        }
        closeQuietly(st.channel);
    }

    /** 连接状态:非阻塞通道 + 增量解析器 + 读缓冲 + 待写队列 */
    private static final class ConnectionState {
        final SocketChannel channel;
        final NioServerRequest request;
        // 每连接独立读缓冲:ThreadLocal 池化在 2000 并发下出现请求 0% 回归,
        // 固定分配更稳定(连接生命周期内复用同一缓冲,无跨连接共享风险)
        final ByteBuffer readBuf = ByteBuffer.allocate(16384);
        // 实测 ArrayDeque + synchronized 在 1000/2000 并发下吞吐最高(3876/2430 RPS),
        // 无锁队列 + pendingWrites 因多一轮 select 循环反而降低吞吐
        final java.util.ArrayDeque<ByteBuffer> writeQueue = new java.util.ArrayDeque<>();
        boolean keepAlive = true;
        boolean inWorker = false;
        /** 所属分片索引(决定注册到哪个 Selector 与写队列) */
        int shard = 0;

        ConnectionState(SocketChannel channel, long maxRequestSize, String charset) {
            this.channel = channel;
            this.request = new NioServerRequest(channel, maxRequestSize, charset);
        }
    }

    /**
     * 处理连接(SSL 回退路径):阻塞读 + feed() 增量解析,支持 Keep-Alive。
     * 普通 HTTP 走事件循环 processRequest;SSL 通道无法注册 Selector,回退此处。
     */
    private void handleConnection(SocketChannel channel) {
        try {
            NioServerRequest request = new NioServerRequest(channel,
                    setting.getMaxRequestSize(), setting.getCharset());
            ByteBuffer readBuf = ByteBuffer.allocate(16384);
            while (running && channel.isConnected()) {
                int n = channel.read(readBuf);
                if (n < 0) {
                    break; // 对端关闭
                }
                if (n == 0) {
                    continue;
                }
                readBuf.flip();
                int r = request.feed(readBuf);
                readBuf.compact();
                if (r == 0) {
                    continue; // 还需更多数据
                }
                if (r < 0) {
                    break; // 解析错误
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
        if (selectors != null) {
            for (Selector sel : selectors) {
                try {
                    sel.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (acceptorPool != null) {
            acceptorPool.shutdownNow();
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
