// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.discovery;

import com.chua.common.support.exception.StartFailedException;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌注册中心实现（{@code registry-hub}）。
 *
 * <p>基于 {@link com.chua.common.support.network.server.SyncServer} 传输通道实现一个
 * 进程内/网络可达的服务注册中心：本进程持有全部服务表（本地缓存），并同时接受
 * 远程 {@code registry-client} 的注册、注销与快照同步请求。</p>
 *
 * <p>职责边界：</p>
 * <ul>
 *     <li>作为 {@code server} 模式：独立注册中心进程，只维护服务表；</li>
 *     <li>作为 {@code embbed} 模式：注册中心与应用同进程，应用直接以本实例作为
 *     {@link ServiceDiscovery} 完成注册与查询，无需自连；</li>
 *     <li>远程服务表变更通过 {@link RegistrySyncMessage} 事件广播给所有在线客户端，
 *     客户端掉线时回收其全部注册并广播 REMOVE。</li>
 * </ul>
 *
 * <p>协议约定见 {@link RegistrySyncMessage}：client -&gt; hub 的操作走共享主题
 * {@link RegistrySyncMessage#TOPIC}；hub -&gt; client 的快照/事件走客户端专属主题
 * {@link RegistrySyncMessage#topicOf(String)}（HTTP 传输按主题竞争消费，独占主题才能保证事件不串流）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
@Spi("registry-hub")
public class RegistryHubServiceDiscovery extends DefaultServiceDiscovery {

    /**
     * hub 侧消息的来源标识（hub 自身发起的事件/快照使用，客户端按 clientId 过滤自回声时不会命中）
     */
    public static final String HUB_ID = "registry-hub";

    /**
     * 默认传输协议
     */
    private static final String DEFAULT_PROTOCOL = "http";

    /**
     * 默认监听地址
     */
    private static final String DEFAULT_HOST = "0.0.0.0";

    /**
     * 默认监听端口
     */
    private static final int DEFAULT_PORT = 9600;

    /**
     * 事件名：新增
     */
    private static final String EVENT_ADD = "add";

    /**
     * 事件名：更新
     */
    private static final String EVENT_UPDATE = "update";

    /**
     * 事件名：移除
     */
    private static final String EVENT_REMOVE = "remove";

    /**
     * 传输协议（http/websocket/tcp/udp）
     */
    private final String transportProtocol;

    /**
     * 监听地址
     */
    private final String bindHost;

    /**
     * 监听端口
     */
    private final int bindPort;

    /**
     * 注册归属表：clientId -&gt; (规范化路径 -&gt; serverId)，客户端掉线时整体回收
     */
    private final Map<String, Map<String, String>> ownedByClient = new ConcurrentHashMap<>();

    /**
     * 启动状态
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 同步服务端（懒启动）
     */
    private volatile SyncServer syncServer;

    /**
     * 创建内嵌注册中心。
     *
     * <p>地址格式：{@code [protocol://]host[:port]}，默认协议 {@code http}、默认端口 {@code 9600}。</p>
     *
     * @param discoveryOption 发现选项，address 承载监听地址
     */
    public RegistryHubServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
        String address = discoveryOption == null ? null : discoveryOption.getAddress();
        String protocol = DEFAULT_PROTOCOL;
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (StringUtils.isNotBlank(address)) {
            String rest = address.trim();
            int schemeIdx = rest.indexOf("://");
            if (schemeIdx > 0) {
                protocol = rest.substring(0, schemeIdx);
                rest = rest.substring(schemeIdx + 3);
            }
            int slashIdx = rest.indexOf('/');
            if (slashIdx >= 0) {
                rest = rest.substring(0, slashIdx);
            }
            if (rest.startsWith("[")) {
                // IPv6 形如 [::1]:9600
                int end = rest.indexOf(']');
                if (end > 0) {
                    host = rest.substring(1, end);
                    int colon = rest.indexOf(':', end);
                    if (colon > 0) {
                        port = Integer.parseInt(rest.substring(colon + 1));
                    }
                } else {
                    host = rest;
                }
            } else {
                int colon = rest.lastIndexOf(':');
                if (colon > 0) {
                    host = rest.substring(0, colon);
                    port = Integer.parseInt(rest.substring(colon + 1));
                } else {
                    host = rest;
                }
            }
        }
        this.transportProtocol = protocol;
        this.bindHost = host;
        this.bindPort = port;
    }

    /**
     * 启动注册中心：按解析出的协议创建 {@link SyncServer} 并挂载消息监听。
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ServerSetting setting = ServerSetting.builder()
                .host(bindHost)
                .port(bindPort)
                .protocol(transportProtocol)
                .build();
        SyncServer server = ServiceProvider.of(SyncServer.class).getNewExtension(transportProtocol, setting);
        if (server == null) {
            running.set(false);
            throw new StartFailedException("[registry-hub] 未找到可用的 SyncServer 实现: " + transportProtocol);
        }
        server.addListener(new SyncServerListener() {
            @Override
            public void onMessage(String clientId, String topic, Object message) {
                if (!RegistrySyncMessage.TOPIC.equals(topic)) {
                    // registry-sync#<cid> 是各客户端的下行拉取队列，hub 只消费共享上行主题
                    return;
                }
                handleClientMessage(clientId, message);
            }

            @Override
            public void onClientDisconnected(String clientId) {
                purgeClient(clientId);
            }
        });
        server.start();
        this.syncServer = server;
        log.info("[registry-hub] 注册中心已启动: {}://{}:{}，模式随宿主装配", transportProtocol, bindHost, bindPort);
    }

    /**
     * 注册服务到本地服务表（upsert、幂等），并向所有在线客户端广播 ADD/UPDATE 事件。
     *
     * <p>同一 serverId 重复注册且内容未变化时直接忽略（对账周期重申不产生事件风暴）；
     * 内容有变化时按 UPDATE 事件广播。</p>
     */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        Discovery existing = findSameServerId(prefixed, discovery.getServerId());
        if (existing != null && existing.equals(discovery)) {
            return this;
        }
        // upsert：同 serverId 先移除旧条目，避免重复堆积
        removeFromCache(prefixed, discovery.getServerId());
        ServiceDiscovery result = super.registerService(path, discovery);
        broadcast(RegistrySyncMessage.of(RegistrySyncMessage.OP_EVENT, HUB_ID)
                .setPath(prefixed)
                .setDiscovery(discovery)
                .setEvent(existing == null ? EVENT_ADD : EVENT_UPDATE));
        return result;
    }

    /**
     * 注销服务并广播 REMOVE 事件（条目不存在时静默返回，不广播）。
     */
    @Override
    public ServiceDiscovery unregisterService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        Discovery existing = findSameServerId(prefixed, discovery.getServerId());
        ServiceDiscovery result = super.unregisterService(path, discovery);
        if (existing != null) {
            broadcast(RegistrySyncMessage.of(RegistrySyncMessage.OP_EVENT, HUB_ID)
                    .setPath(prefixed)
                    .setDiscovery(discovery)
                    .setEvent(EVENT_REMOVE));
        }
        return result;
    }

    /**
     * 按 serverId 注销服务并广播 REMOVE 事件（条目不存在时静默返回，不广播）。
     */
    @Override
    public ServiceDiscovery unregisterService(String path, String serverId) {
        String prefixed = addClusterPrefix(path);
        Discovery existing = findSameServerId(prefixed, serverId);
        ServiceDiscovery result = super.unregisterService(path, serverId);
        if (existing != null) {
            broadcast(RegistrySyncMessage.of(RegistrySyncMessage.OP_EVENT, HUB_ID)
                    .setPath(prefixed)
                    .setDiscovery(Discovery.builder().serverId(serverId).build())
                    .setEvent(EVENT_REMOVE));
        }
        return result;
    }

    /**
     * 更新服务并广播 UPDATE 事件（内容未变化时静默返回，不广播）。
     */
    @Override
    public ServiceDiscovery updateService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        Discovery existing = findSameServerId(prefixed, discovery.getServerId());
        if (existing != null && existing.equals(discovery)) {
            return this;
        }
        ServiceDiscovery result = super.updateService(path, discovery);
        broadcast(RegistrySyncMessage.of(RegistrySyncMessage.OP_EVENT, HUB_ID)
                .setPath(prefixed)
                .setDiscovery(discovery)
                .setEvent(EVENT_UPDATE));
        return result;
    }

    /**
     * 停止注册中心并清空服务表。
     */
    @Override
    public void close() {
        running.set(false);
        SyncServer server = this.syncServer;
        this.syncServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                log.warn("[registry-hub] 注册中心关闭异常: {}", e.getMessage());
            }
        }
        ownedByClient.clear();
        super.close();
        log.info("[registry-hub] 注册中心已关闭: {}://{}:{}", transportProtocol, bindHost, bindPort);
    }

    // ======================== 上行消息处理 ========================

    /**
     * 处理 client -&gt; hub 的操作消息。
     *
     * @param clientId 来源客户端
     * @param message  消息内容（JSON 字符串）
     */
    private void handleClientMessage(String clientId, Object message) {
        RegistrySyncMessage msg = parse(message);
        if (msg == null || msg.getOp() == null) {
            return;
        }
        switch (msg.getOp()) {
            case RegistrySyncMessage.OP_REGISTER, RegistrySyncMessage.OP_UPDATE -> applyRemoteUpsert(clientId, msg);
            case RegistrySyncMessage.OP_UNREGISTER -> applyRemoteUnregister(clientId, msg);
            case RegistrySyncMessage.OP_SYNC_REQUEST -> sendSnapshot(clientId);
            default -> {
                // OP_SYNC / OP_EVENT 为 hub -> client 的下行消息，hub 不消费
            }
        }
    }

    /**
     * 应用远程注册/更新（upsert 语义：先按 serverId 移除旧条目再追加），记录归属并广播事件。
     *
     * <p>幂等抑制：同一 serverId 重复上报且内容未变化（典型场景：客户端对账周期重申注册）
     * 时仅补记归属，不入库、不升版本、不广播；内容有变化时按新增/更新分别广播
     * ADD / UPDATE 事件。</p>
     */
    private void applyRemoteUpsert(String clientId, RegistrySyncMessage msg) {
        Discovery discovery = msg.getDiscovery();
        String path = msg.getPath();
        if (discovery == null || StringUtils.isEmpty(path)) {
            return;
        }
        String normalizedPath = StringUtils.startWithAppend(path, "/");
        Discovery existing = findSameServerId(normalizedPath, discovery.getServerId());
        if (existing != null && existing.equals(discovery)) {
            // 幂等重申：内容未变化，仅确保归属仍在（掉线回收后重连重申会走到新增分支）
            ownedByClient.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                    .put(normalizedPath, discovery.getServerId());
            return;
        }
        removeFromCache(normalizedPath, discovery.getServerId());
        addToCache(normalizedPath, discovery);
        ownedByClient.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                .put(normalizedPath, discovery.getServerId());
        incrementServiceVersion();
        String event = existing == null ? EVENT_ADD : EVENT_UPDATE;
        broadcast(RegistrySyncMessage.of(RegistrySyncMessage.OP_EVENT, clientId)
                .setPath(normalizedPath)
                .setDiscovery(discovery)
                .setEvent(event));
        log.info("[registry-hub] 远程注册: clientId={}, path={}, serverId={}, event={}",
                clientId, normalizedPath, discovery.getServerId(), event);
    }

    /**
     * 应用远程注销，清除归属并广播 REMOVE 事件（条目不存在时仅清理归属，不广播）。
     */
    private void applyRemoteUnregister(String clientId, RegistrySyncMessage msg) {
        Discovery discovery = msg.getDiscovery();
        String path = msg.getPath();
        if (discovery == null || StringUtils.isEmpty(path)) {
            return;
        }
        String normalizedPath = StringUtils.startWithAppend(path, "/");
        Map<String, String> owned = ownedByClient.get(clientId);
        if (owned != null) {
            owned.remove(normalizedPath);
        }
        Discovery existing = findSameServerId(normalizedPath, discovery.getServerId());
        if (existing == null) {
            // 幂等：条目已不存在（可能已被掉线回收或重复注销），不再重复广播
            return;
        }
        removeFromCache(normalizedPath, discovery.getServerId());
        incrementServiceVersion();
        broadcast(RegistrySyncMessage.of(RegistrySyncMessage.OP_EVENT, clientId)
                .setPath(normalizedPath)
                .setDiscovery(discovery)
                .setEvent(EVENT_REMOVE));
        log.info("[registry-hub] 远程注销: clientId={}, path={}, serverId={}", clientId, normalizedPath, discovery.getServerId());
    }

    /**
     * 在指定路径的服务列表中查找同一 serverId 的现有条目。
     *
     * @param normalizedPath 规范化路径（首斜杠开头）
     * @param serverId       服务实例标识
     * @return 匹配的现有条目，不存在返回 null
     */
    private Discovery findSameServerId(String normalizedPath, String serverId) {
        if (serverId == null) {
            return null;
        }
        List<Discovery> list = localCache.get(StringUtils.startWithAppend(normalizedPath, "/"));
        if (list == null) {
            return null;
        }
        for (Discovery d : list) {
            if (serverId.equals(d.getServerId())) {
                return d;
            }
        }
        return null;
    }

    /**
     * 应答全量快照请求：把当前服务表整体回给请求方。
     */
    private void sendSnapshot(String clientId) {
        Map<String, List<Discovery>> snapshot = new ConcurrentHashMap<>();
        localCache.forEach((path, list) -> snapshot.put(path, new CopyOnWriteArrayList<>(list)));
        sendToClient(clientId, RegistrySyncMessage.of(RegistrySyncMessage.OP_SYNC, HUB_ID).setServices(snapshot));
        log.debug("[registry-hub] 快照应答: clientId={}, paths={}", clientId, snapshot.size());
    }

    /**
     * 客户端掉线：回收该客户端名下的全部注册并广播 REMOVE。
     */
    private void purgeClient(String clientId) {
        Map<String, String> owned = ownedByClient.remove(clientId);
        if (owned == null || owned.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : owned.entrySet()) {
            String path = entry.getKey();
            String serverId = entry.getValue();
            removeFromCache(path, serverId);
            incrementServiceVersion();
            broadcast(RegistrySyncMessage.of(RegistrySyncMessage.OP_EVENT, clientId)
                    .setPath(path)
                    .setDiscovery(Discovery.builder().serverId(serverId).build())
                    .setEvent(EVENT_REMOVE));
            log.info("[registry-hub] 客户端掉线回收注册: clientId={}, path={}, serverId={}", clientId, path, serverId);
        }
    }

    // ======================== 下行投递 ========================

    /**
     * 向所有在线客户端广播消息。
     *
     * @param msg 消息信封
     */
    private void broadcast(RegistrySyncMessage msg) {
        SyncServer server = this.syncServer;
        if (server == null) {
            return;
        }
        String payload = Json.toJson(msg);
        for (String clientId : server.getConnectedClients()) {
            try {
                server.send(clientId, RegistrySyncMessage.topicOf(clientId), payload);
            } catch (Exception e) {
                log.debug("[registry-hub] 事件下发失败: clientId={}, error={}", clientId, e.getMessage());
            }
        }
    }

    /**
     * 向指定客户端投递消息。
     *
     * @param clientId 目标客户端
     * @param msg      消息信封
     */
    private void sendToClient(String clientId, RegistrySyncMessage msg) {
        SyncServer server = this.syncServer;
        if (server == null) {
            return;
        }
        try {
            server.send(clientId, RegistrySyncMessage.topicOf(clientId), Json.toJson(msg));
        } catch (Exception e) {
            log.debug("[registry-hub] 消息投递失败: clientId={}, error={}", clientId, e.getMessage());
        }
    }

    /**
     * 解析上行消息。
     *
     * @param message 原始消息
     * @return 消息信封，解析失败返回 null
     */
    private RegistrySyncMessage parse(Object message) {
        try {
            String json = message instanceof CharSequence text ? text.toString() : Json.toJson(message);
            return Json.fromJson(json, RegistrySyncMessage.class);
        } catch (Exception e) {
            log.warn("[registry-hub] 上行消息解析失败: {}", e.getMessage());
            return null;
        }
    }
}
