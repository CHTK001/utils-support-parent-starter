package com.chua.common.support.network.sync.impl;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.utils.ThreadUtils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
     * 订阅的主题映射（topic -> handler）
     */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new ArrayList<>();

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

    @Override
    /** 连接 */
    public void connect() {
        if (connected) {
            return;
        }
        try {
            doConnect();
            connected = true;
            reconnectCount.set(0);
            startReceiveThread();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket 连接失败", e);
        }
    }

    @Override
    /** 断开 */
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

    @Override
    /** 是否Connected */
    public boolean isConnected() {
        return connected;
    }

    @Override
    /** 获取ClientId */
    public String getClientId() {
        return clientId;
    }

    @Override
    /** 发送 */
    public void send(String topic, Object message) {
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
        try {
            String messageBody = message instanceof String value ? value : Json.toJson(message);
            String payload = topic + ":" + messageBody;
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
            output.write(out.toByteArray());
            output.flush();
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败", e);
        }
    }

    @Override
    /** 订阅 */
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    @Override
    /** 添加Listener */
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除Listener */
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取Metadata */
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", "websocket");
    }

    @Override
    /** 关闭 */
    public void close() {
        disconnect();
    }

    // ==================== 连接管理 ====================

    /** Do连接 */
    private void doConnect() throws Exception {
        String url = serverUrl;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url;
        }
        URI uri = URI.create(url);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equals("wss") ? 443 : 80);
        String path = uri.getPath().isEmpty() ? "/" : uri.getPath();

        socket = new Socket(host, port);
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

    /** 开始接收Thread */
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

    /** 停止接收Thread */
    private void stopReceiveThread() {
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
    }

    /** 读取Frames */
    private void readFrames() throws IOException {
        InputStream in = socket.getInputStream();
        while (connected && !socket.isClosed() && !Thread.currentThread().isInterrupted()) {
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
                length = (in.read() << 8) | in.read();
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
                SyncMessageHandler handler = subscriptions.get(finalTopic);
                if (handler != null) {
                    handler.handle(finalTopic, finalBody);
                }
                notifyListeners(l -> l.onMessage(null, text));
            }
        }
    }

    // ==================== 重连 ====================

    /** AttemptReconnect */
    private void attemptReconnect() {
        if (MAX_RECONNECT > 0 && reconnectCount.incrementAndGet() > MAX_RECONNECT) {
            return;
        }
        try {
            Thread.sleep(RECONNECT_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!connected) {
            return;
        }
        try {
            closeSilently(socket);
            doConnect();
            startReceiveThread();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            // ignore
        }
    }

    // ==================== 工具方法 ====================

    /** 关闭Silently */
    private void closeSilently(Socket s) {
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /** 通知Listeners */
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
