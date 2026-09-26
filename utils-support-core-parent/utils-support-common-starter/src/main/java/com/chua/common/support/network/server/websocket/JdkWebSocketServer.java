package com.chua.common.support.network.server.websocket;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Base64;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jdk-websocket")
public class JdkWebSocketServer extends AbstractServer {

    /**
     * 服务器Socket
     */
    private ServerSocket serverSocket;
    /**
     * 执行器
     */
    private ExecutorService executor;
    /**
     * topicHandlers
     */
    private final Map<String, List<ServerHandler>> topicHandlers = new ConcurrentHashMap<>();
    /**
     * Connections
     */
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    /**
     * ConnectionIDSEQ
     */
    private final AtomicInteger connectionIdSeq = new AtomicInteger();

    /**
     * 创建 JdkWebSocketServer 实例
     * @param setting setting
     */
    public JdkWebSocketServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * Do开始
     */
    @Override
    protected void doStart() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.setReceiveBufferSize(Math.max(setting.getBufferSize(), 16384));
            serverSocket.bind(new java.net.InetSocketAddress(setting.getHost(), setting.getPort()),
                    Math.max(setting.getBacklog(), 2048));
            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.submit(this::acceptLoop);
            log.info("JDK WebSocketServer started on {}:{} (backlog={}, virtualThreads=true)",
                    setting.getHost(), setting.getPort(), Math.max(setting.getBacklog(), 2048));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (Connection c : connections) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
        connections.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
        invokeAnnotatedMethods(OnClose.class);
        log.info("JDK WebSocketServer stopped");
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.WS;
    }

    /**
     * 注册Bean
     */
    @Override
    public JdkWebSocketServer registerBean(Object handler) {
        super.registerBean(handler);
        if (handler == null) {
            return this;
        }
        Class<?> clazz = handler.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            ClassUtils.setAccessible(method);
            if (method.isAnnotationPresent(OnMessage.class)) {
                OnMessage ann = method.getAnnotation(OnMessage.class);
                String topic = ann.value();
                if (topic == null || topic.isEmpty()) {
                    topic = "default";
                }
                ServerHandler serverHandler = createMessageHandler(handler, method);
                topicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(serverHandler);
            }
        }
        return this;
    }

    /**
     * On订阅
     * @param topic 方法入参 topic
     * @param handler 处理器，不允许为 null
     * @return JdkWebSocket服务端 对象
     */
    public JdkWebSocketServer onSubscribe(String topic, ServerHandler handler) {
        topicHandlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    /**
     * 发布
     * @param topic 方法入参 topic
     * @param payload 方法入参 payload
     */
    public void publish(String topic, String payload) {
        try {
            String frame = buildTextFrame(payload);
            for (Connection c : connections) {
                try {
                    synchronized (c) {
                        c.out.write(frame.getBytes(StandardCharsets.ISO_8859_1));
                        c.out.flush();
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * AcceptLoop
     */
    private void acceptLoop() {
        while (!serverSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(setting.isTcpNoDelay());
                Connection conn = new Connection(socket);
                connections.add(conn);
                executor.submit(() -> handleConnection(conn));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
                log.warn("WebSocket accept failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理Connection
     * @param conn 连接，不允许为 null
     */
    private void handleConnection(Connection conn) {
        try {
            if (!performHandshake(conn)) {
                conn.close();
                connections.remove(conn);
                return;
            }
            invokeAnnotatedMethods(OnOpen.class);
            readFrames(conn);
        } catch (IOException e) {
            log.debug("WebSocket connection closed: {}", e.getMessage());
        } finally {
            try {
                conn.close();
            } catch (IOException ignored) {
            }
            connections.remove(conn);
            invokeAnnotatedMethods(OnClose.class);
        }
    }

    /**
     * PerformHandshake
     * @param conn 连接，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private boolean performHandshake(Connection conn) throws IOException {
        InputStream in = conn.socket.getInputStream();
        OutputStream out = conn.socket.getOutputStream();
        ByteArrayOutputStream reqBuf = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read = in.read(buf);
        if (read <= 0) {
            return false;
        }
        reqBuf.write(buf, 0, read);
        String request = reqBuf.toString(StandardCharsets.UTF_8.name());
        String key = null;
        for (String line : request.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(":") + 1).trim();
                break;
            }
        }
        if (key == null) {
            return false;
        }
        String accept = computeWebSocketAccept(key);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        conn.out = out;
        return true;
    }

    /**
     * ComputeWebSocketAccept
     * @param key 键，不允许为 null
     * @return 结果字符串
     */
    private String computeWebSocketAccept(String key) {
        try {
            String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(combined.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 读取Frames
     * @param conn 连接，不允许为 null
     */
    private void readFrames(Connection conn) throws IOException {
        InputStream in = conn.socket.getInputStream();
        while (!conn.socket.isClosed() && !Thread.currentThread().isInterrupted()) {
            int b0 = in.read();
            if (b0 < 0) {
                break;
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            int b1 = in.read();
            if (b1 < 0) {
                break;
            }
            boolean masked = (b1 & 0x80) != 0;
            int length = b1 & 0x7F;
            if (length == 126) {
                length = (in.read() << 8) | (in.read());
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 4; i++) {
                    length = (length << 8) | in.read();
                }
                long extended = 0;
                for (int i = 0; i < 4; i++) {
                    extended = (extended << 8) | in.read();
                }
                if (extended > Integer.MAX_VALUE) {
                    continue;
                }
                length = (int) extended;
            }
            byte[] maskKey = new byte[4];
            if (masked) {
                in.read(maskKey);
            }
            byte[] payload = new byte[length];
            int off = 0;
            while (off < length) {
                int r = in.read(payload, off, length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i % 4];
                }
            }
            if (opcode == 0x8) {
                break;
            }
            if (opcode == 0x9 || opcode == 0xA) {
                continue;
            }
            if (opcode == 0x1 || opcode == 0x2) {
                String text = new String(payload, StandardCharsets.UTF_8);
                String topic = "default";
                String body = text;
                int idx = text.indexOf('\n');
                if (idx > 0) {
                    topic = text.substring(0, idx).trim();
                    body = text.substring(idx + 1);
                } else if (idx == 0) {
                    body = text.substring(1);
                }
                String finalBody = body;
                List<ServerHandler> handlers = topicHandlers.get(topic);
                if (handlers != null) {
                    for (ServerHandler handler : handlers) {
                        SimpleServerRequest request = new SimpleServerRequest(topic, finalBody);
                        SimpleServerResponse response = new SimpleServerResponse(conn);
                        try {
                            handler.handle(request, response);
                        } catch (Exception e) {
                            log.warn("WebSocket handler error: {}", e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 构建TextFrame
     * @param payload 方法入参 payload
     * @return 结果字符串
     */
    private String buildTextFrame(String payload) throws Exception {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        if (data.length < 126) {
            out.write(data.length);
        } else if (data.length <= 0xFFFF) {
            out.write(126);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 3; i >= 0; i--) {
                out.write((data.length >> (i * 8)) & 0xFF);
            }
            for (int i = 3; i >= 0; i--) {
                out.write(0);
            }
        }
        out.write(data, 0, data.length);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /**
     * 创建MessageHandler
     * @param bean 方法入参 bean
     * @param method 方法，不允许为 null
     * @return 服务端处理器 对象
     */
    private ServerHandler createMessageHandler(Object bean, Method method) {
        ClassUtils.setAccessible(method);
        return (request, response) -> {
            try {
                Class<?>[] paramTypes = method.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                String body = request.getBodyString();
                for (int i = 0; i < paramTypes.length; i++) {
                    Class<?> type = paramTypes[i];
                    if (type == com.chua.common.support.network.server.request.ServerRequest.class) {
                        args[i] = request;
                    } else if (type == com.chua.common.support.network.server.response.ServerResponse.class) {
                        args[i] = response;
                    } else if (type == String.class) {
                        args[i] = body;
                    } else if (type == byte[].class) {
                        args[i] = body != null ? body.getBytes(setting.getCharset()) : null;
                    } else {
                        throw new IllegalArgumentException("Unsupported param: " + type.getName());
                    }
                }
                Object result = ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
                if (result != null) {
                    response.setResult(result);
                }
                if (response instanceof SimpleServerResponse wsResp && wsResp.getCloseMessage() != null) {
                    String frame = buildCloseFrame(wsResp.getCloseMessage());
                    synchronized (((SimpleServerResponse) response).getConnection()) {
                        ((SimpleServerResponse) response).getConnection().out.write(frame.getBytes(StandardCharsets.ISO_8859_1));
                        ((SimpleServerResponse) response).getConnection().out.flush();
                    }
                }
            } catch (Exception e) {
                if (!response.isEnded()) {
                    response.sendError(500, "Internal Server Error");
                }
            }
        };
    }

    /**
     * 构建关闭Frame
     * @param reason 方法入参 reason
     * @return 结果字符串
     */
    private String buildCloseFrame(String reason) throws Exception {
        byte[] data = reason.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x88);
        out.write(0x80 | data.length);
        out.write(new byte[4]);
        out.write(data, 0, data.length);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /**
     * 调用AnnotatedMethods
     * @param annotationType annotation类型，不允许为 null
     */
    private void invokeAnnotatedMethods(Class<? extends Annotation> annotationType) {
        if (getObjectContext() == null) {
            return;
        }
        Collection<String> beanNames = getObjectContext().getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = getObjectContext().getBean(beanName, Object.class);
            if (bean == null) {
                continue;
            }
            for (Method method : bean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(annotationType)) {
                    ClassUtils.setAccessible(method);
                    try {
                        ReflectUtils.invoke(bean, method.getName(), method.getReturnType());
                    } catch (Exception e) {
                        log.error("Invoke {} error: {}.{}", annotationType.getSimpleName(),
                                bean.getClass().getSimpleName(), method.getName(), e);
                    }
                }
            }
        }
    }

    private static class Connection {
        final Socket socket;
        OutputStream out;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void close() throws IOException {
            if (!socket.isClosed()) {
                socket.close();
            }
        }
    }

    private static class SimpleServerRequest implements ServerRequest {

        /**
         * 主题
         */
        private final String topic;
        /**
         * 请求体
         */
        private final String body;
        /**
         * attributes
         */
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        SimpleServerRequest(String topic, String body) {
            this.topic = topic;
            this.body = body;
        }

        /**
         * 获取Uri
         */
        @Override
        public String getUri() {
            return "/ws/" + topic;
        }

        /**
         * 获取Path
         */
        @Override
        public String getPath() {
            return "/ws/" + topic;
        }

        /**
         * 获取Method
         */
        @Override
        public com.chua.common.support.network.http.HttpMethod getMethod() {
            return com.chua.common.support.network.http.HttpMethod.POST;
        }

        /**
         * 获取Header
         */
        @Override
        public String getHeader(String name) {
            return null;
        }

        /**
         * 获取Headers
         */
        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }

        /**
         * 获取Params
         */
        @Override
        public Map<String, String> getParams() {
            return Collections.emptyMap();
        }

        /**
         * 获取Param
         */
        @Override
        public String getParam(String name) {
            return null;
        }

        /**
         * 获取ContentType
         */
        @Override
        public String getContentType() {
            return "text/plain";
        }

        /**
         * 获取Content获取长度
         */
        @Override
        public long getContentLength() {
            return body != null ? body.getBytes().length : -1;
        }

        /**
         * 获取Body
         */
        @Override
        public byte[] getBody() {
            return body != null ? body.getBytes() : new byte[0];
        }

        /**
         * 获取BodyString
         */
        @Override
        public String getBodyString() {
            return body;
        }

        /**
         * 获取InputStream
         */
        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(body != null ? body.getBytes() : new byte[0]);
        }

        /**
         * 获取RemoteAddress
         */
        @Override
        public String getRemoteAddress() {
            return "127.0.0.1";
        }

        /**
         * 获取RemotePort
         */
        @Override
        public int getRemotePort() {
            return 0;
        }

        /**
         * 获取Attributes
         */
        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        /**
         * 获取Attribute
         */
        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        /**
         * 设置Attribute
         */
        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }
    }

    private static class SimpleServerResponse implements ServerResponse {

        /**
         * Connection
         */
        private final Connection connection;
        /**
         * ended
         */
        private volatile boolean ended;
        /**
         * committed
         */
        private volatile boolean committed;
        /**
         * 状态
         */
        private int status = 200;
        /**
         * 结果
         */
        private Object result;
        /**
         * Close消息
         */
        private String closeMessage;

        SimpleServerResponse(Connection connection) {
            this.connection = connection;
        }

        /**
         * 获取Status
         */
        @Override
        public int getStatus() {
            return status;
        }

        /**
         * 设置Status
         */
        @Override
        public ServerResponse setStatus(int status) {
            this.status = status;
            return this;
        }

        /**
         * 设置Body
         */
        @Override
        public ServerResponse setBody(byte[] body) {
            this.result = body;
            return this;
        }

        /**
         * 设置Body
         */
        @Override
        public ServerResponse setBody(String body) {
            this.result = body;
            return this;
        }

        /**
         * 设置Header
         */
        @Override
        public ServerResponse setHeader(String name, String value) {
            return this;
        }

        /**
         * 获取Header
         */
        @Override
        public String getHeader(String name) {
            return null;
        }

        /**
         * 获取Headers
         */
        @Override
        public com.chua.common.support.network.http.HttpHeader getHeaders() {
            return com.chua.common.support.network.http.HttpHeader.create();
        }

        /**
         * 获取ContentType
         */
        @Override
        public String getContentType() {
            return null;
        }

        /**
         * 设置ContentType
         */
        @Override
        public ServerResponse setContentType(String contentType) {
            return this;
        }

        /**
         * 获取Body
         */
        @Override
        public byte[] getBody() {
            return result instanceof byte[] ? (byte[]) result : null;
        }

        /**
         * 获取OutputStream
         */
        @Override
        public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        /**
         * 发送Redirect
         */
        @Override
        public ServerResponse sendRedirect(String location) {
            return this;
        }

        /**
         * 发送记录错误
         */
        @Override
        public ServerResponse sendError(int code, String message) {
            this.status = code;
            this.result = message;
            this.ended = true;
            return this;
        }

        /**
         * 刷新
         */
        @Override
        public void flush() {
        }

        /**
         * 是否Committed
         */
        @Override
        public boolean isCommitted() {
            return committed;
        }

        /**
         * 是否Ended
         */
        @Override
        public boolean isEnded() {
            return ended;
        }

        /**
         * End
         */
        @Override
        public void end() {
            this.ended = true;
        }

        /**
         * 重置
         */
        @Override
        public ServerResponse reset() {
            if (!committed) {
                status = 200;
                result = null;
                ended = false;
            }
            return this;
        }

        /**
         * 写入Raw
         */
        @Override
        public void writeRaw(byte[] bytes) {
        }

        /**
         * 设置Result
         */
        @Override
        public ServerResponse setResult(Object result) {
            this.result = result;
            return this;
        }

        /**
         * 获取Result
         */
        @Override
        public Object getResult() {
            return result;
        }

        void setCloseMessage(String closeMessage) {
            this.closeMessage = closeMessage;
        }

        String getCloseMessage() {
            return closeMessage;
        }

        Connection getConnection() {
            return connection;
        }
    }
}
