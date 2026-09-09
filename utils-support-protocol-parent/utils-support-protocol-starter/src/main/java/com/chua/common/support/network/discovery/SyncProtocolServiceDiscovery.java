package com.chua.common.support.network.discovery;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.text.json.Json;
import com.chua.common.support.lang.robin.Node;
import com.chua.common.support.lang.robin.LoadBalance;
import com.chua.common.support.network.net.NetAddress;
import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.listener.DataEvent;
import com.chua.common.support.network.protocol.listener.TopicListener;
import com.chua.common.support.network.protocol.sync.*;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.core.utils.CollectionUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于SyncProtocol的服务发现实现
 * <p>
 * 使用SyncProtocol进行服务注册和发现，支持：
 * <ul>
 *   <li>服务注册：将服务信息通过SyncProtocol发送到服务端</li>
 *   <li>服务发现：从服务端获取已注册的服务列表</li>
 *   <li>服务订阅：支持监听服务变化事件</li>
 *   <li>实时通信：利用SyncProtocol的全双工通信能力</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/12/04
 */
@Slf4j
@Spi("sync")
public class SyncProtocolServiceDiscovery extends AbstractServiceDiscovery {

    /**
     * 服务注册主题
     */
    private static final String TOPIC_REGISTER = "discovery/register";

    /**
     * 服务注销主题
     */
    private static final String TOPIC_UNREGISTER = "discovery/unregister";

    /**
     * 服务同步主题
     */
    private static final String TOPIC_SYNC = "discovery/sync";

    /**
     * 服务心跳主题
     */
    private static final String TOPIC_HEARTBEAT = "discovery/heartbeat";

    /**
     * 服务变更主题前缀
     */
    private static final String TOPIC_CHANGE_PREFIX = "discovery/change/";

    /**
     * 已注册的服务
     */
    private final ConcurrentMap<String, Set<Discovery>> registeredServices = new ConcurrentHashMap<>();

    /**
     * 服务订阅监听器
     */
    private final ConcurrentMap<String, List<ServiceDiscoveryListener>> listeners = new ConcurrentHashMap<>();

    /**
     * SyncClient客户端
     */
    private SyncClient client;

    /**
     * SyncServer服务端（可选）
     */
    private SyncServer server;

    /**
     * 协议类型
     */
    private String protocolType;

    /**
     * 是否为服务端模式
     */
    private boolean serverMode;

    /**
     * 是否已启动
     */
    private volatile boolean started = false;

    public SyncProtocolServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
        this.protocolType = discoveryOption.getOptions().getString("protocol", "websocket");
        this.serverMode = discoveryOption.getOptions().getBoolean("serverMode", false);
    }

    public SyncProtocolServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
        this.protocolType = discoveryOption.getOptions().getString("protocol", "websocket");
        this.serverMode = discoveryOption.getOptions().getBoolean("serverMode", false);
    }

    @Override
    protected Set<Discovery> get(String path) {
        Set<Discovery> discoveries = registeredServices.get(path);
        return discoveries != null ? new LinkedHashSet<>(discoveries) : Collections.emptySet();
    }

    @Override
    public void start() throws IOException {
        if (started) {
            log.warn("SyncProtocolServiceDiscovery已经启动");
            return;
        }

        try {
            NetAddress netAddress = NetAddress.of(discoveryOption.getAddress());

            if (serverMode) {
                startServer(netAddress);
            }
            startClient(netAddress);

            started = true;
            log.info("SyncProtocolServiceDiscovery启动成功，地址: {}, 模式: {}",
                    discoveryOption.getAddress(), serverMode ? "服务端" : "客户端");

        } catch (Exception e) {
            log.error("启动SyncProtocolServiceDiscovery失败", e);
            try {
                close();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            throw new IOException("启动SyncProtocolServiceDiscovery失败", e);
        }
    }

    /**
     * 启动服务端
     */
    private void startServer(NetAddress netAddress) {
        ServerSetting serverSetting = ServerSetting.builder()
                .protocol(protocolType)
                .host(netAddress.getHost())
                .port(netAddress.getPort())
                .build();

        server = SyncServer.create(protocolType, serverSetting);

        // 添加连接监听器
        server.addConnectionListener(new SyncConnectionListener() {
            @Override
            public void onConnect(SyncSession session) {
                log.info("客户端连接: sessionId={}", session.getSessionId());
                // 同步当前所有服务给新连接的客户端
                syncServicesToSession(session);
            }

            @Override
            public void onDisconnect(SyncSession session) {
                log.info("客户端断开: sessionId={}", session.getSessionId());
            }
        });

        // 添加消息监听器
        server.addMessageListener((session, message) -> handleServerMessage(session, message));

        try {
            server.start();
            log.info("SyncProtocol服务端启动成功，端口: {}", netAddress.getPort());
        } catch (Exception e) {
            throw new RuntimeException("启动SyncProtocol服务端失败", e);
        }
    }

    /**
     * 启动客户端
     */
    private void startClient(NetAddress netAddress) {
        ClientSetting clientSetting = ClientSetting.builder()
                .protocol(protocolType)
                .host(netAddress.getHost())
                .port(netAddress.getPort())
                .build();

        client = SyncClient.create(protocolType, clientSetting);

        // 订阅服务相关主题
        client.subscribe(TOPIC_REGISTER, TOPIC_UNREGISTER, TOPIC_SYNC, TOPIC_HEARTBEAT);

        // 添加主题监听器
        addTopicListeners();

        try {
            client.connect();
            log.info("SyncProtocol客户端启动成功，连接到: {}:{}", netAddress.getHost(), netAddress.getPort());

            // 请求同步服务
            requestSync();

        } catch (Exception e) {
            throw new RuntimeException("启动SyncProtocol客户端失败", e);
        }
    }

    /**
     * 同步服务到指定会话
     */
    private void syncServicesToSession(SyncSession session) {
        for (Map.Entry<String, Set<Discovery>> entry : registeredServices.entrySet()) {
            for (Discovery discovery : entry.getValue()) {
                session.send(TOPIC_SYNC, discovery.toFullString());
            }
        }
    }

    /**
     * 处理服务端接收到的消息
     */
    private void handleServerMessage(SyncSession session, SyncMessage message) {
        String topic = message.getTopic();
        Object data = message.getData();

        try {
            if (TOPIC_REGISTER.equals(topic)) {
                handleRegisterMessage(data);
                // 广播给所有客户端
                server.broadcast(TOPIC_REGISTER, data);
            } else if (TOPIC_UNREGISTER.equals(topic)) {
                handleUnregisterMessage(data);
                // 广播给所有客户端
                server.broadcast(TOPIC_UNREGISTER, data);
            } else if (TOPIC_SYNC.equals(topic)) {
                // 同步所有服务给请求的客户端
                syncServicesToSession(session);
            } else if (TOPIC_HEARTBEAT.equals(topic)) {
                handleHeartbeatMessage(data);
            }
        } catch (Exception e) {
            log.error("处理服务端消息失败: topic={}, data={}", topic, data, e);
        }
    }

    /**
     * 添加主题监听器
     */
    private void addTopicListeners() {
        // 注册消息监听
        client.getListenerManager().addTopicListener(new TopicListener() {
            @Override
            public String getTopic() {
                return TOPIC_REGISTER;
            }

            @Override
            public void onEvent(DataEvent event) {
                handleRegisterMessage(event.getData());
            }
        });

        // 注销消息监听
        client.getListenerManager().addTopicListener(new TopicListener() {
            @Override
            public String getTopic() {
                return TOPIC_UNREGISTER;
            }

            @Override
            public void onEvent(DataEvent event) {
                handleUnregisterMessage(event.getData());
            }
        });

        // 同步消息监听
        client.getListenerManager().addTopicListener(new TopicListener() {
            @Override
            public String getTopic() {
                return TOPIC_SYNC;
            }

            @Override
            public void onEvent(DataEvent event) {
                handleSyncMessage(event.getData());
            }
        });

        // 心跳消息监听
        client.getListenerManager().addTopicListener(new TopicListener() {
            @Override
            public String getTopic() {
                return TOPIC_HEARTBEAT;
            }

            @Override
            public void onEvent(DataEvent event) {
                handleHeartbeatMessage(event.getData());
            }
        });

        // 服务变更通配符监听
        client.getListenerManager().addTopicListener(new TopicListener() {
            @Override
            public String getTopic() {
                return TOPIC_CHANGE_PREFIX + "#";
            }

            @Override
            public void onEvent(DataEvent event) {
                String topic = event.getTopic();
                if (topic != null && topic.startsWith(TOPIC_CHANGE_PREFIX)) {
                    handleChangeMessage(topic, event.getData());
                }
            }
        });
    }

    /**
     * 处理注册消息
     */
    private void handleRegisterMessage(Object data) {
        Discovery discovery = parseDiscovery(data);
        if (discovery != null) {
            String path = discovery.getUriSpec();
            registeredServices.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(discovery);
            if (log.isDebugEnabled()) {
                log.debug("收到服务注册: {}", discovery.toFullString());
            }

            // 通知监听器
            notifyListeners(path, discovery, Event.ADD);
        }
    }

    /**
     * 处理注销消息
     */
    private void handleUnregisterMessage(Object data) {
        Discovery discovery = parseDiscovery(data);
        if (discovery != null) {
            String path = discovery.getUriSpec();
            Set<Discovery> discoveries = registeredServices.get(path);
            if (discoveries != null) {
                discoveries.removeIf(d ->
                        d.getHost().equals(discovery.getHost()) && d.getPort() == discovery.getPort());
            }
            if (log.isDebugEnabled()) {
                log.debug("收到服务注销: {}", discovery.toFullString());
            }

            // 通知监听器
            notifyListeners(path, discovery, Event.REMOVE);
        }
    }

    /**
     * 处理同步消息
     */
    private void handleSyncMessage(Object data) {
        Discovery discovery = parseDiscovery(data);
        if (discovery != null) {
            String path = discovery.getUriSpec();
            registeredServices.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(discovery);
            if (log.isDebugEnabled()) {
                log.debug("同步服务: {}", discovery.toFullString());
            }
        }
    }

    /**
     * 处理心跳消息
     */
    private void handleHeartbeatMessage(Object data) {
        Discovery discovery = parseDiscovery(data);
        if (discovery != null) {
            String path = discovery.getUriSpec();
            Set<Discovery> discoveries = registeredServices.get(path);
            if (discoveries != null) {
                // 更新服务状态
                discoveries.add(discovery);
            }
            log.trace("收到服务心跳: {}", discovery.toFullString());
        }
    }

    /**
     * 处理服务变更消息
     */
    private void handleChangeMessage(String topic, Object data) {
        String serviceName = topic.substring(TOPIC_CHANGE_PREFIX.length());
        Discovery discovery = parseDiscovery(data);
        if (discovery != null) {
            notifyListeners(serviceName, discovery, Event.UPDATE);
        }
    }

    /**
     * 解析Discovery对象
     */
    private Discovery parseDiscovery(Object data) {
        if (data == null) {
            return null;
        }

        try {
            if (data instanceof Discovery) {
                return (Discovery) data;
            } else if (data instanceof String) {
                return Json.fromJson((String) data, Discovery.class);
            } else if (data instanceof Map) {
                return Json.fromJson(Json.toJson(data), Discovery.class);
            }
        } catch (Exception e) {
            log.error("解析Discovery失败: {}", data, e);
        }

        return null;
    }

    /**
     * 请求同步服务
     */
    private void requestSync() {
        if (client != null) {
            client.publish(TOPIC_SYNC, "sync_request");
        }
    }

    /**
     * 通知监听器
     */
    private void notifyListeners(String serviceName, Discovery discovery, Event event) {
        List<ServiceDiscoveryListener> listenerList = listeners.get(serviceName);
        if (listenerList != null) {
            for (ServiceDiscoveryListener listener : listenerList) {
                try {
                    listener.listen(serviceName, discovery, event);
                } catch (Exception e) {
                    log.error("通知监听器失败: serviceName={}, event={}", serviceName, event, e);
                }
            }
        }
    }

    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        if (!started) {
            throw new IllegalStateException("SyncProtocolServiceDiscovery未启动");
        }

        // 添加集群前缀进行隔离
        String prefixedPath = addClusterPrefix(path);
        discovery.setUriSpec(prefixedPath);

        // 本地注册
        registeredServices.computeIfAbsent(prefixedPath, k -> ConcurrentHashMap.newKeySet()).add(discovery);

        // 通过SyncProtocol发送注册消息
        if (client != null) {
            client.publish(TOPIC_REGISTER, discovery.toFullString());
        }

        log.info("注册服务: {} -> {} (集群: {})", path, prefixedPath, clusterName);
        return this;
    }

    @Override
    public Discovery getService(String path, String balance, String protocol) {
        // 添加集群前缀进行隔离
        String prefixedPath = addClusterPrefix(path);
        Set<Discovery> netAddresses = getPath(StringUtils.startWithAppend(prefixedPath, "/"));

        log.debug("获取服务: {} -> {} (集群: {}), 找到{}个服务",
                path, prefixedPath, clusterName, netAddresses.size());

        if (CollectionUtils.isEmpty(netAddresses)) {
            return null;
        }

        LoadBalance loadBalance = ServiceProvider.of(LoadBalance.class).getNewExtension(balance);
        LoadBalance loadBalance1 = loadBalance.create();

        for (Discovery netAddress : netAddresses) {
            if (StringUtils.isNotBlank(protocol) && !protocol.equalsIgnoreCase(netAddress.getProtocol())) {
                continue;
            }
            Node node = new Node(netAddress);
            node.setWeight(netAddress.getWeight());
            loadBalance1.addNode(node);
        }

        Node selectNode = loadBalance1.selectNode();
        return selectNode != null ? selectNode.getValue(Discovery.class) : null;
    }

    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        if (!started) {
            throw new IllegalStateException("SyncProtocolServiceDiscovery未启动");
        }

        String prefixedServiceName = addClusterPrefix(serviceName);
        listeners.computeIfAbsent(prefixedServiceName, k -> new CopyOnWriteArrayList<>()).add(listener);

        // 订阅服务变更主题
        String changeTopic = TOPIC_CHANGE_PREFIX + prefixedServiceName;
        if (client != null) {
            client.subscribe(changeTopic);
        }

        log.info("订阅服务: {} (集群: {})", serviceName, clusterName);
    }

    /**
     * 取消订阅
     *
     * @param serviceName 服务名称
     * @param listener    监听器
     */
    public void unsubscribe(String serviceName, ServiceDiscoveryListener listener) {
        String prefixedServiceName = addClusterPrefix(serviceName);
        List<ServiceDiscoveryListener> listenerList = listeners.get(prefixedServiceName);
        if (listenerList != null) {
            listenerList.remove(listener);
            if (listenerList.isEmpty()) {
                listeners.remove(prefixedServiceName);

                // 取消订阅服务变更主题
                String changeTopic = TOPIC_CHANGE_PREFIX + prefixedServiceName;
                if (client != null) {
                    client.unsubscribe(changeTopic);
                }
            }
        }

        log.info("取消订阅服务: {} (集群: {})", serviceName, clusterName);
    }

    /**
     * 注销服务
     *
     * @param path      服务路径
     * @param discovery 服务发现实例
     */
    public void unregisterService(String path, Discovery discovery) {
        if (!started) {
            return;
        }

        String prefixedPath = addClusterPrefix(path);
        Set<Discovery> discoveries = registeredServices.get(prefixedPath);
        if (discoveries != null) {
            discoveries.removeIf(d ->
                    d.getHost().equals(discovery.getHost()) && d.getPort() == discovery.getPort());
        }

        // 通过SyncProtocol发送注销消息
        if (client != null) {
            discovery.setUriSpec(prefixedPath);
            client.publish(TOPIC_UNREGISTER, discovery.toFullString());
        }

        log.info("注销服务: {} -> {} (集群: {})", path, prefixedPath, clusterName);
    }

    /**
     * 发送心跳
     */
    public void sendHeartbeat(Discovery discovery) {
        if (client != null && started) {
            client.publish(TOPIC_HEARTBEAT, discovery.toFullString());
        }
    }

    /**
     * 获取所有已注册的服务路径
     *
     * @return 服务路径集合
     */
    public Set<String> getRegisteredPaths() {
        return new HashSet<>(registeredServices.keySet());
    }

    /**
     * 获取连接数（服务端模式）
     *
     * @return 连接数
     */
    public int getConnectionCount() {
        return server != null ? server.getConnectionCount() : 0;
    }

    @Override
    public void close() throws Exception {
        if (!started) {
            return;
        }

        log.info("正在关闭SyncProtocolServiceDiscovery...");

        started = false;

        // 关闭客户端
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭SyncProtocol客户端失败", e);
            }
            client = null;
        }

        // 关闭服务端
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                log.warn("关闭SyncProtocol服务端失败", e);
            }
            server = null;
        }

        // 清理数据
        registeredServices.clear();
        listeners.clear();

        log.info("SyncProtocolServiceDiscovery关闭");
    }
}