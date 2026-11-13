// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.sync.netty;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * 基于 Netty 的 WebSocket 同步客户端实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NettyWebSocketSyncClient implements com.chua.common.support.network.sync.SyncClient {

    /**
     * 客户端标识
    */
    private final String clientId;
    /**
     * 服务端地址
    */
    private final String serverUrl;
    /**
     * 连接
    */
    private volatile boolean connected;
    /**
     * Socket
    */
    private Socket socket;
    /**
     * 输出
    */
    private OutputStream output;
    /**
     * 输入（按字节读：帧负载是二进制，不能过字符解码器）
    */
    private InputStream input;
    /**
     * 单帧负载上限（防畸形长度导致 OOM）
    */
    private static final long MAX_FRAME_BYTES = 64L * 1024 * 1024;
    /**
     * 掩码随机源（客户端帧必须掩码）
    */
    private static final java.security.SecureRandom MASK_RANDOM = new java.security.SecureRandom();
    /**
     * subscriptions
    */
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();
    /**
     * 监听器
    */
    private final List<SyncFlowListener> listeners = Wraps.list();
    /**
     * 接收线程
    */
    private Thread receiveThread;

    /**
     * 创建 nettywebSocket同步客户端 实例
     * @param serverUrl 服务端url
     */
    public NettyWebSocketSyncClient(String serverUrl) {
        this(java.util.UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建 nettywebSocket同步客户端 实例
     * @param clientId 客户端标识
     * @param serverUrl 服务端url
     */
    public NettyWebSocketSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId == null || clientId.isEmpty()
                ? java.util.UUID.randomUUID().toString()
                : clientId;
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
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
            // 必须用构造时传入的地址：原实现把它丢掉、固定连 ws://localhost:8080，
            // 于是无论配置什么地址都连不上（NettyWebSocketSyncFlow 建连直接抛异常）。
            URI uri = URI.create(serverUrl);
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();

            socket = new Socket(host, port);
            output = socket.getOutputStream();
            // 按字节读：帧负载是二进制。原实现走 InputStreamReader 按 UTF-8 解码，
            // 多字节字符的「字节数」与「字符数」对不上，非 ASCII 负载会错位甚至读挂。
            input = new BufferedInputStream(socket.getInputStream());

            String key = Base64.getEncoder()
                    .encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            String request = "GET " + path + " HTTP/1.1\r\n" +
                    "Host: " + host + ":" + port + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + key + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String responseHead = readHandshakeHeader();
            if (responseHead == null || !responseHead.contains("101")) {
                throw new RuntimeException("WebSocket 握手失败: " + responseHead);
            }

            connected = true;
            startReceiveThread();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket 连接失败", e);
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
     * 是否连接
    */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 获取客户端标识
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
            String payload = topic + ":" + message.toString();
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            // 客户端帧必须掩码（RFC 6455），且 >125 字节要写扩展长度：
            // 原实现只写了 7 位长度且不带掩码，标准服务端会直接关连接，大负载也读不出来。
            byte[] frame = new byte[data.length + 14];
            int pos = 0;
            frame[pos++] = (byte) 0x81;
            if (data.length <= 125) {
                frame[pos++] = (byte) (0x80 | data.length);
            } else if (data.length <= 0xFFFF) {
                frame[pos++] = (byte) (0x80 | 126);
                frame[pos++] = (byte) ((data.length >> 8) & 0xFF);
                frame[pos++] = (byte) (data.length & 0xFF);
            } else {
                frame[pos++] = (byte) (0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    frame[pos++] = (byte) ((data.length >> (8 * i)) & 0xFF);
                }
            }
            byte[] mask = new byte[4];
            MASK_RANDOM.nextBytes(mask);
            System.arraycopy(mask, 0, frame, pos, 4);
            pos += 4;
            for (int i = 0; i < data.length; i++) {
                frame[pos + i] = (byte) (data[i] ^ mask[i % 4]);
            }
            output.write(frame, 0, pos + data.length);
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
     * 添加监听器
    */
    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监听器
    */
    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取Metadata
    */
    @Override
    public java.util.Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "protocol", "websocket");
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
        disconnect();
    }

    /**
     * 读握手响应头（读到空行为止）。
     *
     * <p>按字节读到 \r\n\r\n 结束：不能按行读，否则服务端紧随 101 之后发出的首帧
     * 会被行缓冲吞掉。</p>
     *
     * @return 响应头文本；流已关闭时返回 null
     * @throws IOException 读失败
     */
    private String readHandshakeHeader() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int p1 = -1;
        int p2 = -1;
        int p3 = -1;
        int read;
        while ((read = input.read()) >= 0) {
            buffer.write(read);
            if (p1 == '\r' && p2 == '\n' && p3 == '\r' && read == '\n') {
                break;
            }
            p1 = p2;
            p2 = p3;
            p3 = read;
        }
        return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * 开始接收Thread
    */
    private void startReceiveThread() {
// [P3C C1 豁免] 守护/后台线程：有独立生命周期，不适合交给通用执行器
        receiveThread = new Thread(() -> {
            while (connected && socket != null && socket.isConnected()) {
                try {
                    int b0 = input.read();
                    if (b0 < 0) {
                        break;
                    }
                    int opcode = b0 & 0x0F;
                    int b1 = input.read();
                    if (b1 < 0) {
                        break;
                    }
                    boolean masked = (b1 & 0x80) != 0;
                    long length = b1 & 0x7F;
                    if (length == 126) {
                        byte[] ext = input.readNBytes(2);
                        if (ext.length < 2) {
                            break;
                        }
                        length = ((long) (ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                    } else if (length == 127) {
                        // 127 后面是 8 字节（64 位）长度：原来只读 4 字节，读到的全是高位 0
                        byte[] ext = input.readNBytes(8);
                        if (ext.length < 8) {
                            break;
                        }
                        length = 0L;
                        for (byte value : ext) {
                            length = (length << 8) | (value & 0xFF);
                        }
                    }
                    if (length < 0 || length > MAX_FRAME_BYTES) {
                        break;
                    }
                    byte[] maskKey = masked ? input.readNBytes(4) : new byte[0];
                    if (masked && maskKey.length < 4) {
                        break;
                    }
                    byte[] payloadBytes = input.readNBytes((int) length);
                    if (payloadBytes.length < length) {
                        break;
                    }
                    if (masked) {
                        for (int i = 0; i < payloadBytes.length; i++) {
                            payloadBytes[i] = (byte) (payloadBytes[i] ^ maskKey[i % 4]);
                        }
                    }
                    if (opcode == 0x8) {
                        break;
                    }
                    if (opcode == 0x1 || opcode == 0x2) {
                        handleMessage(new String(payloadBytes, StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    if (connected) {
                        notifyListeners(l -> l.onError("receive", e));
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
     * 处理消息
     *
     * @param message 消息
     */
    private void handleMessage(String message) {
        int idx = message.indexOf(':');
        if (idx > 0) {
            String topic = message.substring(0, idx);
            String data = message.substring(idx + 1);
            SyncMessageHandler handler = subscriptions.get(topic);
            if (handler != null) {
                handler.handle(topic, data);
            }
        }
        notifyListeners(l -> l.onMessage(null, message));
    }

    /**
     * 关闭Silently
     *
     * @param s s
     */
    private void closeSilently(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * 通知监听器
     *
     * @param action 动作
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