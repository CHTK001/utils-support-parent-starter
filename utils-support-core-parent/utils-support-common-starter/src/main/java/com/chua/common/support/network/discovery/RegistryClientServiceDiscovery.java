// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.discovery;

import com.chua.common.support.exception.StartFailedException;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 注册中心客户端实现（{@code registry-client}）。
 *
 * <p>连接到 {@link RegistryHubServiceDiscovery registry-hub}（内嵌/独立注册中心），
 * 基于 {@link com.chua.common.support.network.sync.SyncClient} 通道完成：
 * 本地注册即时生效（写本地缓存供本进程查询），同时把注册/注销/更新操作同步给 hub；
 * 启动时向 hub 请求一次全量快照，之后靠 hub 的增量事件保持服务表一致。</p>
 *
 * <p>协议约定见 {@link RegistrySyncMessage}：上行（注册/注销/快照请求）走共享主题
 * {@link RegistrySyncMessage#TOPIC}；下行（快照/事件）走客户端专属主题
 * {@link RegistrySyncMessage#topicOf(String)}。客户端额外订阅共享主题仅用于
 * 排水（HTTP 服务端把上行消息也入了共享队列，需有消费者），处理器直接忽略。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
@Spi("registry-client")
public class RegistryClientServiceDiscovery extends DefaultServiceDiscovery {

    /**
     * 事件名：新增（与 {@link Event#ADD} 同名，避免枚举序列化歧义直接用字符串）
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
     * 默认传输协议
     */
    private static final String DEFAULT_PROTOCOL = "http";

    /**
     * 默认注册中心地址
     */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * 默认注册中心端口
     */
    private static final int DEFAULT_PORT = 9600;

    /**
     * 客户端标识（进程内唯一，hub 按它路由下行消息、按它归属注册）
     */
    private final String clientId = UUID.randomUUID().toString();

    /**
     * 传输协议（http/websocket/tcp/udp）
     */
    private final String transportProtocol;

    /**
     * 注册中心地址（protocol://host:port）
     */
    private final String serverUrl;

    /**
     * 本实例名下的注册（规范化路径 -&gt; 完整实例信息）：
     * 优雅关闭时逐条注销；对账周期内用于向注册中心重申注册、快照应用时保护本地视图
     */
    private final Map<String, Discovery> owned = new ConcurrentHashMap<>();

    /**
     * 默认对账间隔（毫秒）
     */
    private static final long DEFAULT_RECONCILE_INTERVAL_MILLIS = 30_000L;

    /**
     * 对账间隔下限（毫秒）：过短会对注册中心形成高频快照请求
     */
    private static final long MIN_RECONCILE_INTERVAL_MILLIS = 5_000L;

    /**
     * 对账间隔（毫秒）：周期性重取全量快照 + 重申本实例注册，自愈注册中心重启/事件丢失
     */
    private final long reconcileIntervalMillis;

    /**
     * 对账线程运行标记
     */
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

    /**
     * 对账线程
     */
    private volatile Thread reconcileThread;

    /**
     * 启动状态
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 同步客户端（start 时创建）
     */
    private volatile SyncClient syncClient;

    /**
     * 创建注册中心客户端。
     *
     * <p>地址格式：{@code [protocol://]host[:port]}，默认协议 {@code http}、默认端口 {@code 9600}。</p>
     *
     * @param discoveryOption 发现选项，address 承载注册中心地址
     */
    public RegistryClientServiceDiscovery(DiscoveryOption discoveryOption) {
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
        if ("ws".equalsIgnoreCase(protocol) || "wss".equalsIgnoreCase(protocol)
                || "websocket".equalsIgnoreCase(protocol)) {
            // SPI 实现名统一为 websocket；线上地址用 ws:// 或 wss://（WebSocketSyncClient 只认这两个 scheme）
            this.transportProtocol = "websocket";
            this.serverUrl = ("wss".equalsIgnoreCase(protocol) ? "wss://" : "ws://") + host + ":" + port;
        } else {
            this.transportProtocol = protocol;
            this.serverUrl = protocol + "://" + host + ":" + port;
        }
        this.reconcileIntervalMillis = resolveReconcileInterval(discoveryOption);
    }

    /**
     * 启动客户端：创建传输通道、订阅下行主题、连接并请求全量快照。
     */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        SyncClient client = ServiceProvider.of(SyncClient.class).getNewExtension(transportProtocol, clientId, serverUrl);
        if (client == null) {
            started.set(false);
            throw new StartFailedException("[registry-client] 未找到可用的 SyncClient 实现: " + transportProtocol);
        }
        // 先订阅再连接：HTTP 拉取线程随 connect() 启动，保证首轮拉取就带上全部主题
        client.subscribe(RegistrySyncMessage.topicOf(clientId), this::handleDownlink);
        // 共享主题仅用于排水（服务端把上行消息也入了共享队列），处理器直接忽略
        client.subscribe(RegistrySyncMessage.TOPIC, (topic, message) -> {
        });
        this.syncClient = client;
        client.connect();
        try {
            client.send(RegistrySyncMessage.TOPIC, Json.toJson(
                    RegistrySyncMessage.of(RegistrySyncMessage.OP_SYNC_REQUEST, clientId)));
        } catch (Exception e) {
            // 注册中心暂不可达不阻断启动：拉取线程会持续重连，快照稍后补齐
            log.warn("[registry-client] 快照请求失败（注册中心暂不可达）: {}", e.getMessage());
        }
        startReconcile();
        log.info("[registry-client] 已连接注册中心: {}，clientId={}，对账间隔={}ms", serverUrl, clientId, reconcileIntervalMillis);
    }

    /**
     * 注册服务：本地缓存即时生效，并同步给注册中心。
     */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        // 与 DefaultServiceDiscovery 相同的本地语义；需自行计算前缀路径，避免二次加前缀
        String prefixed = addClusterPrefix(path);
        discovery.setUriSpec(prefixed);
        // upsert：重复注册同一条时先移除旧条目，避免本地列表重复堆积
        removeFromCache(prefixed, discovery.getServerId());
        addToCache(prefixed, discovery);
        incrementServiceVersion();
        owned.put(prefixed, discovery);
        sendToHub(RegistrySyncMessage.of(RegistrySyncMessage.OP_REGISTER, clientId)
                .setPath(prefixed)
                .setDiscovery(discovery));
        return this;
    }

    /**
     * 更新服务：本地 upsert，并按更新语义同步给注册中心（hub 端 upsert 后广播 UPDATE 事件）。
     */
    @Override
    public ServiceDiscovery updateService(String path, Discovery discovery) {
        super.updateService(path, discovery);
        String prefixed = addClusterPrefix(path);
        owned.put(prefixed, discovery);
        sendToHub(RegistrySyncMessage.of(RegistrySyncMessage.OP_UPDATE, clientId)
                .setPath(prefixed)
                .setDiscovery(discovery));
        return this;
    }

    /**
     * 注销钩子：把注销操作同步给注册中心（本地缓存由父类统一处理）。
     */
    @Override
    protected void doUnregister(String path, Discovery discovery) {
        // path 已含集群前缀（AbstractServiceDiscovery.unregisterService 统一处理）
        String serverId = discovery == null ? null : discovery.getServerId();
        Discovery existing = owned.get(path);
        if (serverId == null || existing == null || serverId.equals(existing.getServerId())) {
            owned.remove(path);
        }
        sendToHub(RegistrySyncMessage.of(RegistrySyncMessage.OP_UNREGISTER, clientId)
                .setPath(path)
                .setDiscovery(discovery));
    }

    /**
     * 关闭客户端：尽力注销本实例名下的注册（注册中心侧另有掉线回收兜底），断开通道并清空本地缓存。
     */
    @Override
    public void close() {
        stopReconcile();
        SyncClient client = this.syncClient;
        this.syncClient = null;
        if (client != null) {
            owned.forEach((path, discovery) -> {
                try {
                    client.send(RegistrySyncMessage.TOPIC, Json.toJson(
                            RegistrySyncMessage.of(RegistrySyncMessage.OP_UNREGISTER, clientId)
                                    .setPath(path)
                                    .setDiscovery(discovery)));
                } catch (Exception e) {
                    log.debug("[registry-client] 关闭注销失败: path={}, error={}", path, e.getMessage());
                }
            });
            owned.clear();
            try {
                client.close();
            } catch (Exception e) {
                log.debug("[registry-client] 通道关闭异常: {}", e.getMessage());
            }
            log.info("[registry-client] 已断开注册中心: {}，clientId={}", serverUrl, clientId);
        }
        super.close();
        // 复位启动标记：允许关闭后重新 start()（换通道重连）
        started.set(false);
    }

    // ======================== 对账（自愈） ========================

    /**
     * 解析对账间隔：{@code <=0} 用默认值，并钳制到下限之上。
     *
     * @param option 发现选项
     * @return 对账间隔（毫秒）
     */
    private long resolveReconcileInterval(DiscoveryOption option) {
        long interval = option == null ? DEFAULT_RECONCILE_INTERVAL_MILLIS : option.getReconcileIntervalMillis();
        if (interval <= 0) {
            return DEFAULT_RECONCILE_INTERVAL_MILLIS;
        }
        return Math.max(interval, MIN_RECONCILE_INTERVAL_MILLIS);
    }

    /**
     * 启动对账线程：周期性重取全量快照并重申本实例名下的注册。
     *
     * <p>对账让下列故障在一个周期内自愈：注册中心重启（服务表清零）、
     * 增量事件在传输中丢失、注册/注销上行发送失败、启动时快照请求失败。</p>
     */
    private void startReconcile() {
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        Thread thread = ThreadUtils.newThread(() -> {
            while (reconciling.get()) {
                ThreadUtils.sleep(reconcileIntervalMillis);
                if (!reconciling.get()) {
                    break;
                }
                try {
                    reconcileOnce();
                } catch (Exception e) {
                    log.debug("[registry-client] 对账失败: {}", e.getMessage());
                }
            }
        }, "registry-client-reconcile-" + clientId);
        thread.setDaemon(true);
        thread.start();
        this.reconcileThread = thread;
    }

    /**
     * 停止对账线程。
     */
    private void stopReconcile() {
        reconciling.set(false);
        Thread thread = this.reconcileThread;
        this.reconcileThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 执行一次对账：先重取全量快照，再重申本实例名下的全部注册（均为幂等操作）。
     */
    private void reconcileOnce() {
        SyncClient client = this.syncClient;
        if (client == null) {
            return;
        }
        try {
            client.send(RegistrySyncMessage.TOPIC, Json.toJson(
                    RegistrySyncMessage.of(RegistrySyncMessage.OP_SYNC_REQUEST, clientId)));
        } catch (Exception e) {
            log.debug("[registry-client] 对账快照请求失败: {}", e.getMessage());
        }
        owned.forEach((path, discovery) -> {
            try {
                client.send(RegistrySyncMessage.TOPIC, Json.toJson(
                        RegistrySyncMessage.of(RegistrySyncMessage.OP_REGISTER, clientId)
                                .setPath(path)
                                .setDiscovery(discovery)));
            } catch (Exception e) {
                log.debug("[registry-client] 对账重申注册失败: path={}, error={}", path, e.getMessage());
            }
        });
    }

    // ======================== 下行消息处理 ========================

    /**
     * 处理 hub -&gt; client 的快照与事件。
     *
     * @param topic   消息主题
     * @param message 消息内容（JSON 字符串）
     */
    private void handleDownlink(String topic, Object message) {
        RegistrySyncMessage msg = parse(message);
        if (msg == null || msg.getOp() == null) {
            return;
        }
        if (clientId.equals(msg.getClientId())) {
            // 自回声过滤：hub 广播的事件携带来源 clientId，本人已本地生效
            return;
        }
        switch (msg.getOp()) {
            case RegistrySyncMessage.OP_SYNC -> applySnapshot(msg.getServices());
            case RegistrySyncMessage.OP_EVENT -> applyEvent(msg);
            default -> {
                // register/unregister/sync-request 为上行消息，客户端不消费
            }
        }
    }

    /**
     * 应用全量快照：整体替换本地服务表。
     *
     * @param services 路径 -&gt; 服务实例列表
     */
    private void applySnapshot(Map<String, List<Discovery>> services) {
        if (services == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        services.forEach((path, list) -> {
            if (StringUtils.isEmpty(path)) {
                return;
            }
            String normalized = StringUtils.startWithAppend(path, "/");
            seen.add(normalized);
            if (list == null || list.isEmpty()) {
                localCache.remove(normalized);
            } else {
                replaceCache(normalized, list);
            }
        });
        // 快照里没有的路径说明已全部下线；本实例名下的注册除外——
        // 注册上行可能晚于快照到达（或曾在传输中丢失），不能被快照清掉
        localCache.keySet().removeIf(path -> !seen.contains(path) && !owned.containsKey(path));
        // 恢复本实例名下的注册，保证本地视图始终包含自己提供的服务
        owned.forEach((path, discovery) -> {
            removeFromCache(path, discovery.getServerId());
            addToCache(path, discovery);
        });
        incrementServiceVersion();
        log.debug("[registry-client] 快照已应用: paths={}, owned={}", seen.size(), owned.size());
    }

    /**
     * 应用增量事件（add/update/remove）。
     *
     * @param msg 消息信封
     */
    private void applyEvent(RegistrySyncMessage msg) {
        String path = msg.getPath();
        Discovery discovery = msg.getDiscovery();
        if (StringUtils.isEmpty(path) || discovery == null) {
            return;
        }
        String normalized = StringUtils.startWithAppend(path, "/");
        String event = msg.getEvent();
        if (EVENT_ADD.equals(event) || EVENT_UPDATE.equals(event)) {
            // upsert：先按 serverId 移除再追加，避免重复条目
            removeFromCache(normalized, discovery.getServerId());
            addToCache(normalized, discovery);
            incrementServiceVersion();
        } else if (EVENT_REMOVE.equals(event)) {
            removeFromCache(normalized, discovery.getServerId());
            incrementServiceVersion();
        }
    }

    // ======================== 工具方法 ========================

    /**
     * 向注册中心发送上行消息。
     *
     * @param msg 消息信封
     */
    private void sendToHub(RegistrySyncMessage msg) {
        SyncClient client = this.syncClient;
        if (client == null) {
            log.warn("[registry-client] 尚未启动，丢弃消息: op={}, path={}", msg.getOp(), msg.getPath());
            return;
        }
        try {
            client.send(RegistrySyncMessage.TOPIC, Json.toJson(msg));
        } catch (Exception e) {
            log.warn("[registry-client] 消息发送失败: op={}, path={}, error={}", msg.getOp(), msg.getPath(), e.getMessage());
        }
    }

    /**
     * 解析下行消息。
     *
     * @param message 原始消息
     * @return 消息信封，解析失败返回 null
     */
    private RegistrySyncMessage parse(Object message) {
        try {
            String json = message instanceof CharSequence text ? text.toString() : Json.toJson(message);
            return Json.fromJson(json, RegistrySyncMessage.class);
        } catch (Exception e) {
            log.warn("[registry-client] 下行消息解析失败: {}", e.getMessage());
            return null;
        }
    }
}
