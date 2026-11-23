// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter.discovery;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.Event;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.discovery.ServiceDiscoveryListener;
import com.chua.common.support.scatter.ScatterContext;
import com.chua.common.support.scatter.ScatterMemberInfo;
import com.chua.common.support.scatter.ScatterNetworks;
import com.chua.common.support.scatter.ScatterNode;
import com.chua.common.support.scatter.ScatterRemoteClient;
import com.chua.common.support.scatter.ScatterResult;
import com.chua.common.support.scatter.ScatterServiceDiscovery;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.ScatterStats;
import com.chua.common.support.scatter.ScatterSyncHelper;
import com.chua.common.support.scatter.node.ScatterNodeHandler;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import com.chua.common.support.utils.StringUtils;

/**
 * scatter 发现基类：本地服务 hash 表（按 serverId 幂等去重）+ 自身注册 + 心跳剔除 + 持久化。
 *
 * <p>帧处理（{@link ScatterNodeHandler}）：REQ 拉取 → 返回服务表 RESP；PUSH 推送 → 合并去重 + ACK。
 * 每轮同步（{@link #doDiscoveryRound()}）由子类实现（路由 gossip / seed 同步）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractScatterDiscovery extends AbstractServiceDiscovery
        implements ScatterServiceDiscovery, ScatterNodeHandler {

    /**
     * 心跳失败计数：serverId -> 连续失败次数
     */
    protected final Map<String, Integer> heartbeatFailCounts = new ConcurrentHashMap<>();

    protected final ScatterSetting setting;
    /**
     * remote客户端
     */
    protected ScatterRemoteClient remoteClient;

    /**
     * discoveryExecutor
     */
    private ScheduledExecutorService discoveryExecutor;
    private volatile boolean started = false;
    /**
     * 请求 ID 生成器（线程安全单调递增）
     */
    private final AtomicInteger requestIdSeq = new AtomicInteger(0);

    // ==================== 成员变更事件与观测统计 ====================

    /**
     * 成员变更监听器（CopyOnWriteArrayList：回调遍历免锁，注册/注销低频）
     */
    private final List<ServiceDiscoveryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 成员最近活跃时间（serverId -&gt; 毫秒时间戳，本节点视角）
     */
    private final Map<String, Long> lastSeenAtMillis = new ConcurrentHashMap<>();

    /**
     * 累计发现轮数
     */
    protected final AtomicLong discoveryRounds = new AtomicLong();
    /**
     * 发现轮异常次数
     */
    protected final AtomicLong roundFailures = new AtomicLong();
    /**
     * 同步（拉取合并）成功次数
     */
    protected final AtomicLong syncSuccesses = new AtomicLong();
    /**
     * 同步（拉取合并）失败次数
     */
    protected final AtomicLong syncFailures = new AtomicLong();
    /**
     * 心跳探活发送次数
     */
    protected final AtomicLong heartbeatsSent = new AtomicLong();
    /**
     * 心跳探活失败次数（含达阈值剔除前的失败）
     */
    protected final AtomicLong heartbeatFailures = new AtomicLong();
    /**
     * 成员新增次数（含自身注册与远端合并入表）
     */
    protected final AtomicLong nodesAdded = new AtomicLong();
    /**
     * 成员地址/协议变化更新次数（仅权重变化不计入）
     */
    protected final AtomicLong nodesUpdated = new AtomicLong();
    /**
     * 成员剔除次数（心跳超限 / 优雅离网 / 注销）
     */
    protected final AtomicLong nodesRemoved = new AtomicLong();
    /**
     * 收到的 REQ 帧数
     */
    protected final AtomicLong reqFramesReceived = new AtomicLong();
    /**
     * 收到的 PUSH 帧数
     */
    protected final AtomicLong pushFramesReceived = new AtomicLong();
    /**
     * 收到的 ELEC 选举帧数
     */
    protected final AtomicLong elecFramesReceived = new AtomicLong();
    /**
     * 收到的 DISC 自动发现宣告帧数
     */
    protected final AtomicLong discFramesReceived = new AtomicLong();
    /**
     * 收到的 LEAVE 优雅离网帧数
     */
    protected final AtomicLong leaveFramesReceived = new AtomicLong();
    /**
     * 收到的未知类型帧数
     */
    protected final AtomicLong unknownFrames = new AtomicLong();
    /**
     * 最后一轮发现完成时间戳（毫秒；0 表示尚未完成任何一轮）
     */
    protected final AtomicLong lastRoundAtMillis = new AtomicLong();
    /**
     * 本节点发现服务启动时间戳（毫秒；0 表示未启动）
     */
    protected final AtomicLong startedAtMillis = new AtomicLong();

    /**
     * seed 网关标记元数据键（与 SeedModeDiscovery 的 METADATA_SEED 保持一致）
     */
    static final String METADATA_KEY_SEED = "seed";
    /**
     * seed 降级瞬态标记元数据键（降级期间由 SeedModeDiscovery 按轮重写）
     */
    static final String METADATA_KEY_SEED_DOWN = "seedDown";

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

    /**
     * 设置远程客户端（未启动前）。
     */
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

    /**
     * 对外宣告地址：announceHost 优先；host 为空或 0.0.0.0 时推断本机非回环 IPv4
     * （自动发现场景下对端需用该地址回连，0.0.0.0 不可路由）。
     *
     * @return 宣告地址
     */
    protected String advertiseHost() {
        String host = setting.effectiveHost();
        if (StringUtils.isBlank(host) || "0.0.0.0".equals(host)) {
            String local = ScatterNetworks.localIPv4();
            if (local != null) {
                return local;
            }
        }
        return host;
    }

    @Override
    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        started = true;
        startedAtMillis.set(System.currentTimeMillis());
        loadPersistedNodes();
        registerSelf();
// [P3C C1 豁免] 场景化创建线程，需文档化
        discoveryExecutor = new ScheduledThreadPoolExecutor(1, r -> {
// [P3C C1 豁免] 场景化创建线程，需文档化
                    Thread t = new Thread(r, "scatter-discovery");
                    t.setDaemon(true);
                    return t;
                });
        // 首轮尽快执行（上限 2s），加快新节点入网收敛
        discoveryExecutor.scheduleAtFixedRate(this::discoveryRound,
                Math.min(setting.getAutoDiscoveryIntervalMillis(), 2000L),
                setting.getAutoDiscoveryIntervalMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 每轮同步：注册自身 → 子类发现/扩散 → 心跳探活 → 持久化。
     */
    private void discoveryRound() {
        try {
            discoveryRounds.incrementAndGet();
            updateSelfWeight();
            doDiscoveryRound();
            healthCheck();
            persistNodes();
            lastRoundAtMillis.set(System.currentTimeMillis());
        } catch (Exception e) {
            roundFailures.incrementAndGet();
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

    /**
     * 注册自身到本地 hash 表。
     */
    public void registerSelf() {
        upsertSelf(1.0);
    }

    /**
     * 更新自身动态权重（同步即心跳）。
     */
    protected void updateSelfWeight() {
        upsertSelf(computeDynamicWeight());
    }

    /**
     * 注册/更新自身条目并触发成员事件（仅权重变化不重复上报 UPDATE）。
     *
     * @param weight 动态权重
     */
    private void upsertSelf(double weight) {
        // scatterPort > 0 说明 nodeServer 已启动（由 DefaultScatter 在 start() 中填充）
        // 使用 scatter 通信端口注册，确保对端可通过该端口连接到本节点的 scatter 服务
        Discovery self = buildSelfDiscovery(weight);
        Discovery old = findByServerId(setting.getServicePath(), setting.getNodeId());
        updateService(setting.getServicePath(), self);
        lastSeenAtMillis.put(setting.getNodeId(), System.currentTimeMillis());
        if (old == null) {
            nodesAdded.incrementAndGet();
            fireEvent(self, Event.ADD);
        } else if (memberChanged(old, self)) {
            nodesUpdated.incrementAndGet();
            fireEvent(self, Event.UPDATE);
        }
    }

    /**
     * 构建本节点服务条目，并合并元数据（metadata 保留修复）：
     * 先继承本地已有 self 条目的动态元数据（如 seed 引导/网关模式的 {@code seed=true}，
     * 避免每轮重建条目时被裸条目覆盖剥掉），transient 的 {@code seedDown} 标记不保留
     * （由 SeedModeDiscovery.markSeedDown 在降级期间按轮重新写入，seed 恢复后自动清除）；
     * 再叠加 {@link ScatterSetting#getMetadata()} 用户显式配置（配置优先）。
     *
     * @param weight 动态权重
     * @return 本节点服务条目
     */
    private Discovery buildSelfDiscovery(double weight) {
        int selfPort = setting.getScatterPort() > 0 ? setting.getScatterPort() : setting.getPort();
        Map<String, String> metadata = buildSelfMetadata();
        return Discovery.builder()
                .id(setting.getNodeId())
                .serverId(setting.getNodeId())
                .scatterId(getGroupId())
                .protocol(setting.getProtocol())
                .host(advertiseHost())
                .port(selfPort)
                .timeout((int) setting.getTimeoutMillis())
                .weight(weight)
                .uriSpec(setting.getServicePath())
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }

    /**
     * 构建本节点元数据（metadata 保留修复）：
     * 先继承本地已有 self 条目的动态元数据（transient 的 {@code seedDown} 与
     * 非网关节点的 {@code seed} 标记除外），网关模式补写 {@code seed=true}，
     * 最后叠加 {@link ScatterSetting#getMetadata()} 用户显式配置（配置优先）。
     *
     * @return 合并后的元数据（可能为空 map）
     */
    protected Map<String, String> buildSelfMetadata() {
        Map<String, String> metadata = Wraps.linkedMap();
        Discovery existing = findByServerId(setting.getServicePath(), setting.getNodeId());
        if (existing != null && existing.getMetadata() != null) {
            for (Map.Entry<String, String> en : existing.getMetadata().entrySet()) {
                // "seed" 不随继承保留：worker 节点若继承 seed 引导条目的标记会被全网排除出负载均衡，
                // 仅网关模式（isGatewayNode）按需补写；"seedDown" 为降级瞬态标记，由 markSeedDown 按轮重写
                if (!METADATA_KEY_SEED.equals(en.getKey())
                        && !METADATA_KEY_SEED_DOWN.equals(en.getKey())) {
                    metadata.put(en.getKey(), en.getValue());
                }
            }
        }
        if (isGatewayNode()) {
            metadata.put(METADATA_KEY_SEED, "true");
        }
        if (setting.getMetadata() != null) {
            metadata.putAll(setting.getMetadata());
        }
        return metadata;
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
            } catch (Throwable e) {
                log.debug("[scatter] 负载采集失败，回退默认负载: {}", e.getMessage());
            }
            double load = 1.0 - (0.6 * cpu + 0.4 * mem);
            return Math.max(0.1, Math.min(1.0, load));
        } catch (Throwable e) {
            return 0.5;
        }
    }

    /**
     * 帧处理：REQ 拉取（返回完整服务表列表，供对端逐条合并——hash 同步）/ PUSH 合并 /
     * ELEC 选举 / DISC 宣告 / LEAVE 优雅离网；未知类型计数忽略并返回 ACK。
     */
    @Override
    public byte[] handle(ScatterFrame frame) {
        try {
            if (frame.getType() == ScatterProtocol.TYPE_REQ) {
                reqFramesReceived.incrementAndGet();
                Set<Discovery> services = getServiceAll(frame.getPath());
                // 返回完整服务表（JSON 数组），对端逐条按 serverId 合并
                byte[] payload = Json.toJson(Wraps.list(services)).getBytes(StandardCharsets.UTF_8);
                return new ScatterFrame(ScatterProtocol.TYPE_RESP, frame.getRequestId(),
                        frame.getPath(), payload).encode();
            } else if (frame.getType() == ScatterProtocol.TYPE_PUSH) {
                pushFramesReceived.incrementAndGet();
                String json = new String(frame.getPayload(), StandardCharsets.UTF_8);
                Discovery remote = Json.fromJson(json, Discovery.class);
                if (remote != null && getGroupId().equals(remote.getScatterId())) {
                    // 统一走 mergeRemote：与 REQ/DISC 合并路径一致（事件触发 + 活跃时间维护）
                    mergeRemote(List.of(remote));
                }
                return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                        frame.getPath(), new byte[0]).encode();
            } else if (frame.getType() == ScatterProtocol.TYPE_ELEC) {
                elecFramesReceived.incrementAndGet();
                // 选举通知：对端节点成为新引导，本地把它纳入 seed 列表（后续 seed 掉线时用它同步）
                String json = new String(frame.getPayload(), StandardCharsets.UTF_8);
                Discovery elected = Json.fromJson(json, Discovery.class);
                if (elected != null && elected.getHost() != null) {
                    String seedAddr = elected.getHost() + ":" + elected.getPort();
                    List<String> seeds = Wraps.list(setting.getSeeds() == null
                            ? List.of() : setting.getSeeds());
                    if (!seeds.contains(seedAddr)) {
                        seeds.add(seedAddr);
                        setting.setSeeds(seeds);
                        log.info("收到选举通知，新增 seed 引导: {}", seedAddr);
                    }
                }
                return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                        frame.getPath(), new byte[0]).encode();
            } else if (frame.getType() == ScatterProtocol.TYPE_DISC) {
                discFramesReceived.incrementAndGet();
                // 自动发现宣告（UDP 多播/广播）：payload 为宣告方全量服务表，逐条合并同分组条目
                String json = new String(frame.getPayload(), StandardCharsets.UTF_8);
                List<Discovery> announced = Json.fromJsonToList(json, Discovery.class);
                if (announced != null && !announced.isEmpty()) {
                    mergeRemote(announced);
                }
                return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                        frame.getPath(), new byte[0]).encode();
            } else if (frame.getType() == ScatterProtocol.TYPE_LEAVE) {
                // 优雅离网通知：立即从本地表剔除该节点并触发 REMOVE 事件（无需等待心跳超限）
                leaveFramesReceived.incrementAndGet();
                String json = new String(frame.getPayload(), StandardCharsets.UTF_8);
                Discovery leaving = Json.fromJson(json, Discovery.class);
                if (leaving != null && leaving.getServerId() != null
                        && getGroupId().equals(leaving.getScatterId())) {
                    Discovery removed = findByServerId(frame.getPath(), leaving.getServerId());
                    removeFromCache(frame.getPath(), leaving.getServerId());
                    heartbeatFailCounts.remove(leaving.getServerId());
                    lastSeenAtMillis.remove(leaving.getServerId());
                    nodesRemoved.incrementAndGet();
                    fireEvent(removed != null ? removed : leaving, Event.REMOVE);
                    log.debug("节点优雅离网: {}", leaving.getServerId());
                }
                return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                        frame.getPath(), new byte[0]).encode();
            } else {
                unknownFrames.incrementAndGet();
                log.debug("收到未知类型帧: type={}", frame.getType());
            }
            return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                    frame.getPath(), new byte[0]).encode();
        } catch (Exception e) {
            log.debug("帧处理异常: {}", e.getMessage());
            return new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                    frame.getPath(), new byte[0]).encode();
        }
    }

    /**
     * 心跳探活：对表内非自身节点逐心跳，连续失败达阈值剔除。
     */
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
        heartbeatsSent.incrementAndGet();
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
                Discovery old = findByServerId(setting.getServicePath(), r.getServerId());
                updateService(setting.getServicePath(), r);
                lastSeenAtMillis.put(r.getServerId(), System.currentTimeMillis());
                if (old == null) {
                    nodesAdded.incrementAndGet();
                    fireEvent(r, Event.ADD);
                } else if (memberChanged(old, r)) {
                    nodesUpdated.incrementAndGet();
                    fireEvent(r, Event.UPDATE);
                }
            }
        }
    }

    /**
     * 心跳失败：累计计数，达阈值剔除。
     * @param node 节点，不允许为 null
     */
    protected void onHeartbeatFail(ScatterNode node) {
        heartbeatFailures.incrementAndGet();
        int count = heartbeatFailCounts.merge(node.getNodeId(), 1, Integer::sum);
        if (count >= setting.getFailRemoveCount()) {
            Discovery removed = findByServerId(setting.getServicePath(), node.getNodeId());
            removeFromCache(setting.getServicePath(), node.getNodeId());
            heartbeatFailCounts.remove(node.getNodeId());
            lastSeenAtMillis.remove(node.getNodeId());
            nodesRemoved.incrementAndGet();
            if (removed != null) {
                fireEvent(removed, Event.REMOVE);
            }
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
        return StringUtils.isBlank(host) || "0.0.0.0".equals(host);
    }

    /**
     * 解析 seed 地址为节点列表（供子类同步/扩散用）。
     * @return 结果列表，无数据时为空列表
     */
    protected List<ScatterNode> resolveSeeds() {
        List<ScatterNode> nodes = Wraps.list();
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

    /**
     * 持久化：服务表 + seed 列表落盘。
     */
    protected void persistNodes() {
        if (!setting.isPersistenceEnabled()) {
            return;
        }
        try {
            List<Discovery> nodes = Wraps.list(getServiceAll(setting.getServicePath()));
            List<ScatterNode> seeds = resolveSeeds();
            Map<String, Object> root = Wraps.linkedMap();
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

    /**
     * 启动加载持久化节点。
     */
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

    // ==================== 成员变更订阅 ====================

    /**
     * scatter 基于本地 hash 表 + 同步/心跳驱动，天然支持成员变更事件订阅。
     */
    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void unsubscribe(String serviceName, ServiceDiscoveryListener listener) {
        listeners.remove(listener);
    }

    /**
     * 触发成员变更事件（逐个回调，单个监听器异常不影响其他监听器与本节点流程）。
     *
     * @param discovery 成员条目
     * @param event     事件类型
     */
    private void fireEvent(Discovery discovery, Event event) {
        if (listeners.isEmpty() || discovery == null) {
            return;
        }
        String path = setting.getServicePath();
        for (ServiceDiscoveryListener listener : listeners) {
            try {
                listener.listen(path, discovery, event);
            } catch (Exception e) {
                log.debug("成员变更监听器回调异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 成员身份是否发生变化（host/port/protocol；仅 weight 变化不算，避免每轮权重刷新刷屏）。
     *
     * @param old 旧条目
     * @param now 新条目
     * @return 是否变化
     */
    private boolean memberChanged(Discovery old, Discovery now) {
        return !Objects.equals(old.getHost(), now.getHost())
                || old.getPort() != now.getPort()
                || !Objects.equals(old.getProtocol(), now.getProtocol());
    }

    /**
     * 按 serverId 查找本地服务表条目（get() 已按 serverId 去重保留最新）。
     *
     * @param path     服务路径
     * @param serverId 成员唯一标识
     * @return 命中条目，未找到返回 null
     */
    private Discovery findByServerId(String path, String serverId) {
        for (Discovery d : getServiceAll(path)) {
            if (d != null && serverId.equals(d.getServerId())) {
                return d;
            }
        }
        return null;
    }

    /**
     * 本节点是否为网关/seed 自身（seed 模式且未配置 seed 地址）。
     * 网关自身条目需保留 {@code seed=true} 元数据；worker 节点必须剥离，
     * 否则会被全网排除出负载均衡。仅 {@link SeedModeDiscovery} 覆写。
     */
    protected boolean isGatewayNode() {
        return false;
    }

    // ==================== 注销事件 ====================

    @Override
    public ServiceDiscovery unregisterService(String path, Discovery discovery) {
        return unregisterService(path, discovery == null ? null : discovery.getServerId());
    }

    @Override
    public ServiceDiscovery unregisterService(String path, String serverId) {
        if (serverId == null) {
            return this;
        }
        Discovery removed = findByServerId(path, serverId);
        super.unregisterService(path, serverId);
        if (removed != null) {
            nodesRemoved.incrementAndGet();
            lastSeenAtMillis.remove(serverId);
            fireEvent(removed, Event.REMOVE);
        }
        return this;
    }

    // ==================== 运行观测与优雅离网 ====================

    /**
     * 汇总运行观测快照：成员表 + 发现/同步/心跳/帧处理计数器（自节点视角）。
     */
    @Override
    public ScatterStats stats() {
        List<ScatterMemberInfo> members = Wraps.list();
        for (Discovery d : getServiceAll(setting.getServicePath())) {
            if (d == null || d.getServerId() == null) {
                continue;
            }
            members.add(ScatterMemberInfo.builder()
                    .serverId(d.getServerId())
                    .host(d.getHost())
                    .port(d.getPort())
                    .protocol(d.getProtocol())
                    .weight(d.getWeight())
                    .seed(isSeedNode(d))
                    .self(setting.getNodeId().equals(d.getServerId()))
                    .heartbeatFailures(heartbeatFailCounts.getOrDefault(d.getServerId(), 0))
                    .lastSeenAtMillis(lastSeenAtMillis.getOrDefault(d.getServerId(), 0L))
                    .build());
        }
        return ScatterStats.builder()
                .nodeId(setting.getNodeId())
                .groupId(getGroupId())
                .protocol(setting.getProtocol())
                .mode(resolveMode())
                .memberCount(members.size())
                .members(members)
                .discoveryRounds(discoveryRounds.get())
                .roundFailures(roundFailures.get())
                .syncSuccesses(syncSuccesses.get())
                .syncFailures(syncFailures.get())
                .heartbeatsSent(heartbeatsSent.get())
                .heartbeatFailures(heartbeatFailures.get())
                .nodesAdded(nodesAdded.get())
                .nodesUpdated(nodesUpdated.get())
                .nodesRemoved(nodesRemoved.get())
                .reqFramesReceived(reqFramesReceived.get())
                .pushFramesReceived(pushFramesReceived.get())
                .elecFramesReceived(elecFramesReceived.get())
                .discFramesReceived(discFramesReceived.get())
                .leaveFramesReceived(leaveFramesReceived.get())
                .unknownFrames(unknownFrames.get())
                .lastRoundAtMillis(lastRoundAtMillis.get())
                .build();
    }

    /**
     * 解析发现模式标识：seed / dns / auto-udp / auto-tcp。
     */
    private String resolveMode() {
        if (this instanceof SeedModeDiscovery) {
            return "seed";
        }
        if (this instanceof DnsScatterDiscovery) {
            return "dns";
        }
        if (this instanceof UdpBroadcastDiscovery) {
            return "auto-udp";
        }
        return "auto-tcp";
    }

    /**
     * 优雅离网：向表内其他可路由节点广播 LEAVE 帧（尽力而为），
     * 对端收到后立即剔除本节点并触发 REMOVE 事件，无需等待心跳超限；
     * 最后从本地表移除自身并触发 REMOVE 事件。
     */
    @Override
    public void leave() {
        Discovery self = findByServerId(setting.getServicePath(), setting.getNodeId());
        if (self == null) {
            self = buildSelfDiscovery(computeDynamicWeight());
        }
        try {
            byte[] payload = Json.toJson(self).getBytes(StandardCharsets.UTF_8);
            ScatterFrame frame = new ScatterFrame(ScatterProtocol.TYPE_LEAVE, genRequestId(),
                    setting.getServicePath(), payload);
            List<ScatterNode> targets = Wraps.list();
            for (Discovery d : getServiceAll(setting.getServicePath())) {
                if (d == null || d.getServerId() == null
                        || setting.getNodeId().equals(d.getServerId())) {
                    continue;
                }
                if (isUnroutable(d.getHost())) {
                    continue;
                }
                targets.add(new ScatterNode(d.getServerId(), d.getHost(), d.getPort(),
                        d.getProtocol(), getGroupId(), setting.getServicePath()));
            }
            ScatterSyncHelper.broadcast(targets, frame, setting.getTimeoutMillis());
            log.debug("LEAVE 优雅离网通知已发送至 {} 个节点", targets.size());
        } catch (Exception e) {
            log.debug("发送 LEAVE 通知失败: {}", e.getMessage());
        } finally {
            removeFromCache(setting.getServicePath(), setting.getNodeId());
            nodesRemoved.incrementAndGet();
            fireEvent(self, Event.REMOVE);
        }
    }
}