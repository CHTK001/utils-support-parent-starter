package com.chua.vertx.support.server;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.starter.datasync.agent.DefaultDataSyncAgentServer;
import com.chua.vertx.support.source.WebSocketAgentDataSyncSource;
import com.chua.common.support.lang.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
   * WebSocket 数据同步 智能体 服务端
 * <p>运行在 DataSyncServer 侧，接受 WebSocket 连接，管理 Agent 注册、心跳、数据拉取。</p>
 *
 * <pre>{@code
 * WebSocketDataSyncAgentServer server = new WebSocketDataSyncAgentServer(8080);
 * server.start();
 * }</pre>;
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 2026-07-20
 */
public class WebSocketDataSyncAgentServer extends DefaultDataSyncAgentServer {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(WebSocketDataSyncAgentServer.class);

    /**
     * 监听端口
     */
    private final int port;

    /**
     * 服务器套接字
     */
    private ServerSocket serverSocket;

    /**
     * 线程池
     */
    private ExecutorService executor;

    /**
       * 智能体 连接映射（智能体标识 -> Connection）
     */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /**
       * 响应等待器（请求标识 -> completable期货）
     */
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    /**
      * 创建 web套接字数据同步智能体服务端 实例
     * @param port 端口
     */
    public WebSocketDataSyncAgentServer(int port) {
        super("websocket");
        this.port = port;
    }

    @Override
    /** 开始 */
    public void start() {
        if (running) {
            return;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(port));
            executor = Executors.newCachedThreadPool(r -> new Thread(r, "ws-agent-server-" + r.hashCode()));
            executor.submit(this::acceptLoop);
            running = true;
            log.info("[WebSocketDataSyncAgentServer] 已启动，监听端口: {}", port);
        } catch (IOException e) {
            throw new RuntimeException("WebSocketDataSyncAgentServer 启动失败", e);
        }
    }

    @Override
    /** 停止 */
    public void stop() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        connections.values().forEach(Connection::close);
        connections.clear();
        running = false;
        log.info("[WebSocketDataSyncAgentServer] 已停止");
    }

    /** accept循环 */
    private void acceptLoop() {
        while (!serverSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                executor.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
                log.warn("[WebSocketDataSyncAgentServer] accept 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理Connection
     *
     * @param socket 套接字
     */
    private void handleConnection(Socket socket) {
        String agentId = null;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            ByteArrayOutputStream reqBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read = in.read(buf);
            if (read <= 0) {
                socket.close();
                return;
            }
            reqBuf.write(buf, 0, read);
            String request = reqBuf.toString(StandardCharsets.UTF_8);
            String key = null;
            for (String line : request.split("\r\n")) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    key = line.substring(line.indexOf(":") + 1).trim();
                    break;
                }
            }
            if (key == null) {
                socket.close();
                return;
            }

            String accept = computeWebSocketAccept(key);
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String registerMsg = readTextFrame(in);
            Map<String, Object> register = Json.fromJson(registerMsg, Map.class);
            agentId = (String) register.get("agentId");
            String sourceId = (String) register.get("sourceId");

            Connection conn = new Connection(socket, agentId, sourceId);
            connections.put(agentId, conn);
            onAgentConnected(new SimpleDataSyncAgent(agentId, sourceId));

            log.info("[WebSocketDataSyncAgentServer] Agent 已连接: agentId={}, sourceId={}", agentId, sourceId);

            while (!socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                String msg = readTextFrame(in);
                if (msg == null) {
                    break;
                }
                Map<String, Object> parsed = Json.fromJson(msg, Map.class);
                String type = (String) parsed.get("requestId");
                if (type != null) {
                    CompletableFuture<String> future = pendingRequests.remove(type);
                    if (future != null) {
                        future.complete(msg);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[WebSocketDataSyncAgentServer] 连接异常: {}", e.getMessage());
        } finally {
            if (agentId != null) {
                connections.remove(agentId);
                onAgentDisconnected(new SimpleDataSyncAgent(agentId, ""));
                log.info("[WebSocketDataSyncAgentServer] Agent 已断开: agentId={}", agentId);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 发送请求
     *
     * @param agentId 智能体标识
     * @param request 请求
     * @return 发送请求的结果
     */
    public String sendRequest(String agentId, String request) {
        Connection conn = connections.get(agentId);
        if (conn == null) {
            return null;
        }
        try {
            String requestId = java.util.UUID.randomUUID().toString();
            Map<String, Object> req = Json.fromJson(request, Map.class);
            req.put("requestId", requestId);
            String json = Json.toJson(req);

            CompletableFuture<String> future = new CompletableFuture<>();
            pendingRequests.put(requestId, future);

            conn.sendTextFrame(json);
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取文本帧
     *
     * @param in 入
     * @return 读取文本帧的结果
     */
    private static String readTextFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int opcode = b0 & 0x0F;
        int b1 = in.read();
        if (b1 < 0) {
            return null;
        }
        boolean masked = (b1 & 0x80) != 0;
        int length = b1 & 0x7F;
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 4; i++) {
                length = (length << 8) | in.read();
            }
            long ext = 0;
            for (int i = 0; i < 4; i++) {
                ext = (ext << 8) | in.read();
            }
            if (ext > Integer.MAX_VALUE) {
                return null;
            }
            length = (int) ext;
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
            return null;
        }
        if (opcode == 0x9 || opcode == 0xA) {
            return readTextFrame(in);
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * 写入文本帧
     *
     * @param out 出
     * @param payload payload
     */
    private static void writeTextFrame(OutputStream out, String payload) throws IOException {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
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
        out.flush();
    }

    /**
     * computeweb套接字accept
     *
     * @param key 键
     * @return computeweb套接字accept的结果
     * @author CH
     * @since 4.0.0
     */
    private static String computeWebSocketAccept(String key) throws Exception {
        String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(md.digest(combined.getBytes(StandardCharsets.US_ASCII)));
    }

    public static class Connection {
        /** 套接字 */
        private final Socket socket;
        /** 智能体标识 */
        private final String agentId;
        /** 来源标识 */
        private final String sourceId;

        Connection(Socket socket, String agentId, String sourceId) {
            this.socket = socket;
            this.agentId = agentId;
            this.sourceId = sourceId;
        }

        /**
         * 发送文本帧
         *
         * @param text 文本
         */
        public void sendTextFrame(String text) throws IOException {
            synchronized (socket) {
                writeTextFrame(socket.getOutputStream(), text);
            }
        }

        /**
          * 获取智能体标识
         *
         * @return 获取智能体id的结果
         */
        public String getAgentId() { return agentId; }
        /**
          * 获取源标识
         *
         * @return 获取源id的结果
         */
        public String getSourceId() { return sourceId; }
        /** 关闭 */
        public void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static class SimpleDataSyncAgent implements DataSyncAgent {
        /** 智能体标识 */
        private final String agentId;
        /** 来源标识 */
        private final String sourceId;

        SimpleDataSyncAgent(String agentId, String sourceId) {
            this.agentId = agentId;
            this.sourceId = sourceId;
        }

        @Override public String agentId() { return agentId; }
        @Override public DataSyncSource toSource() { return null; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return false; }
        @Override public String dataUrl() { return ""; }
    }
}
