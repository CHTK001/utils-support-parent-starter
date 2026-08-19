package com.chua.common.support.network.sip;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * SIP 信令服务器，内部同时支持 TCP 与 KCP 两种长连接传输。
 *
 * <p>本服务器复用 {@code com.chua.common.support.network.sync} 的传输层实现
 * （TCP 使用 {@code TcpSyncServer}，KCP 使用 {@code KcpSyncServer}，通过 SPI 按
 * {@code "tcp"} / {@code "kcp"} 加载），在其之上提供注册、寻址与消息中继能力。</p>
 *
 * <h2>工作方式</h2>
 * <ul>
 *   <li>客户端（A、B）与服务器建立长连接并通过 {@code sip/register} 注册，上报可达地址</li>
 *   <li>任意客户端通过 {@code sip/find} 查询对端地址</li>
 *   <li>A 通过 {@code sip/msg} 携带 B 的标识发送消息，服务器按注册表路由到 B 的长连接</li>
 *   <li>B 通过 {@code sip/resp} 回传响应，服务器转发回 A</li>
 * </ul>
 *
 * <p>转发基于注册表路由（走客户端既有长连接），不建立到 IP:PORT 的新连接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipServer {

    /**
     * 客户端注册表（clientId -> 会话信息）
     */
    private final Map<String, SipPeer> registry = new ConcurrentHashMap<>();

    /**
     * 隧道服务注册表（serviceName -> 服务提供方 clientId）
     */
    private final Map<String, String> tunnelServices = new ConcurrentHashMap<>();

    /**
     * 隧道通道路由表（channelId -> 通道两端）
     */
    private final Map<String, TunnelChannel> tunnelChannels = new ConcurrentHashMap<>();

    /**
     * 客户端连接回调列表
     */
    private final List<Consumer<String>> connectListeners = new CopyOnWriteArrayList<>();

    /**
     * 客户端断开回调列表
     */
    private final List<Consumer<String>> disconnectListeners = new CopyOnWriteArrayList<>();

    /**
     * 服务器配置
     */
    private final SipConfig config;

    /**
     * TCP 传输实例
     */
    private SyncServer tcpServer;

    /**
     * KCP 传输实例
     */
    private SyncServer kcpServer;

    /**
     * 是否正在运行
     */
    private volatile boolean running;

    /**
     * 使用默认配置创建 SIP 服务器。
     */
    public SipServer() {
        this(SipConfig.defaults());
    }

    /**
     * 使用指定配置创建 SIP 服务器。
     *
     * @param config 服务器配置
     */
    public SipServer(SipConfig config) {
        this.config = config;
    }

    /**
     * 启动 SIP 服务器（按配置启动 TCP 与 KCP 传输）。
     *
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer start() {
        if (running) {
            return this;
        }
        if (config.isTcpEnabled()) {
            startTransport("tcp", config.getTcpPort());
        }
        if (config.isKcpEnabled()) {
            startTransport("kcp", config.getKcpPort());
        }
        running = true;
        log.info("SIP 服务器启动完成: tcp={}, kcp={}", config.isTcpEnabled(), config.isKcpEnabled());
        return this;
    }

    /**
     * 启动指定类型的传输。
     *
     * @param type 传输类型（tcp / kcp）
     * @param port 监听端口
     */
    private void startTransport(String type, int port) {
        try {
            ServerSetting setting = ServerSetting.defaults();
            setting.setHost(config.getHost());
            setting.setPort(port);
            setting.setProtocol(type);
            SyncServer transport = ServiceProvider.of(SyncServer.class).getNewExtension(type, setting);
            if (transport == null) {
                log.warn("SIP 传输 [{}] 未找到 SPI 实现，已跳过", type);
                return;
            }
            transport.addListener(new SipTransportListener(transport));
            transport.start();
            if ("tcp".equals(type)) {
                tcpServer = transport;
            } else {
                kcpServer = transport;
            }
            log.info("SIP 传输 [{}] 启动成功: {}:{}", type, config.getHost(), port);
        } catch (Exception e) {
            log.warn("SIP 传输 [{}] 启动失败（请确认依赖是否齐全）: {}", type, e.getMessage());
        }
    }

    /**
     * 停止 SIP 服务器。
     *
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer stop() {
        if (!running) {
            return this;
        }
        running = false;
        if (tcpServer != null) {
            try {
                tcpServer.stop();
            } catch (Exception ignored) {
            }
        }
        if (kcpServer != null) {
            try {
                kcpServer.stop();
            } catch (Exception ignored) {
            }
        }
        registry.clear();
        tunnelServices.clear();
        tunnelChannels.clear();
        log.info("SIP 服务器已停止");
        return this;
    }

    /**
     * 判断服务器是否正在运行。
     *
     * @return true 表示正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取服务器配置。
     *
     * @return 配置实例
     */
    public SipConfig getConfig() {
        return config;
    }

    /**
     * 获取当前已注册的客户端标识列表。
     *
     * @return 客户端标识列表
     */
    public List<String> getConnectedClients() {
        return new ArrayList<>(registry.keySet());
    }

    /**
     * 获取指定客户端的可达地址。
     *
     * @param clientId 客户端标识
     * @return 形如 {@code host:port} 的地址，未注册时返回 null
     */
    public String getClientAddress(String clientId) {
        SipPeer peer = registry.get(clientId);
        return peer != null ? peer.host() + ":" + peer.port() : null;
    }

    /**
     * 向所有已注册客户端广播消息。
     *
     * @param topic   主题
     * @param message 消息内容
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer publish(String topic, Object message) {
        String payload = topic + SipProtocol.SEPARATOR + message;
        for (String clientId : registry.keySet()) {
            SipPeer peer = registry.get(clientId);
            if (peer != null) {
                peer.transport().send(clientId, SipProtocol.CMD_PUSH, payload);
            }
        }
        return this;
    }

    /**
     * 向指定客户端定向推送消息。
     *
     * @param clientId 目标客户端标识
     * @param topic    主题
     * @param message  消息内容
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer push(String clientId, String topic, Object message) {
        SipPeer peer = registry.get(clientId);
        if (peer != null) {
            peer.transport().send(clientId, SipProtocol.CMD_PUSH,
                    topic + SipProtocol.SEPARATOR + message);
        }
        return this;
    }

    /**
     * 注册客户端连接回调。
     *
     * @param listener 连接回调，参数为客户端标识
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer onConnect(Consumer<String> listener) {
        connectListeners.add(listener);
        return this;
    }

    /**
     * 注册客户端断开回调。
     *
     * @param listener 断开回调，参数为客户端标识
     * @return 当前服务器实例，支持链式调用
     */
    public SipServer onDisconnect(Consumer<String> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    /**
     * 处理信令消息。
     *
     * @param transport 来源传输实例
     * @param clientId  客户端标识
     * @param topic     消息主题
     * @param message   消息内容
     */
    private void handleSignal(SyncServer transport, String clientId, String topic, Object message) {
        String payload = message != null ? message.toString() : "";
        switch (topic) {
            case SipProtocol.CMD_REGISTER -> handleRegister(transport, clientId, payload);
            case SipProtocol.CMD_FIND -> handleFind(transport, clientId, payload);
            case SipProtocol.CMD_MSG -> handleMsg(transport, clientId, payload);
            case SipProtocol.CMD_RESP -> handleResp(transport, clientId, payload);
            case SipProtocol.CMD_TUNNEL_REGISTER -> handleTunnelRegister(transport, clientId, payload);
            case SipProtocol.CMD_TUNNEL_OPEN -> handleTunnelOpen(transport, clientId, payload);
            case SipProtocol.CMD_TUNNEL_DATA -> handleTunnelData(transport, clientId, payload);
            case SipProtocol.CMD_TUNNEL_CLOSE -> handleTunnelClose(transport, clientId, payload);
            default -> log.debug("忽略未知 SIP 信令: {}", topic);
        }
    }

    /**
     * 处理客户端注册，登记可达地址。
     *
     * @param transport 来源传输实例
     * @param clientId  客户端标识
     * @param payload   报文内容（host|port）
     */
    private void handleRegister(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|");
        String host = parts.length > 0 ? parts[0] : "";
        int port = parsePort(parts);
        registry.put(clientId, new SipPeer(transport, host, port, System.currentTimeMillis()));
        transport.send(clientId, SipProtocol.CMD_REGISTERED, clientId);
        notifyConnectListeners(clientId);
    }

    /**
     * 处理对端地址查询。
     *
     * @param transport 来源传输实例
     * @param clientId  查询方客户端标识
     * @param payload   报文内容（requestId|peerId）
     */
    private void handleFind(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|");
        String requestId = parts[0];
        String peerId = parts.length > 1 ? parts[1] : "";
        SipPeer peer = registry.get(peerId);
        if (peer != null) {
            transport.send(clientId, SipProtocol.CMD_FOUND,
                    SipProtocol.found(requestId, peerId, peer.host(), peer.port()));
        } else {
            transport.send(clientId, SipProtocol.CMD_NOTFOUND, SipProtocol.notFound(requestId, peerId));
        }
    }

    /**
     * 处理消息转发。
     *
     * @param transport 来源传输实例
     * @param clientId  发送方客户端标识
     * @param payload   报文内容（toId|content）
     */
    private void handleMsg(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|", 2);
        String toId = parts[0];
        String content = parts.length > 1 ? parts[1] : "";
        SipPeer peer = registry.get(toId);
        if (peer != null) {
            peer.transport().send(toId, SipProtocol.CMD_MSG, SipProtocol.msg(clientId, content));
        } else {
            transport.send(clientId, SipProtocol.CMD_NOTFOUND, SipProtocol.notFound("", toId));
        }
    }

    /**
     * 处理响应回传。
     *
     * @param transport 来源传输实例
     * @param clientId  响应方客户端标识
     * @param payload   报文内容（toId|requestId|content）
     */
    private void handleResp(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|", 3);
        String toId = parts[0];
        String requestId = parts.length > 1 ? parts[1] : "";
        String content = parts.length > 2 ? parts[2] : "";
        SipPeer peer = registry.get(toId);
        if (peer != null) {
            peer.transport().send(toId, SipProtocol.CMD_RESP,
                    SipProtocol.resp(clientId, requestId, content));
        } else {
            transport.send(clientId, SipProtocol.CMD_NOTFOUND, SipProtocol.notFound("", toId));
        }
    }

    /**
     * 处理隧道服务注册，登记服务提供方。
     *
     * @param transport 来源传输实例
     * @param clientId  服务提供方客户端标识
     * @param payload   报文内容（serviceName）
     */
    private void handleTunnelRegister(SyncServer transport, String clientId, String payload) {
        String serviceName = payload.trim();
        if (serviceName.isEmpty()) {
            return;
        }
        tunnelServices.put(serviceName, clientId);
        transport.send(clientId, SipProtocol.CMD_TUNNEL_REGISTERED, serviceName);
        log.info("SIP 隧道服务注册: {} -> {}", serviceName, clientId);
    }

    /**
     * 处理隧道开启请求，在访问方与服务提供方之间建立通道。
     *
     * @param transport 来源传输实例
     * @param clientId  访问方客户端标识
     * @param payload   报文内容（requestId|serviceName）
     */
    private void handleTunnelOpen(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|", 2);
        String requestId = parts[0];
        String serviceName = parts.length > 1 ? parts[1] : "";
        String providerId = tunnelServices.get(serviceName);
        if (providerId == null || providerId.equals(clientId)) {
            transport.send(clientId, SipProtocol.CMD_TUNNEL_ERROR,
                    requestId + SipProtocol.SEPARATOR + "service not found: " + serviceName);
            return;
        }
        SipPeer provider = registry.get(providerId);
        if (provider == null) {
            transport.send(clientId, SipProtocol.CMD_TUNNEL_ERROR,
                    requestId + SipProtocol.SEPARATOR + "provider offline: " + serviceName);
            return;
        }
        String channelId = UUID.randomUUID().toString();
        tunnelChannels.put(channelId, new TunnelChannel(clientId, providerId));
        provider.transport().send(providerId, SipProtocol.CMD_TUNNEL_OPEN,
                channelId + SipProtocol.SEPARATOR + serviceName);
        transport.send(clientId, SipProtocol.CMD_TUNNEL_OPENED,
                requestId + SipProtocol.SEPARATOR + channelId);
        log.info("SIP 隧道建立: {} <-> {} via {}", clientId, providerId, channelId);
    }

    /**
     * 处理隧道数据帧，转发给通道对端。
     *
     * @param transport 来源传输实例
     * @param clientId  发送方客户端标识
     * @param payload   报文内容（channelId|data）
     */
    private void handleTunnelData(SyncServer transport, String clientId, String payload) {
        String[] parts = payload.split("\\|", 2);
        String channelId = parts[0];
        String data = parts.length > 1 ? parts[1] : "";
        TunnelChannel channel = tunnelChannels.get(channelId);
        if (channel == null) {
            return;
        }
        String targetId = channel.targetOf(clientId);
        if (targetId == null) {
            return;
        }
        SipPeer target = registry.get(targetId);
        if (target != null) {
            target.transport().send(targetId, SipProtocol.CMD_TUNNEL_DATA,
                    channelId + SipProtocol.SEPARATOR + data);
        }
    }

    /**
     * 处理隧道关闭，通知通道对端并清理路由。
     *
     * @param transport 来源传输实例
     * @param clientId  关闭方客户端标识
     * @param payload   报文内容（channelId）
     */
    private void handleTunnelClose(SyncServer transport, String clientId, String payload) {
        String channelId = payload.trim();
        TunnelChannel channel = tunnelChannels.remove(channelId);
        if (channel == null) {
            return;
        }
        String targetId = channel.targetOf(clientId);
        if (targetId != null) {
            SipPeer target = registry.get(targetId);
            if (target != null) {
                target.transport().send(targetId, SipProtocol.CMD_TUNNEL_CLOSE, channelId);
            }
        }
        log.info("SIP 隧道关闭: {} ({})", channelId, clientId);
    }

    /**
     * 解析端口号。
     *
     * @param parts 报文字段
     * @return 端口号，解析失败返回 0
     */
    private int parsePort(String[] parts) {
        if (parts.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 通知连接回调。
     *
     * @param clientId 客户端标识
     */
    private void notifyConnectListeners(String clientId) {
        for (Consumer<String> listener : connectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("SIP 连接回调异常", e);
            }
        }
    }

    /**
     * 通知断开回调。
     *
     * @param clientId 客户端标识
     */
    private void notifyDisconnectListeners(String clientId) {
        for (Consumer<String> listener : disconnectListeners) {
            try {
                listener.accept(clientId);
            } catch (Exception e) {
                log.error("SIP 断开回调异常", e);
            }
        }
    }

    /**
     * 清理断线客户端占用的隧道服务与通道，并通知通道对端关闭。
     *
     * @param clientId 客户端标识
     */
    private void cleanupTunnels(String clientId) {
        // 移除该客户端提供的隧道服务，避免成为陈旧服务
        tunnelServices.entrySet().removeIf(entry -> entry.getValue().equals(clientId));
        // 关闭该客户端参与的所有隧道通道，并通知对端
        List<String> closedChannels = new ArrayList<>();
        for (Map.Entry<String, TunnelChannel> entry : tunnelChannels.entrySet()) {
            TunnelChannel channel = entry.getValue();
            String peerId = channel.targetOf(clientId);
            if (peerId != null) {
                SipPeer peer = registry.get(peerId);
                if (peer != null) {
                    peer.transport().send(peerId, SipProtocol.CMD_TUNNEL_CLOSE, entry.getKey());
                }
                closedChannels.add(entry.getKey());
            }
        }
        closedChannels.forEach(tunnelChannels::remove);
    }

    /**
     * 传输层事件监听器，将各传输（TCP/KCP）的信令统一交给 {@link SipServer} 处理。
     *
     * @since 4.0.0.42
     */
    private final class SipTransportListener implements SyncServerListener {

        /**
         * 关联的传输实例
         */
        private final SyncServer transport;

        /**
         * 创建监听器。
         *
         * @param transport 关联的传输实例
         */
        private SipTransportListener(SyncServer transport) {
            this.transport = transport;
        }

        @Override
        /** OnClientConnected */
        public void onClientConnected(String clientId, Map<String, Object> metadata) {
            log.debug("SIP 客户端连接: {}", clientId);
        }

        @Override
        /** OnClientDisconnected */
        public void onClientDisconnected(String clientId) {
            if (registry.remove(clientId) != null) {
                notifyDisconnectListeners(clientId);
            }
            cleanupTunnels(clientId);
        }

        @Override
        /** OnMessage */
        public void onMessage(String clientId, String topic, Object message) {
            if (topic != null && topic.startsWith(SipProtocol.TOPIC_PREFIX)) {
                handleSignal(transport, clientId, topic, message);
            }
        }

        @Override
        /** On记录错误 */
        public void onError(String clientId, Throwable cause) {
            log.debug("SIP 传输异常: {}", cause.getMessage());
        }
    }

    /**
     * 已注册客户端会话信息。
     *
     * @param transport   关联的传输实例
     * @param host        客户端上报的可达地址
     * @param port        客户端上报的可达端口
     * @param connectedAt 注册时间（毫秒时间戳）
     * @since 4.0.0.42
     */
    private record SipPeer(SyncServer transport, String host, int port, long connectedAt) {
    }

    /**
     * 隧道通道两端路由信息。
     *
     * @param aId 访问方客户端标识
     * @param bId 服务提供方客户端标识
     * @since 4.0.0.42
     */
    private record TunnelChannel(String aId, String bId) {

        /**
         * 获取发送方对应的对端标识。
         *
         * @param senderId 发送方客户端标识
         * @return 对端客户端标识，非通道两端时返回 null
         */
        private String targetOf(String senderId) {
            if (senderId.equals(aId)) {
                return bId;
            }
            if (senderId.equals(bId)) {
                return aId;
            }
            return null;
        }
    }
}
