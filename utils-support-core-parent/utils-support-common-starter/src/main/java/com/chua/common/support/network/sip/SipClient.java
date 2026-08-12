package com.chua.common.support.network.sip;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SIP 客户端，支持 TCP 与 KCP 两种长连接传输，与 {@link SipServer} 配对使用。
 *
 * <p>客户端通过注册、寻址与消息收发能力与信令服务器交互：
 * <ul>
 *   <li>注册 — 上报可达地址，供对端查询</li>
 *   <li>寻址 — 同步或异步查询对端地址</li>
 *   <li>消息 — 向指定客户端发送消息，经服务器中继转发</li>
 *   <li>响应 — 回复对端请求，经服务器回传</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // TCP 传输
 * SipClient client = SipClient.tcp("A", "tcp://127.0.0.1:19460");
 * client.onMessage((fromId, content) -> System.out.println(fromId + " -> " + content));
 * client.connect();
 * client.register("192.168.1.10", 10001);
 *
 * String address = client.find("B");          // 获取 B 的可达地址
 * client.send("B", "hello");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipClient {

    /**
     * 默认查找超时时间（毫秒）
     */
    public static final long DEFAULT_TIMEOUT_MS = 5000L;

    /**
     * 传输类型（tcp / kcp）
     */
    private final String transport;

    /**
     * 底层同步客户端
     */
    private final SyncClient syncClient;

    /**
     * 待处理的查找请求映射（requestId -> Future）
     */
    private final Map<String, CompletableFuture<String>> pendingFinds = new ConcurrentHashMap<>();

    /**
     * 消息监听器列表
     */
    private final List<BiConsumer<String, String>> messageListeners = new CopyOnWriteArrayList<>();

    /**
     * 响应监听器列表
     */
    private final List<SipResponseListener> responseListeners = new CopyOnWriteArrayList<>();

    /**
     * 推送监听器列表
     */
    private final List<BiConsumer<String, String>> pushListeners = new CopyOnWriteArrayList<>();

    /**
     * 待处理的隧道开启请求映射（requestId -> Future）
     */
    private final Map<String, CompletableFuture<SipTunnelSession>> pendingTunnels = new ConcurrentHashMap<>();

    /**
     * 活动隧道会话集合（channelId -> 会话）
     */
    private final Map<String, SipTunnelSession> openTunnels = new ConcurrentHashMap<>();

    /**
     * 隧道开启请求监听器列表（作为服务提供方接收：channelId, serviceName）
     */
    private final List<BiConsumer<String, String>> tunnelOpenListeners = new CopyOnWriteArrayList<>();

    /**
     * 本地上报的可达地址
     */
    private volatile String localHost;

    /**
     * 本地上报的可达端口
     */
    private volatile int localPort;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 创建 SIP 客户端。
     *
     * @param transport  传输类型
     * @param syncClient 底层同步客户端
     */
    private SipClient(String transport, SyncClient syncClient) {
        this.transport = transport;
        this.syncClient = syncClient;
    }

    /**
     * 创建基于 TCP 传输的 SIP 客户端。
     *
     * @param url 服务端地址，如 tcp://127.0.0.1:19460
     * @return SIP 客户端
     */
    public static SipClient tcp(String url) {
        return create("tcp", url);
    }

    /**
     * 创建基于 KCP 传输的 SIP 客户端。
     *
     * @param url 服务端地址，如 kcp://127.0.0.1:19461
     * @return SIP 客户端
     */
    public static SipClient kcp(String url) {
        return create("kcp", url);
    }

    /**
     * 按传输类型创建 SIP 客户端，底层通过 SyncProtocol SPI 创建同步客户端。
     *
     * @param transport 传输类型（tcp / kcp）
     * @param url       服务端地址
     * @return SIP 客户端
     */
    private static SipClient create(String transport, String url) {
        SyncServer server = ServiceProvider.of(SyncServer.class).getNewExtension(transport, ServerSetting.defaults());
        if (!(server instanceof SyncProtocol protocol)) {
            throw new IllegalStateException("传输 [" + transport + "] 不支持 SyncProtocol，请确认依赖是否齐全");
        }
        SyncClient syncClient = protocol.createClient(url);
        return new SipClient(transport, syncClient);
    }

    /**
     * 连接 SIP 服务器并订阅全部信令主题。
     *
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient connect() {
        if (connected) {
            return this;
        }
        subscribeSignals();
        syncClient.connect();
        connected = true;
        return this;
    }

    /**
     * 订阅全部信令主题。
     *
     * <p>Sync 传输的订阅为精确匹配，故逐个订阅每个信令主题。</p>
     */
    private void subscribeSignals() {
        syncClient.subscribe(SipProtocol.CMD_REGISTERED, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_FOUND, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_NOTFOUND, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_MSG, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_RESP, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_PUSH, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_TUNNEL_REGISTERED, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_TUNNEL_OPEN, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_TUNNEL_OPENED, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_TUNNEL_ERROR, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_TUNNEL_DATA, this::handleSipMessage);
        syncClient.subscribe(SipProtocol.CMD_TUNNEL_CLOSE, this::handleSipMessage);
    }

    /**
     * 断开与服务器的连接。
     */
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        syncClient.disconnect();
    }

    /**
     * 判断客户端是否已连接。
     *
     * @return true 表示已连接
     */
    public boolean isConnected() {
        return connected && syncClient.isConnected();
    }

    /**
     * 获取客户端标识。
     *
     * @return 客户端标识
     */
    public String getClientId() {
        return syncClient.getClientId();
    }

    /**
     * 获取传输类型。
     *
     * @return tcp 或 kcp
     */
    public String getTransport() {
        return transport;
    }

    /**
     * 注册到服务器并上报可达地址。
     *
     * @param host 可达地址
     * @param port 可达端口
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient register(String host, int port) {
        this.localHost = host;
        this.localPort = port;
        syncClient.send(SipProtocol.CMD_REGISTER, SipProtocol.register(host, port));
        return this;
    }

    /**
     * 同步查询对端可达地址。
     *
     * @param peerId 对端客户端标识
     * @return 形如 {@code peerId:host:port} 的地址
     */
    public String find(String peerId) {
        return find(peerId, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 同步查询对端可达地址，指定超时时间。
     *
     * @param peerId    对端客户端标识
     * @param timeoutMs 超时时间（毫秒）
     * @return 形如 {@code peerId:host:port} 的地址
     */
    public String find(String peerId, long timeoutMs) {
        CompletableFuture<String> future = findAsync(peerId);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("SIP 查找对端失败: " + peerId, e);
        }
    }

    /**
     * 异步查询对端可达地址。
     *
     * @param peerId 对端客户端标识
     * @return 异步任务，完成时包含形如 {@code peerId:host:port} 的地址
     */
    public CompletableFuture<String> findAsync(String peerId) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingFinds.put(requestId, future);
        future.whenComplete((result, error) -> pendingFinds.remove(requestId));
        syncClient.send(SipProtocol.CMD_FIND, SipProtocol.find(requestId, peerId));
        return future;
    }

    /**
     * 向指定客户端发送消息。
     *
     * @param peerId  目标客户端标识
     * @param content 消息内容
     */
    public void send(String peerId, String content) {
        syncClient.send(SipProtocol.CMD_MSG, SipProtocol.msg(peerId, content));
    }

    /**
     * 向指定客户端回传响应。
     *
     * @param peerId    目标客户端标识
     * @param requestId 对应请求标识
     * @param content   响应内容
     */
    public void respond(String peerId, String requestId, String content) {
        syncClient.send(SipProtocol.CMD_RESP, SipProtocol.resp(peerId, requestId, content));
    }

    /**
     * 注册隧道服务，声明本客户端对外暴露的服务。
     *
     * @param serviceName 服务名称
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient registerTunnel(String serviceName) {
        syncClient.send(SipProtocol.CMD_TUNNEL_REGISTER, serviceName);
        return this;
    }

    /**
     * 注册隧道开启请求监听器（作为服务提供方接收访问方的隧道请求）。
     *
     * @param listener 监听器，参数为通道标识与服务名称
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient onTunnelOpen(BiConsumer<String, String> listener) {
        tunnelOpenListeners.add(listener);
        return this;
    }

    /**
     * 获取指定通道标识的隧道会话（服务提供方在 {@code onTunnelOpen} 回调中获取会话）。
     *
     * @param channelId 通道标识
     * @return 隧道会话，通道不存在时返回 null
     */
    public SipTunnelSession tunnelSession(String channelId) {
        return openTunnels.get(channelId);
    }

    /**
     * 同步开启到指定服务的隧道。
     *
     * @param serviceName 服务名称
     * @return 隧道会话
     */
    public SipTunnelSession openTunnel(String serviceName) {
        return openTunnel(serviceName, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 同步开启到指定服务的隧道，指定超时时间。
     *
     * @param serviceName 服务名称
     * @param timeoutMs   超时时间（毫秒）
     * @return 隧道会话
     */
    public SipTunnelSession openTunnel(String serviceName, long timeoutMs) {
        CompletableFuture<SipTunnelSession> future = openTunnelAsync(serviceName);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("SIP 隧道开启失败: " + serviceName, e);
        }
    }

    /**
     * 异步开启到指定服务的隧道。
     *
     * @param serviceName 服务名称
     * @return 异步任务，完成时包含隧道会话
     */
    public CompletableFuture<SipTunnelSession> openTunnelAsync(String serviceName) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<SipTunnelSession> future = new CompletableFuture<>();
        pendingTunnels.put(requestId, future);
        future.whenComplete((session, error) -> pendingTunnels.remove(requestId));
        syncClient.send(SipProtocol.CMD_TUNNEL_OPEN, requestId + SipProtocol.SEPARATOR + serviceName);
        return future;
    }

    /**
     * 向指定隧道通道发送数据。
     *
     * @param channelId 通道标识
     * @param data      数据内容
     */
    public void sendTunnelData(String channelId, String data) {
        syncClient.send(SipProtocol.CMD_TUNNEL_DATA, channelId + SipProtocol.SEPARATOR + data);
    }

    /**
     * 关闭指定隧道通道。
     *
     * @param channelId 通道标识
     */
    public void closeTunnel(String channelId) {
        syncClient.send(SipProtocol.CMD_TUNNEL_CLOSE, channelId);
        openTunnels.remove(channelId);
    }

    /**
     * 注册消息监听器。
     *
     * @param listener 消息监听器，参数为来源客户端标识与消息内容
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient onMessage(BiConsumer<String, String> listener) {
        messageListeners.add(listener);
        return this;
    }

    /**
     * 注册响应监听器。
     *
     * @param listener 响应监听器
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient onResponse(SipResponseListener listener) {
        responseListeners.add(listener);
        return this;
    }

    /**
     * 注册推送监听器（接收服务器主动推送的消息）。
     *
     * @param listener 推送监听器，参数为主题与消息内容
     * @return 当前客户端实例，支持链式调用
     */
    public SipClient onPush(BiConsumer<String, String> listener) {
        pushListeners.add(listener);
        return this;
    }

    /**
     * 获取客户端元数据。
     *
     * @return 元数据映射
     */
    public Map<String, Object> getMetadata() {
        return Map.of("transport", transport, "clientId", getClientId(), "host", localHost, "port", localPort);
    }

    /**
     * 关闭客户端，释放资源。
     */
    public void close() {
        disconnect();
    }

    /**
     * 处理服务端下发的信令消息。
     *
     * @param topic   消息主题
     * @param message 消息内容
     */
    private void handleSipMessage(String topic, Object message) {
        String payload = message != null ? message.toString() : "";
        switch (topic) {
            case SipProtocol.CMD_REGISTERED -> log.debug("SIP 注册成功: {}", payload);
            case SipProtocol.CMD_FOUND -> handleFound(payload);
            case SipProtocol.CMD_NOTFOUND -> handleNotFound(payload);
            case SipProtocol.CMD_MSG -> handleIncomingMsg(payload);
            case SipProtocol.CMD_RESP -> handleIncomingResp(payload);
            case SipProtocol.CMD_PUSH -> handleIncomingPush(payload);
            case SipProtocol.CMD_TUNNEL_REGISTERED -> log.debug("SIP 隧道服务注册成功: {}", payload);
            case SipProtocol.CMD_TUNNEL_OPEN -> handleTunnelOpenRequest(payload);
            case SipProtocol.CMD_TUNNEL_OPENED -> handleTunnelOpened(payload);
            case SipProtocol.CMD_TUNNEL_ERROR -> handleTunnelError(payload);
            case SipProtocol.CMD_TUNNEL_DATA -> handleTunnelData(payload);
            case SipProtocol.CMD_TUNNEL_CLOSE -> handleTunnelClose(payload);
            default -> log.debug("忽略未知 SIP 信令: {}", topic);
        }
    }

    /**
     * 处理隧道开启请求（作为服务提供方收到访问方的隧道请求）。
     *
     * @param payload 报文内容（channelId|serviceName）
     */
    private void handleTunnelOpenRequest(String payload) {
        String[] parts = payload.split("\\|", 2);
        String channelId = parts[0];
        String serviceName = parts.length > 1 ? parts[1] : "";
        SipTunnelSession session = new SipTunnelSession(this, channelId, serviceName);
        openTunnels.put(channelId, session);
        for (BiConsumer<String, String> listener : tunnelOpenListeners) {
            try {
                listener.accept(channelId, serviceName);
            } catch (Exception e) {
                log.error("SIP 隧道开启监听器异常", e);
            }
        }
    }

    /**
     * 处理隧道开启成功（作为访问方收到通道标识）。
     *
     * @param payload 报文内容（requestId|channelId）
     */
    private void handleTunnelOpened(String payload) {
        int separatorIndex = payload.indexOf(SipProtocol.SEPARATOR);
        if (separatorIndex <= 0) {
            return;
        }
        String requestId = payload.substring(0, separatorIndex);
        String channelId = payload.substring(separatorIndex + 1);
        CompletableFuture<SipTunnelSession> future = pendingTunnels.remove(requestId);
        if (future == null) {
            return;
        }
        SipTunnelSession session = new SipTunnelSession(this, channelId, "");
        openTunnels.put(channelId, session);
        future.complete(session);
    }

    /**
     * 处理隧道开启失败。
     *
     * @param payload 报文内容（requestId|reason）
     */
    private void handleTunnelError(String payload) {
        int separatorIndex = payload.indexOf(SipProtocol.SEPARATOR);
        if (separatorIndex <= 0) {
            return;
        }
        String requestId = payload.substring(0, separatorIndex);
        String reason = payload.substring(separatorIndex + 1);
        CompletableFuture<SipTunnelSession> future = pendingTunnels.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new IllegalArgumentException(reason));
        }
    }

    /**
     * 处理隧道数据帧，路由到对应会话。
     *
     * @param payload 报文内容（channelId|data）
     */
    private void handleTunnelData(String payload) {
        String[] parts = payload.split("\\|", 2);
        String channelId = parts[0];
        String data = parts.length > 1 ? parts[1] : "";
        SipTunnelSession session = openTunnels.get(channelId);
        if (session != null) {
            session.dispatchData(data);
        }
    }

    /**
     * 处理隧道关闭。
     *
     * @param payload 报文内容（channelId）
     */
    private void handleTunnelClose(String payload) {
        String channelId = payload.trim();
        SipTunnelSession session = openTunnels.remove(channelId);
        if (session != null) {
            session.dispatchClose();
        }
    }

    /**
     * 处理查询结果（requestId|peerId:host:port）。
     *
     * @param payload 报文内容
     */
    private void handleFound(String payload) {
        int separatorIndex = payload.indexOf(SipProtocol.SEPARATOR);
        if (separatorIndex <= 0) {
            return;
        }
        String requestId = payload.substring(0, separatorIndex);
        String address = payload.substring(separatorIndex + 1);
        CompletableFuture<String> future = pendingFinds.remove(requestId);
        if (future != null) {
            future.complete(address);
        }
    }

    /**
     * 处理查无此人（requestId|peerId）。
     *
     * @param payload 报文内容
     */
    private void handleNotFound(String payload) {
        int separatorIndex = payload.indexOf(SipProtocol.SEPARATOR);
        if (separatorIndex <= 0) {
            return;
        }
        String requestId = payload.substring(0, separatorIndex);
        String peerId = payload.substring(separatorIndex + 1);
        CompletableFuture<String> future = pendingFinds.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new IllegalArgumentException("对端不存在: " + peerId));
        }
    }

    /**
     * 处理收到的转发消息（fromId|content）。
     *
     * @param payload 报文内容
     */
    private void handleIncomingMsg(String payload) {
        String[] parts = payload.split("\\|", 2);
        String fromId = parts[0];
        String content = parts.length > 1 ? parts[1] : "";
        for (BiConsumer<String, String> listener : messageListeners) {
            try {
                listener.accept(fromId, content);
            } catch (Exception e) {
                log.error("SIP 消息监听器异常", e);
            }
        }
    }

    /**
     * 处理收到的响应（fromId|requestId|content）。
     *
     * @param payload 报文内容
     */
    private void handleIncomingResp(String payload) {
        String[] parts = payload.split("\\|", 3);
        String fromId = parts[0];
        String requestId = parts.length > 1 ? parts[1] : "";
        String content = parts.length > 2 ? parts[2] : "";
        for (SipResponseListener listener : responseListeners) {
            try {
                listener.onResponse(fromId, requestId, content);
            } catch (Exception e) {
                log.error("SIP 响应监听器异常", e);
            }
        }
    }

    /**
     * 处理服务器主动推送的消息（topic|message）。
     *
     * @param payload 报文内容
     */
    private void handleIncomingPush(String payload) {
        String[] parts = payload.split("\\|", 2);
        String topic = parts[0];
        String message = parts.length > 1 ? parts[1] : "";
        for (BiConsumer<String, String> listener : pushListeners) {
            try {
                listener.accept(topic, message);
            } catch (Exception e) {
                log.error("SIP 推送监听器异常", e);
            }
        }
    }

    /**
     * SIP 响应监听器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @FunctionalInterface
    public interface SipResponseListener {

        /**
         * 收到对端响应。
         *
         * @param fromId    响应来源客户端标识
         * @param requestId 对应请求标识
         * @param content   响应内容
         */
        void onResponse(String fromId, String requestId, String content);
    }
}
