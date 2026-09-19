package com.chua.common.support.scatter.discovery;

import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.scatter.ScatterContext;
import com.chua.common.support.scatter.ScatterNode;
import com.chua.common.support.scatter.ScatterRemoteClient;
import com.chua.common.support.scatter.ScatterResult;
import com.chua.common.support.scatter.ScatterServiceDiscovery;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.ScatterSyncHelper;
import com.chua.common.support.scatter.node.ScatterNodeHandler;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import com.chua.common.support.lang.json.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * scatter 发现基类：本地服务 hash 表（按 serverId 幂等去重）+ 自身注册 + 心跳剔除 + 持久化。
 *
 * <p>帧处理（{@link ScatterNodeHandler}）：REQ 拉取 → 返回服务表 RESP；PUSH 推送 → 合并去重 + ACK。
 * 每轮同步（{@link #doDiscoveryRound()}）由子类实现（路由 gossip / seed 同步）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractScatterDiscovery extends AbstractServiceDiscovery
        implements ScatterServiceDiscovery, ScatterNodeHandler {

    /** 心跳失败计数：serverId -> 连续失败次数 */
    protected final Map<String, Integer> heartbeatFailCounts = new ConcurrentHashMap<>();

    protected final ScatterSetting setting;
    /** remote客户端 */
    protected ScatterRemoteClient remoteClient;

    /** discoveryExecutor */
    private ScheduledExecutorService discoveryExecutor;
    private volatile boolean started = false;
    /** 请求 ID 生成器（线程安全单调递增） */
    private final AtomicInteger requestIdSeq = new AtomicInteger(0);

    /**
     * 构造方法，创建 AbstractScatterDiscovery 实例。
     *
     * @param setting 方法入参 setting
     */
    protected AbstractScatterDiscovery(ScatterSetting setting) {
        super(new DiscoveryOption());
        this.setting = setting == null ? new ScatterSetting() : setting;
        // 持久化文件默认名按 nodeId 隔离：避免同目录多实例互相覆盖/加载（显式设置的 persistenceFile 不受影响）
        if (".scatter-nodes.json".equals(this.setting.getPersistenceFile())
                && this.setting.getNodeId() != null) {
            this.setting.setPersistenceFile(".scatter-nodes-" + this.setting.getNodeId() + ".json");
        }
    }

    /** 设置远程客户端（未启动前）。 */
    @Override
    public ScatterServiceDiscovery remoteClient(ScatterRemoteClient remoteClient) {
        this.remoteClient = remoteClient;
        return this;
    }

    /**
     * 获取分组。
     * @return 结果字符串
     */
    public String getGroupId() {
        return setting.getGroupId();
    }

    /**
     * 获取配置。
     * @return ScatterSetting 对象
     */
    public ScatterSetting getSetting() {
        return setting;
    }

    @Override
    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        started = true;
        loadPersistedNodes();
        registerSelf();
        discoveryExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "scatter-discovery");
                    t.setDaemon(true);
                    return t;
                });
        discoveryExecutor.scheduleAtFixedRate(this::discoveryRound,
                setting.getAutoDiscoveryIntervalMillis(),
                setting.getAutoDiscoveryIntervalMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 每轮同步：注册自身 → 子类发现/扩散 → 心跳探活 → 持久化。 */
    private void discoveryRound() {
        try {
            updateSelfWeight();
            doDiscoveryRound();
            healthCheck();
            persistNodes();
        } catch (Exception e) {
            log.debug("发现轮异常: {}", e.getMessage());
        }
    }

    /**
     * 子类实现每轮发现/扩散逻辑（路由 gossip 或 seed 同步）。
     */
    protected abstract void doDiscoveryRound();

    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        addToCache(path, discovery);
        incrementServiceVersion();
        return this;
    }

    /** 注册自身到本地 hash 表。 */
    public void registerSelf() {
        // scatterPort > 0 说明 nodeServer 已启动（由 DefaultScatter 在 start() 中填充）
        // 使用 scatter 通信端口注册，确保对端可通过该端口连接到本节点的 scatter 服务
        int selfPort = setting.getScatterPort() > 0 ? setting.getScatterPort() : setting.getPort();
        Discovery self = Discovery.builder()
                .id(setting.getNodeId())
                .serverId(setting.getNodeId())
                .scatterId(getGroupId())
                .protocol(setting.getProtocol())
                .host(setting.effectiveHost())
                .port(selfPort)
                .timeout((int) setting.getTimeoutMillis())
                .weight(1.0)
                .uriSpec(setting.getServicePath())
                .build();
        updateService(setting.getServicePath(), self);
    }

    /** 更新自身动态权重（同步即心跳）。 */
    protected void updateSelfWeight() {
        // 使用 scatter 通信端口（与 registerSelf 保持一致），确保对端通过该端口可连接
        int selfPort = setting.getScatterPort() > 0 ? setting.getScatterPort() : setting.getPort();
        Discovery self = Discovery.builder()
                .id(setting.getNodeId())
                .serverId(setting.getNodeId())
                .scatterId(getGroupId())
                .protocol(setting.getProtocol())
                .host(setting.effectiveHost())
                .port(selfPort)
                .timeout((int) setting.getTimeoutMillis())
                .weight(computeDynamicWeight())
                .uriSpec(setting.getServicePath())
                .build();
        updateService(setting.getServicePath(), self);
    }

    /**
     * 动态权重（cpu+内存负载，0.1-1.0）。
     * @return 结果数值
     */
    protected double computeDynamicWeight() {
        try {
            double cpu = 0.5;
            double mem = 0.5;
            try {
                java.lang.management.OperatingSystemMXBean osBean =
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
                    // getSystemLoadAverage() 在 Windows 上恒返回 -1，改用 getCpuLoad()
                    double loadAvg = sun.getSystemLoadAverage();
                    if (loadAvg > 0) {
                        cpu = Math.max(0.05, Math.min(1.0,
                                loadAvg / Math.max(1, Runtime.getRuntime().availableProcessors())));
                    } else {
                        // Windows 回退：使用 CPU 利用率（-1 表示不可用）
                        double cpuLoad = sun.getCpuLoad();
                        cpu = cpuLoad > 0 ? Math.max(0.05, Math.min(1.0, cpuLoad)) : 0.3;
                    }
                    long total = sun.getTotalMemorySize();
                    long free = sun.getFreeMemorySize();
                    mem = total > 0 ? Math.max(0.05, Math.min(1.0, 1.0 - (double) free / total)) : 0.5;
                }
            } catch (Throwable ignored) {
            }
            double load = 1.0 - (0.6 * cpu + 0.4 * mem);
            return Math.max(0.1, Math.min(1.0, load));
        } catch (Throwable e) {
            return 0.5;
        }
    }

    /**
     * 帧处理：REQ 拉取（返回完整服务表列表，供对端逐条合并——hash 同步）/ PUSH 合并。
     */
    @Override
    public byte[] handle(ScatterFrame frame) {
        try {
            if (frame.getType() == ScatterProtocol.TYPE_REQ) {
                Set<Discovery> services = getServiceAll(frame.getPath());
                // 返回完整服务表（JSON 数组），对端逐条按 serverId 合并
                byte[] payload = Json.toJson(new ArrayList<>(services)).getBytes(StandardCharsets.UTF_8);
                return new ScatterFrame(ScatterProtocol.TYPE_RESP, frame.getRequestId(),
                        frame.getPath(), payload).encode();
            } else if (frame.getType() == ScatterProtocol.TYPE_PUSH) {
                String json = new String(frame.getPayload(), StandardCharsets.UTF_8);
                Discovery remote = Json.fromJson(json, Discovery.class);
                if (remote != null && getGroupId().equals(remote.getScatterId())) {
                    updateService(frame.getPath(), remote);
                }
                return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                        frame.getPath(), new byte[0]).encode();
            } else if (frame.getType() == ScatterProtocol.TYPE_ELEC) {
                // 选举通知：对端节点成为新引导，本地把它纳入 seed 列表（后续 seed 掉线时用它同步）
                String json = new String(frame.getPayload(), StandardCharsets.UTF_8);
                Discovery elected = Json.fromJson(json, Discovery.class);
                if (elected != null && elected.getHost() != null) {
                    String seedAddr = elected.getHost() + ":" + elected.getPort();
                    List<String> seeds = new ArrayList<>(setting.getSeeds() == null
                            ? List.of() : setting.getSeeds());
                    if (!seeds.contains(seedAddr)) {
                        seeds.add(seedAddr);
                        setting.setSeeds(seeds);
                        log.info("收到选举通知，新增 seed 引导: {}", seedAddr);
                    }
                }
                return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                        frame.getPath(), new byte[0]).encode();
            }
            return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                    frame.getPath(), new byte[0]).encode();
        } catch (Exception e) {
            log.debug("帧处理异常: {}", e.getMessage());
            return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                    frame.getPath(), new byte[0]).encode();
        }
    }

    /** 心跳探活：对表内非自身节点逐心跳，连续失败达阈值剔除。 */
    protected void healthCheck() {
        if (setting.getHeartbeatIntervalMillis() <= 0) {
            return;
        }
        Set<Discovery> services = getServiceAll(setting.getServicePath());
        for (Discovery d : services) {
            if (d == null || d.getServerId() == null
                    || setting.getNodeId().equals(d.getServerId())) {
                continue;
            }
            if (isSeedNode(d)) {
                continue;
            }
            if (isUnroutable(d.getHost())) {
                continue;
            }
            probeHeartbeat(d);
        }
    }

    /**
     * 探活单个节点：轻量 ICMP/TCP 探针（复用 remoteClient 通道），返回同步结果用于合并。
     * @param d 方法入参 d
     */
    protected void probeHeartbeat(Discovery d) {
        ScatterNode node = new ScatterNode(d.getServerId(), d.getHost(), d.getPort(),
                d.getProtocol(), getGroupId(), setting.getServicePath());
        long hbTimeout = setting.getHeartbeatTimeoutMillis() > 0
                ? setting.getHeartbeatTimeoutMillis() : setting.getTimeoutMillis();
        ScatterContext ctx = new ScatterContext(String.valueOf(genRequestId()),
                setting.getServicePath(), hbTimeout);
        ScatterResult<List<Discovery>> result = ScatterSyncHelper.fetch(ctx, node, hbTimeout);
        if (result != null && result.isSuccess() && result.getData() != null) {
            heartbeatFailCounts.remove(node.getNodeId());
            mergeRemote(result.getData());
        } else {
            onHeartbeatFail(node);
        }
    }

    /**
     * 合并对端服务表：逐条按 serverId 幂等合并（同分组）。
     * @param remote 方法入参 remote
     */
    protected void mergeRemote(java.util.List<Discovery> remote) {
        for (Discovery r : remote) {
            if (r != null && r.getServerId() != null && getGroupId().equals(r.getScatterId())) {
                updateService(setting.getServicePath(), r);
            }
        }
    }

    /**
     * 心跳失败：累计计数，达阈值剔除。
     * @param node 节点，不允许为 null
     */
    protected void onHeartbeatFail(ScatterNode node) {
        int count = heartbeatFailCounts.merge(node.getNodeId(), 1, Integer::sum);
        if (count >= setting.getFailRemoveCount()) {
            removeFromCache(setting.getServicePath(), node.getNodeId());
            heartbeatFailCounts.remove(node.getNodeId());
            log.info("节点连续 {} 次心跳失败，已剔除: {}", setting.getFailRemoveCount(), node.getNodeId());
        }
    }

    /**
     * seed 引导条目不参与心跳剔除（仅引导地址）。
     * @param d 方法入参 d
     * @return 是否成功（true 表示成功）
     */
    protected boolean isSeedNode(Discovery d) {
        return d.getMetadata() != null
                && (Boolean.parseBoolean(d.getMetadata().get("seed"))
                || Boolean.parseBoolean(d.getMetadata().get("self")));
    }

    /**
     * 生成单调递增的请求 ID（避免 UUID hash 碰撞与负数）。
     * @return 结果数值
     */
    protected int genRequestId() {
        return Math.abs(requestIdSeq.incrementAndGet());
    }

    /**
     * 不可路由地址（0.0.0.0 等）跳过探活。
     * @param host 主机，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    protected boolean isUnroutable(String host) {
        return host == null || host.isBlank() || "0.0.0.0".equals(host);
    }

    /**
     * 解析 seed 地址为节点列表（供子类同步/扩散用）。
     * @return 结果列表，无数据时为空列表
     */
    protected List<ScatterNode> resolveSeeds() {
        List<ScatterNode> nodes = new ArrayList<>();
        if (setting.getSeeds() == null) {
            return nodes;
        }
        for (String seed : setting.getSeeds()) {
            if (seed == null || seed.isBlank()) {
                continue;
            }
            com.chua.common.support.scatter.SeedAddress addr =
                    com.chua.common.support.scatter.SeedAddress.parse(seed);
            if (addr == null) {
                continue;
            }
            int port = addr.effectivePort(setting.getPort());
            String nodeId = addr.getHost() + ":" + port;
            nodes.add(new ScatterNode(nodeId, addr.getHost(), port,
                    setting.getProtocol(), getGroupId(), setting.getServicePath()));
        }
        return nodes;
    }

    /** 持久化：服务表 + seed 列表落盘。 */
    protected void persistNodes() {
        if (!setting.isPersistenceEnabled()) {
            return;
        }
        try {
            List<Discovery> nodes = new ArrayList<>(getServiceAll(setting.getServicePath()));
            List<ScatterNode> seeds = resolveSeeds();
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("nodes", nodes);
            root.put("seeds", seeds);
            root.put("persistedAt", System.currentTimeMillis());
            Path path = Paths.get(setting.getPersistenceFile());
            Files.createDirectories(path.toAbsolutePath().getParent() == null
                    ? Paths.get(".") : path.toAbsolutePath().getParent());
            Files.write(path, Json.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("持久化失败: {}", e.getMessage());
        }
    }

    /** 启动加载持久化节点。 */
    @SuppressWarnings("unchecked")
    protected void loadPersistedNodes() {
        if (!setting.isPersistenceEnabled()) {
            return;
        }
        try {
            Path path = Paths.get(setting.getPersistenceFile());
            if (!Files.exists(path)) {
                return;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            com.chua.common.support.lang.json.JsonNode root = Json.parse(json);
            if (root == null) {
                return;
            }
            com.chua.common.support.lang.json.JsonNode nodesNode = root.get("nodes");
            if (nodesNode == null) {
                return;
            }
            Object nodesObj = nodesNode.getValue();
            if (nodesObj != null) {
                List<Discovery> nodes = Json.fromJsonToList(Json.toJson(nodesObj), Discovery.class);
                for (Discovery d : nodes) {
                    if (d.getServerId() != null) {
                        updateService(setting.getServicePath(), d);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("加载持久化节点失败: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        started = false;
        if (discoveryExecutor != null) {
            discoveryExecutor.shutdownNow();
        }
    }

    /**
     * 优雅关闭：等待当前一轮 discoveryRound 完成后再停止调度器，
     * 确保正在执行的 healthCheck → removeFromCache 不会被中断。
     */
    public void gracefulClose() {
        started = false;
        if (discoveryExecutor != null) {
            discoveryExecutor.shutdown();
            try {
                if (!discoveryExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    discoveryExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                discoveryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
