// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.chua.common.support.network.server.AbstractServer;

/**
 * 基于 JDK Socket 的 WebSocket 同步服务端实现。
 * <p>
 * 提供主题发布、客户端消息拉取等能力。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("websocket")
public class WebSocketSyncServer extends AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 获取Protocol
     */
    @Override
    public String getProtocol() {
        return "websocket";
    }

    /**
     * 创建Server
     */
    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new WebSocketSyncServer(setting);
    }

    /**
     * 创建Client
     */
    @Override
    public SyncClient createClient(Object setting) {
        String url = "ws://" + (setting instanceof String ? (String) setting : "127.0.0.1:19380");
        return new WebSocketSyncClient(url);
    }

    /**
     * 服务器Socket
     */
    private ServerSocket serverSocket;

    /**
     * 线程池
     */
    private ExecutorService executor;

    /**
     * 连接列表
     */
    private final List<Connection> connections = new CopyOnWriteArrayList<>();

    /**
     * 客户端注册表（sessionId -> metadata）
     */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();

    /**
     * 主题订阅表（topic -> set of sessionId）
     */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = Wraps.list();

    /**
     * 连接 ID 生成器
     */
    private final AtomicInteger connectionIdSeq = new AtomicInteger();

    /**
     * 创建 WebSocket 同步服务端 (默认配置)。
     */
    public WebSocketSyncServer() {
        this(ServerSetting.defaults());
    }

    /**
     * 创建 WebSocket 同步服务端。
     *
     * @param setting 服务端配置
     */
    public WebSocketSyncServer(ServerSetting setting) {
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
            // backlog 用配置值（默认 bind 是 50）：突发握手会直接丢连接
            serverSocket.bind(new InetSocketAddress(setting.getHost(), setting.getPort()),
                    setting.resolveBacklog(1024));
            setting.setPort(serverSocket.getLocalPort());
            // WebSocket 是长连接：缓存线程池「一连接一平台线程」，上千连接就上千平台线程，
            // 与百万连接目标冲突。改为虚拟线程池，每条连接只在有数据时才占用调度。
            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.submit(this::acceptLoop);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket SyncServer 启动失败", e);
        }
    }

    /**
     * Do停止
     */
    @Override
    protected void doStop() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }
        for (Connection c : connections) {
            closeConnection(c);
        }
        connections.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
        clients.clear();
        subscriptions.clear();
    }

    /**
     * 发布
     */
    @Override
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message.toString();
        for (Connection conn : connections) {
            try {
                synchronized (conn) {
                    conn.send(payload);
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * 发送
     */
    @Override
    public void send(String clientId, String topic, Object message) {
        String messageBody = message instanceof String value ? value : Json.toJson(message);
        String payload = topic + ":" + messageBody;
        for (Connection conn : connections) {
            if (clientId.equals(conn.sessionId)) {
                try {
                    synchronized (conn) {
                        conn.send(payload);
                    }
                } catch (Exception e) {
                    // ignore
                }
                break;
            }
        }
    }

    /**
     * 获取ConnectedClients
     */
    @Override
    public List<String> getConnectedClients() {
        return Wraps.list(clients.keySet());
    }

    /**
     * 获取ClientMetadata
     */
    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    /**
     * 添加Listener
     */
    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除Listener
     */
    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.WS;
    }

    // ==================== 内部方法 ====================

    /**
     * 接受连接循环
     */
    private void acceptLoop() {
        while (!serverSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = serverSocket.accept();
                if (!accepting) {
                    // 优雅关闭中：不再接纳新连接（存量连接继续服务）
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // ignore
                    }
                    continue;
                }
                if (!tryAcquireConnection()) {
                    // 超限：直接关闭。WebSocket 握手前无法回 HTTP 语义的 503，
                    // 且会话表也不该为一个将被拒绝的连接分配条目
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // ignore
                    }
                    continue;
                }
                Connection conn = new Connection(socket);
                connections.add(conn);
                String sessionId = "ws-" + connectionIdSeq.incrementAndGet();
                conn.sessionId = sessionId;
                clients.put(sessionId, Wraps.map());
                notifyListener(l -> l.onClientConnected(sessionId, clients.get(sessionId)));
                try {
                    applySocketOptions(socket);
                    executor.submit(() -> handleConnection(conn));
                } catch (Exception e) {
                    // 停止中任务被拒：连接不会被处理，交给 closeConnection 统一收尾
                    closeConnection(conn);
                }
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
            }
        }
    }

    /**
     * 处理连接
     * @param conn 连接，不允许为 null
     */
    private void handleConnection(Connection conn) {
        try {
            if (!performHandshake(conn)) {
                closeConnection(conn);
                return;
            }
            readFrames(conn);
        } catch (IOException e) {
            // ignore
        } finally {
            closeConnection(conn);
        }
    }

    /**
     * 执行 WebSocket 握手
     * @param conn 连接，不允许为 null
     * @return 是否成功（true 表示成功）
     * @throws IOException 异常
    */
    private boolean performHandshake(Connection conn) throws IOException {
        InputStream in = conn.socket.getInputStream();
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
        String accept;
        try {
            accept = computeWebSocketAccept(key);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket 握手失败", e);
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        conn.out.write(response.getBytes(StandardCharsets.US_ASCII));
        conn.out.flush();
        return true;
    }

    /**
     * 读取 WebSocket 帧
     * @param conn 连接，不允许为 null
     * @return 返回值
     * @throws IOException 异常
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
            if (opcode == 0x1 || opcode == 0x2) {
                String text = new String(payload, StandardCharsets.UTF_8);
                String topic = "default";
                String body = text;
                int idx = text.indexOf(':');
                if (idx > 0) {
                    topic = text.substring(0, idx);
                    body = text.substring(idx + 1);
                }
                String finalBody = body;
                String finalTopic = topic;
                notifyListener(l -> l.onMessage(conn.sessionId, finalTopic, finalBody));
            }
        }
    }

    /**
     * 计算 WebSocket 接受密钥
     * @param key 键，不允许为 null
     * @return 结果字符串
     * @throws Exception 异常
    */
    private String computeWebSocketAccept(String key) throws Exception {
        String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(combined.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(digest);
    }

    /**
     * 构建 WebSocket 文本帧 <p>协议规定：仅客户端帧需掩码（masked），服务端帧必须无掩码——否则客户端按协议 校验会异常关闭连接（1006）。</p>
     * @param payload 方法入参 payload
     * @return 结果值
     * @throws Exception 异常
    */
    private static byte[] buildTextFrame(String payload) throws Exception {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        if (data.length <= 125) {
            out.write(data.length);
        } else if (data.length <= 65535) {
            out.write(126);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((data.length >> (8 * i)) & 0xFF));
            }
        }
        out.write(data);
        return out.toByteArray();
    }

    /**
     * 关闭连接（幂等）：关通道、清理会话表并归还连接预算。
     *
     * <p>accept 失败、握手失败、读帧结束、stop() 都会走到这里，因此必须幂等：
     * 重复归还预算会把活跃连接数打成负数，后续所有新连接都会被限额拒绝。</p>
     *
     * @param conn 连接，不允许为 null
     */
    private void closeConnection(Connection conn) {
        if (!conn.closed.compareAndSet(false, true)) {
            return;
        }
        try {
            conn.close();
        } catch (IOException e) {
            // ignore
        }
        connections.remove(conn);
        String sessionId = conn.sessionId;
        if (sessionId != null) {
            clients.remove(sessionId);
            subscriptions.values().forEach(set -> set.remove(sessionId));
        }
        releaseConnection();
        notifyListener(l -> l.onClientDisconnected(sessionId));
    }

    /**
     * 通知监听器
     * @param action 方法入参 action
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    // ==================== 内部类 ====================

    /**
     * WebSocket 连接
     */
    private static class Connection {
        Socket socket;
        OutputStream out;
        String sessionId;

        /**
         * 关闭标志（幂等收尾用）
         */
        final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /**
         * Connection
         * @param socket 参数
         * @return 返回值
        */
        Connection(Socket socket) {
            this.socket = socket;
            try {
                this.out = socket.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException("获取 Socket 输出流失败: " + e.getMessage(), e);
            }
        }

        void send(String payload) throws Exception {
            byte[] frame = buildTextFrame(payload);
            out.write(frame);
            out.flush();
        }

        void close() throws IOException {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
