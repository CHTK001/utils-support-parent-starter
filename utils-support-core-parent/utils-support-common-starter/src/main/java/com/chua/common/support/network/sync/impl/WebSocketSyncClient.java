// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.sync.SyncAddressResolver;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.network.sync.SyncSetting;
import com.chua.common.support.utils.ThreadUtils;

/**
 * 基于 JDK Socket 的 WebSocket 同步客户端实现。
 * <p>
 * 通过 WebSocket 握手建立连接，使用原生 Socket 通信。
 * 内置自动重连机制。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
public class WebSocketSyncClient implements com.chua.common.support.network.sync.SyncClient {

    /**
     * 客户端标识
     */
    private final String clientId;

    /**
     * 服务端地址
     */
    private final String serverUrl;

    /**
     * WebSocket 连接
     */
    private Socket socket;

    /**
     * 输出流
     */
    private OutputStream output;

    /**
     * 输入流
     */
    private BufferedReader input;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 服务端注册确认（register/registered 握手完成）
     */
    private volatile boolean registered;

    /**
     * 订阅的主题映射（topic -> handler）
     */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = Wraps.list();

    /**
     * 接收线程
     */
    private Thread receiveThread;

    /**
     * 重连次数
     */
    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    /**
     * 最大重连次数（0 表示无限重连）
     */
    private static final int MAX_RECONNECT = 0;

    /**
     * 重连间隔（毫秒）
     */
    private static final long RECONNECT_INTERVAL = 3000;

    /**
     * 掩码随机源（进程级复用：SecureRandom 构造涉及 Provider 查找与熵源初始化，
     * 禁止每条消息新建；{@code nextBytes} 本身线程安全，掩码随机性不变）
     */
    private static final SecureRandom MASK_RANDOM = new SecureRandom();

    /**
     * 创建 WebSocket 同步客户端。
     *
     * @param serverUrl 服务端地址，如 ws://localhost:8080
     */
    public WebSocketSyncClient(String serverUrl) {
        this(java.util.UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 WebSocket 同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    public WebSocketSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    /**
     * 创建 WebSocket 同步客户端，服务端地址由服务发现解析。
     * <p>发现不到时回落到 {@link SyncSetting#getServerUrl()}。</p>
     *
     * @param clientId 客户端标识
     * @param setting  同步通道配置
     */
    public WebSocketSyncClient(String clientId, SyncSetting setting) {
        this(clientId, SyncAddressResolver.resolve(setting));
    }

    /**
     * 连接
     */
    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            doConnect();
            connected = true;
            reconnectCount.set(0);
            startReceiveThread();
            registerSelf();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket 连接失败", e);
        }
    }

    /**
     * 向服务端登记客户端标识：服务端以该标识为键提供定向投递（send(clientId, ...)），
     * 与 TCP/UDP 传输的 register/registered 握手同口径。
     */
    private void registerSelf() {
        registered = false;
        send("register", clientId);
        waitRegistered();
    }

    /**
     * 等待服务端注册确认，保证 connect() 返回后即可被定向投递。
     */
    private void waitRegistered() {
        // [P3C A20 豁免]
        long deadline = System.currentTimeMillis() + 3000L;
        while (!registered && System.currentTimeMillis() < deadline) {
            // [P3C A20 豁免]
            ThreadUtils.sleep(10L);
        }
    }

    /**
     * 断开
     */
    @Override
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        stopReceiveThread();
        closeSilently(socket);
        subscriptions.clear();
        notifyListeners(SyncFlowListener::onStop);
    }

    /**
     * 是否Connected
     */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 获取ClientId
     */
    @Override
    public String getClientId() {
        return clientId;
    }

    /**
     * 发送
     */
    @Override
    public void send(String topic, Object message) {
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
        try {
            String messageBody = message instanceof String value ? value : Json.toJson(message);
            String payload = topic + ":" + messageBody;
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            // 精确分配帧缓冲并原地去掩码：省去 ByteArrayOutputStream 扩容、
            // toByteArray() 全量拷贝与逐字节 write
            int headerLen = data.length <= 125 ? 2 : (data.length <= 65535 ? 4 : 10);
            byte[] frame = new byte[headerLen + 4 + data.length];
            frame[0] = (byte) 0x81;
            int pos;
            if (data.length <= 125) {
                frame[1] = (byte) (0x80 | data.length);
                pos = 2;
            } else if (data.length <= 65535) {
                frame[1] = (byte) (0x80 | 126);
                frame[2] = (byte) ((data.length >> 8) & 0xFF);
                frame[3] = (byte) (data.length & 0xFF);
                pos = 4;
            } else {
                frame[1] = (byte) (0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    frame[2 + (7 - i)] = (byte) ((data.length >> (8 * i)) & 0xFF);
                }
                pos = 10;
            }
            byte[] maskKey = new byte[4];
            MASK_RANDOM.nextBytes(maskKey);
            System.arraycopy(maskKey, 0, frame, pos, 4);
            for (int i = 0; i < data.length; i++) {
                frame[pos + 4 + i] = (byte) (data[i] ^ maskKey[i & 3]);
            }
            output.write(frame);
            output.flush();
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败", e);
        }
    }

    /**
     * 订阅
     */
    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
    }

    /**
     * 取消订阅
     */
    @Override
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    /**
     * 添加Listener
     */
    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除Listener
     */
    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取Metadata
     */
    @Override
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "websocket");
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        disconnect();
    }

    // ==================== 连接管理 ====================

    /**
     * Do连接
     * @return 返回值
     * @throws Exception 异常
    */
    private void doConnect() throws Exception {
        String url = serverUrl;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url;
        }
        URI uri = URI.create(url);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equals("wss") ? 443 : 80);
        String path = uri.getPath().isEmpty() ? "/" : uri.getPath();

        if ("wss".equals(uri.getScheme())) {
            // wss 必须走 TLS：此前对 wss 也建明文 Socket，连 443 端口后 TLS 握手必然失败
            SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
            sslSocket.startHandshake();
            socket = sslSocket;
        } else {
            socket = new Socket(host, port);
        }
        output = socket.getOutputStream();
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        String key = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "\r\n";
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();

        String responseLine = input.readLine();
        if (responseLine == null || !responseLine.contains("101")) {
            throw new RuntimeException("WebSocket 握手失败: " + responseLine);
        }
    }

    // ==================== 接收线程 ====================

    /**
     * 开始接收Thread
     */
    private void startReceiveThread() {
        receiveThread = ThreadUtils.newThread(() -> {
            while (connected && socket != null && !socket.isClosed()) {
                try {
                    readFrames();
                } catch (Exception e) {
                    if (connected) {
                        notifyListeners(l -> l.onError("receive", e));
                        attemptReconnect();
                    }
                    break;
                }
            }
        }, "ws-sync-receive-" + clientId);
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /**
     * 停止接收Thread
     */
    private void stopReceiveThread() {
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
    }

    /**
     * 读取Frames
     * @return 返回值
     * @throws IOException 异常
    */
    private void readFrames() throws IOException {
        InputStream in = socket.getInputStream();
        byte[] header = new byte[2];
        byte[] ext = new byte[8];
        byte[] maskKey = new byte[4];
        while (connected && !socket.isClosed() && !Thread.currentThread().isInterrupted()) {
            // 批量读帧头：原来一帧 2~14 次逐字节 read()（每字节一次系统调用），合并为 1~3 次
            if (!readFully(in, header, 2)) {
                break;
            }
            int b0 = header[0] & 0xFF;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            int b1 = header[1] & 0xFF;
            boolean masked = (b1 & 0x80) != 0;
            int length = b1 & 0x7F;
            if (length == 126) {
                if (!readFully(in, ext, 2)) {
                    throw new EOFException();
                }
                length = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
            } else if (length == 127) {
                if (!readFully(in, ext, 4)) {
                    throw new EOFException();
                }
                length = 0;
                for (int i = 0; i < 4; i++) {
                    length = (length << 8) | (ext[i] & 0xFF);
                }
            }
            if (masked && !readFully(in, maskKey, 4)) {
                throw new EOFException();
            }
            byte[] payload = new byte[length];
            int off = 0;
            while (off < length) {
                int r = in.read(payload, off, length - off);
                if (r < 0) {
                    throw new EOFException();
                }
                off += r;
            }
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i & 3];
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
                if ("registered".equals(finalTopic)) {
                    // 服务端注册确认：置位后 connect()/重连的等待循环即可返回
                    registered = true;
                    return;
                }
                SyncMessageHandler handler = subscriptions.get(finalTopic);
                if (handler != null) {
                    handler.handle(finalTopic, finalBody);
                }
                notifyMessageListeners(text);
            }
        }
    }

    /**
     * 批量读满指定长度；流已结束时返回 false（用于帧头首字节判 EOF）。
     * 中途 EOF 抛 {@link EOFException}，由接收线程统一按连接异常处理（通知 + 重连）。
     */
    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) {
                return false;
            }
            off += r;
        }
        return true;
    }

    // ==================== 重连 ====================

    /**
     * AttemptReconnect
     */
    private void attemptReconnect() {
        if (MAX_RECONNECT > 0 && reconnectCount.incrementAndGet() > MAX_RECONNECT) {
            return;
        }
        ThreadUtils.sleep(RECONNECT_INTERVAL);
        if (!connected) {
            return;
        }
        try {
            closeSilently(socket);
            doConnect();
            startReceiveThread();
            // 新连接对服务端而言是陌生会话，必须重新登记 clientId，否则定向投递找不到连接
            registerSelf();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            // ignore
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 关闭Silently
     * @param s 方法入参 s
     */
    private void closeSilently(Socket s) {
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * 通知消息监听器（每消息热路径专用：避免每条消息分配捕获型 lambda）
     * @param message 消息文本
     */
    private void notifyMessageListeners(String message) {
        for (SyncFlowListener listener : listeners) {
            try {
                listener.onMessage(null, message);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * 通知Listeners
     * @param action 方法入参 action
     */
    private void notifyListeners(java.util.function.Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
