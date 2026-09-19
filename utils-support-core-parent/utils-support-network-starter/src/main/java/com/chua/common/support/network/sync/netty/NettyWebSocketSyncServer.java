package com.chua.common.support.network.sync.netty;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.spi.annotations.Spi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Netty 的 WebSocket 同步服务端实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("netty-websocket")
public class NettyWebSocketSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer {

    /** 服务器Socket */
    private ServerSocket serverSocket;
    /** 执行器 */
    private ExecutorService executor;
    /** Connections */
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    /** 客户端 */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    /** subscriptions */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    /** 监听器 */
    private final List<SyncServerListener> listeners = new ArrayList<>();
    /** connectionidseq */
    private final AtomicInteger connectionIdSeq = new AtomicInteger();

    /**
    * 创建 nettywebSocket同步服务端 实例
    * @param setting setting
    */
    public NettyWebSocketSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** 执行开始 */
    protected void doStart() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(setting.getHost(), setting.getPort()));
            executor = Executors.newCachedThreadPool(r -> new Thread(r, "netty-ws-" + r.hashCode()));
            executor.submit(this::acceptLoop);
        } catch (Exception e) {
            throw new RuntimeException("Netty WebSocket SyncServer 启动失败", e);
        }
    }

    @Override
    /** 执行停止 */
    protected void doStop() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }
        for (Connection c : connections) {
            try {
                c.close();
            } catch (IOException e) {
                // ignore
            }
        }
        connections.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
        clients.clear();
        subscriptions.clear();
    }

    @Override
    /** 发布 */
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

    @Override
    /** 发送 */
    public void send(String clientId, String topic, Object message) {
        String payload = topic + ":" + message.toString();
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

    @Override
    /** 获取连接客户端 */
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    /** 获取客户端metadata */
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    @Override
    /** 添加监听器 */
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除监听器 */
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.WS;
    }

    /** accept循环 */
    private void acceptLoop() {
        while (!serverSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket socket = serverSocket.accept();
                Connection conn = new Connection(socket);
                connections.add(conn);
                String sessionId = "netty-ws-" + connectionIdSeq.incrementAndGet();
                conn.sessionId = sessionId;
                clients.put(sessionId, new HashMap<>());
                notifyListener(l -> l.onClientConnected(sessionId, clients.get(sessionId)));
                executor.submit(() -> handleConnection(conn));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
            }
        }
    }

    /**
     * 处理Connection
     *
     * @param conn conn
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
     * 执行handshake
     *
     * @param conn conn
     * @return 执行handshake的结果
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
     * 读取帧
     *
     * @param conn conn
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
     * computewebSocketaccept
     *
     * @param key 键
     * @return computewebSocketaccept的结果
     */
    private String computeWebSocketAccept(String key) throws Exception {
        String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(combined.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(digest);
    }

    /**
     * 构建文本帧
     *
     * @param payload payload
     * @return 构建文本帧的结果
     */
    private static byte[] buildTextFrame(String payload) throws Exception {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        if (data.length <= 125) {
            out.write(0x80 | data.length);
        } else if (data.length <= 65535) {
            out.write(0x80 | 126);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((data.length >> (8 * i)) & 0xFF));
            }
        }
        byte[] maskKey = new byte[4];
        new java.security.SecureRandom().nextBytes(maskKey);
        out.write(maskKey);
        for (int i = 0; i < data.length; i++) {
            out.write(data[i] ^ maskKey[i % 4]);
        }
        return out.toByteArray();
    }

    /**
     * 关闭Connection
     *
     * @param conn conn
     */
    private void closeConnection(Connection conn) {
        try {
            conn.close();
        } catch (IOException e) {
            // ignore
        }
        connections.remove(conn);
        String sessionId = conn.sessionId;
        clients.remove(sessionId);
        subscriptions.values().forEach(set -> set.remove(sessionId));
        notifyListener(l -> l.onClientDisconnected(sessionId));
    }

    /**
     * 通知监听器
     *
     * @param action 动作
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
    /**
     * Connection类。
     *
     * @author CH
     * @since 4.0.0
     */
            }
        }
    }

    private static class Connection {
        Socket socket; // Socket
        OutputStream out; // 出
        String sessionId; // 会话标识

        Connection(Socket socket) {
            this.socket = socket;
            try {
                this.out = socket.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
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
