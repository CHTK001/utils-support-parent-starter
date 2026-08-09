package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于 JDK ServerSocket 的 TCP 同步服务端实现。
 * <p>
 * 提供主题发布、客户端注册与消息下行推送等能力。
 * </p>
 *
 * @author CH
 * @since 2026-07-25
 */
@Spi("tcp")
public class TcpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 客户端注册表（clientId -> 连接）
     */
    private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * TCP 服务器
     */
    private ServerSocket server;

    /**
     * 接收线程
     */
    private Thread acceptThread;

    /**
     * 创建 TCP 同步服务端。
     *
     * @param setting 服务端配置
     */
    public TcpSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public String getProtocol() {
        return "tcp";
    }

    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new TcpSyncServer(setting);
    }

    @Override
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : "tcp://127.0.0.1:19390";
        return new TcpSyncClient(url);
    }

    @Override
    protected void doStart() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), setting.getBacklog());
            acceptThread = new Thread(this::acceptLoop, "tcp-sync-accept-" + setting.getPort());
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            throw new RuntimeException("TCP SyncServer 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
        for (ClientConnection connection : clients.values()) {
            connection.close();
        }
        clients.clear();
    }

    @Override
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message;
        for (ClientConnection connection : clients.values()) {
            connection.write(payload);
        }
    }

    @Override
    public void send(String clientId, String topic, Object message) {
        ClientConnection connection = clients.get(clientId);
        if (connection == null) {
            return;
        }
        connection.write(topic + ":" + message);
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    @Override
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        ClientConnection connection = clients.get(clientId);
        return connection != null ? Collections.unmodifiableMap(connection.metadata) : Collections.emptyMap();
    }

    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
     * 接受客户端连接循环。
     */
    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket socket = server.accept();
                ClientConnection connection = new ClientConnection(socket);
                connection.start();
            } catch (IOException e) {
                if (server != null && !server.isClosed()) {
                    notifyListener(l -> l.onError(null, e));
                }
                break;
            }
        }
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 客户端连接封装。
     *
     * @author CH
     */
    private final class ClientConnection {

        /**
         * 底层 Socket
         */
        private final Socket socket;

        /**
         * 客户端标识
         */
        private volatile String clientId;

        /**
         * 客户端元数据
         */
        private final Map<String, Object> metadata = new HashMap<>();

        /**
         * 接收线程
         */
        private Thread readThread;

        /**
         * 创建客户端连接。
         *
         * @param socket 底层 Socket
         */
        private ClientConnection(Socket socket) {
            this.socket = socket;
        }

        /**
         * 启动接收线程。
         */
        void start() {
            readThread = new Thread(this::readLoop, "tcp-sync-client-" + socket.getRemoteSocketAddress());
            readThread.setDaemon(true);
            readThread.start();
        }

        /**
         * 读取消息循环。
         */
        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException e) {
                notifyListener(l -> l.onError(clientId, e));
            } finally {
                unregister();
            }
        }

        /**
         * 处理一行消息。
         *
         * @param line 消息行
         */
        private void handleLine(String line) {
            String message = line.trim();
            if (message.isEmpty()) {
                return;
            }
            int colon = message.indexOf(':');
            String topic = colon > 0 ? message.substring(0, colon) : "";
            String payload = colon > 0 ? message.substring(colon + 1) : message;
            if ("register".equals(topic)) {
                clientId = payload;
                metadata.put("clientId", payload);
                clients.put(payload, this);
                write("registered:" + payload);
                notifyListener(l -> l.onClientConnected(payload, metadata));
            } else {
                notifyListener(l -> l.onMessage(clientId, topic, payload));
            }
        }

        /**
         * 写入一行消息。
         *
         * @param payload 消息内容
         */
        void write(String payload) {
            try {
                OutputStream out = socket.getOutputStream();
                out.write((payload + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
            }
        }

        /**
         * 注销连接。
         */
        private void unregister() {
            if (clientId != null) {
                clients.remove(clientId);
                notifyListener(l -> l.onClientDisconnected(clientId));
            }
            close();
        }

        /**
         * 关闭连接。
         */
        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
