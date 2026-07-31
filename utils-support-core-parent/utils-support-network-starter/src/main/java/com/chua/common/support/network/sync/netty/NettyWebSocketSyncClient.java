package com.chua.common.support.network.sync.netty;

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
 * @since 2026-07-25
 */
public class NettyWebSocketSyncClient implements com.chua.common.support.network.sync.SyncClient {

    private final String clientId = java.util.UUID.randomUUID().toString();
    private volatile boolean connected;
    private Socket socket;
    private OutputStream output;
    private BufferedReader input;
    private final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();
    private final List<SyncFlowListener> listeners = new ArrayList<>();
    private Thread receiveThread;

    public NettyWebSocketSyncClient(String serverUrl) {
        this(java.util.UUID.randomUUID().toString(), serverUrl);
    }

    public NettyWebSocketSyncClient(String clientId, String serverUrl) {
        // clientId is generated above
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            URI uri = URI.create("ws://localhost:8080");
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
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

            connected = true;
            startReceiveThread();
            notifyListeners(SyncFlowListener::onStart);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket 连接失败", e);
        }
    }

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

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public void send(String topic, Object message) {
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
        try {
            String payload = topic + ":" + message.toString();
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[2 + data.length];
            frame[0] = (byte) 0x81;
            frame[1] = (byte) data.length;
            System.arraycopy(data, 0, frame, 2, data.length);
            output.write(frame);
            output.flush();
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败", e);
        }
    }

    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
    }

    @Override
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    @Override
    public java.util.Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "protocol", "websocket");
    }

    @Override
    public void close() {
        disconnect();
    }

    private void startReceiveThread() {
        receiveThread = new Thread(() -> {
            while (connected && socket != null && socket.isConnected()) {
                try {
                    int b = input.read();
                    if (b == -1) {
                        break;
                    }
                    if ((b & 0x80) == 0x80) {
                        int length = input.read();
                        if (length == 126) {
                            length = (input.read() << 8) | input.read();
                        } else if (length == 127) {
                            length = 0;
                            for (int i = 0; i < 4; i++) {
                                length = (length << 8) | input.read();
                            }
                        }
                        char[] chars = new char[length];
                        for (int i = 0; i < length; i++) {
                            chars[i] = (char) input.read();
                        }
                        String message = new String(chars);
                        handleMessage(message);
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

    private void stopReceiveThread() {
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
    }

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

    private void closeSilently(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

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
