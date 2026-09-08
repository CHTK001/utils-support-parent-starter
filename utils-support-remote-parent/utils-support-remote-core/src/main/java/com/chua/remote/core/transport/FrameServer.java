package com.chua.remote.core.transport;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FrameServer {

    public static final String META_CLIENT_ID = "clientId";
    private static final String META_KIND = FrameCodec.METADATA_KIND;

    private final ServerSetting setting;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private volatile FrameListener listener;
    private volatile ServerSocketChannel serverChannel;
    private volatile WebSocketServer wsServer;
    private volatile boolean running;

    public FrameServer(ServerSetting setting) {
        this.setting = setting;
    }

    public void setListener(FrameListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), setting.getBacklog());
        } catch (IOException e) {
            throw new RuntimeException("FrameServer 启动失败: " + setting.getHost() + ":" + setting.getPort(), e);
        }
        running = true;
        Thread.ofVirtual().name("remote-frame-accept-" + setting.getPort()).start(this::acceptLoop);
        startWebSocketServer();
        log.info("远控帧服务端已启动 (TCP:{} WS:{})", setting.getPort(), getWsPort());
    }

    public void stop() {
        running = false;
        try { if (serverChannel != null) serverChannel.close(); } catch (IOException ignored) {}
        serverChannel = null;
        if (wsServer != null) {
            try { wsServer.stop(1000); } catch (Exception ignored) {}
            wsServer = null;
        }
        for (Connection connection : connections.values()) { connection.close(); }
        connections.clear();
        log.info("远控帧服务端已停止");
    }

    public void send(String clientId, Frame frame) {
        Connection connection = connections.get(clientId);
        if (connection == null) {
            log.debug("连接不存在，丢弃帧: clientId={}, type={}", clientId, frame.getType());
            return;
        }
        log.info("[帧投递] SEND_TO_CLIENT: clientId={}, type={}, sessionId={}", clientId, frame.getType(), frame.getSessionId());
        connection.write(frame);
    }

    public void publish(Frame frame) {
        for (Connection connection : connections.values()) { connection.write(frame); }
    }

    public List<String> getConnectedClients() {
        return new ArrayList<>(connections.keySet());
    }

    public int getPort() { return setting.getPort(); }
    public int getWsPort() { return setting.getPort() + 2; }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel channel = serverChannel.accept();
                if (channel == null) continue;
                channel.socket().setTcpNoDelay(true);
                Thread.ofVirtual().name("remote-frame-conn").start(() -> serve(channel));
            } catch (IOException e) {
                if (running) log.error("远控帧服务端 accept 异常", e);
            }
        }
    }

    private void serve(SocketChannel channel) {
        String clientId = null;
        try {
            while (running) {
                Frame frame = FrameCodec.readBinary(channel);
                if (clientId == null) {
                    clientId = register(frame, new Connection(channel));
                    if (clientId == null) break;
                    continue;
                }
                FrameListener current = listener;
                if (current != null) {
                    log.info("帧到达分发: clientId={}, type={}", clientId, frame.getType());
                    current.onFrame(clientId, frame);
                }
            }
        } catch (IOException e) {
            log.debug("TCP连接结束: clientId={}, cause={}", clientId, e.getMessage());
        } catch (Exception e) {
            log.warn("TCP连接处理异常: clientId={}", clientId, e);
        } finally {
            if (clientId != null) {
                connections.remove(clientId);
                FrameListener current = listener;
                if (current != null) current.onDisconnected(clientId);
            }
            closeQuietly(channel);
        }
    }

    private String register(Frame frame, Connection connection) {
        if (frame.getType() != MessageType.CTRL || frame.getMetadata() == null
                || frame.getMetadata().get(META_CLIENT_ID) == null) {
            log.warn("首帧非合法 hello，拒绝连接");
            return null;
        }
        String clientId = frame.getMetadata().get(META_CLIENT_ID);
        connections.put(clientId, connection);
        Frame ack = Frame.builder()
                .type(MessageType.CTRL)
                .metadata(Map.of(META_KIND, "hello-ack", META_CLIENT_ID, clientId))
                .build();
        connection.write(ack);
        FrameListener current = listener;
        if (current != null) current.onConnected(clientId);
        log.debug("连接注册成功: clientId={}", clientId);
        return clientId;
    }

    private void startWebSocketServer() {
        int wsPort = getWsPort();
        wsServer = new WebSocketServer(new InetSocketAddress("0.0.0.0", wsPort)) {
            @Override
            public void onOpen(WebSocket ws, ClientHandshake handshake) {
                log.debug("WebSocket新连接: {}", ws.getRemoteSocketAddress());
                Thread.ofVirtual().name("remote-ws-conn").start(() -> serveWebSocket(ws));
            }
            @Override
            public void onClose(WebSocket ws, int code, String reason, boolean remote) {
                log.debug("WebSocket断开: code={}, reason={}", code, reason);
            }
            @Override
            public void onMessage(WebSocket ws, String message) {}
            @Override
            public void onMessage(WebSocket ws, ByteBuffer message) {
                WsSession session = wsSessions.get(ws);
                if (session != null) session.queue.offer(message);
            }
            @Override
            public void onError(WebSocket ws, Exception ex) {
                log.debug("WebSocket异常: {}", ex.getMessage());
                WsSession session = wsSessions.remove(ws);
                if (session != null) session.queue.offer(null);
            }
            @Override
            public void onStart() {
                log.info("WebSocket服务端已启动: port={}", wsPort);
            }
        };
        wsServer.setReuseAddr(true);
        wsServer.start();
    }

    private final Map<WebSocket, WsSession> wsSessions = new ConcurrentHashMap<>();

    private static final class WsSession {
        final LinkedBlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>();
    }

    private void serveWebSocket(WebSocket ws) {
        WsSession session = new WsSession();
        wsSessions.put(ws, session);
        String clientId = null;
        try {
            while (ws.isOpen() && running) {
                ByteBuffer buf = session.queue.poll(5, TimeUnit.SECONDS);
                if (buf == null) continue;
                Frame frame = FrameCodec.parseBinary(buf);
                if (frame == null) break;
                if (clientId == null) {
                    clientId = register(frame, new Connection(ws));
                    if (clientId == null) break;
                    continue;
                }
                FrameListener current = listener;
                if (current != null) current.onFrame(clientId, frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("WebSocket连接处理异常: clientId={}", clientId, e);
        } finally {
            wsSessions.remove(ws);
            if (clientId != null) {
                connections.remove(clientId);
                FrameListener current = listener;
                if (current != null) current.onDisconnected(clientId);
            }
        }
    }

    private void closeQuietly(SocketChannel channel) {
        try { channel.close(); } catch (IOException ignored) {}
    }

    private static final class Connection {
        private final SocketChannel channel;
        private final WebSocket ws;

        private Connection(SocketChannel channel) { this.channel = channel; this.ws = null; }
        private Connection(WebSocket ws) { this.channel = null; this.ws = ws; }

        private void write(Frame frame) {
            byte[] bytes = FrameCodec.encode(frame);
            if (channel != null) {
                synchronized (channel) {
                    try { FrameCodec.writeBinary(channel, frame); }
                    catch (IOException e) { closeQuietly(channel); }
                }
            } else if (ws != null && ws.isOpen()) {
                synchronized (ws) {
                    ws.send(bytes);
                }
            }
        }

        private void close() {
            if (channel != null) closeQuietly(channel);
            if (ws != null) { try { ws.close(); } catch (Exception ignored) {} }
        }

        private static void closeQuietly(SocketChannel channel) {
            if (channel != null) { try { channel.close(); } catch (IOException ignored) {} }
        }
    }

    public interface FrameListener {
        void onFrame(String clientId, Frame frame);
        default void onConnected(String clientId) {}
        default void onDisconnected(String clientId) {}
    }
}
