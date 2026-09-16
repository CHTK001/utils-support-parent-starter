package com.chua.common.support.scatter.discovery;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.ScatterContext;
import com.chua.common.support.scatter.ScatterNode;
import com.chua.common.support.scatter.ScatterResult;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.ScatterSyncHelper;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import com.chua.common.support.lang.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * seed 引导模式发现：仅与 seed 同步 hash + 新节点扩散 + 最小 nodeId 选举 + 全掉线降级。
 *
 * <p>机制：</p>
 * <ul>
 *   <li><b>同步</b>：每轮向所有 seed 拉取服务表合并（与 seed 同步 hash）；</li>
 *   <li><b>扩散</b>：节点上线后向所有 seed 推送自身 hash（PUSH），seed 收到后向已知老节点扩散一次；</li>
 *   <li><b>去重</b>：对端按 serverId 幂等合并（基类 get() 已按 serverId 去重）；</li>
 *   <li><b>选举</b>：seed 连续失败达阈值即标记掉线，存活节点按最小 nodeId 自动选举新引导；</li>
 *   <li><b>降级</b>：seed 全掉线时，节点间用已发现的老节点互相同步（降级 gossip），seed 恢复后重新纳入。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SeedModeDiscovery extends AbstractScatterDiscovery {

    /** seed 元数据键 */
    private static final String METADATA_SEED = "seed";
    /** seed 掉线标记 */
    private static final String METADATA_SEED_DOWN = "seedDown";

    /** 已扩散过的新节点（去重） */
    private final java.util.Set<String> announcedSeeds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 降级同步专用线程池（固定大小，与 RouteModeDiscovery 隔离，不占用 commonPool） */
    private static final ExecutorService DEGRADE_SYNC_EXECUTOR = Executors.newFixedThreadPool(
            4, r -> {
                Thread t = new Thread(r, "scatter-degrade-sync");
                t.setDaemon(true);
                return t;
            });

    public SeedModeDiscovery(ScatterSetting setting) {
        super(setting);
    }

    @Override
    protected void doDiscoveryRound() {
        List<ScatterNode> seeds = resolveSeeds();
        if (seeds.isEmpty()) {
            // seed 全掉线/未配置：降级为与已发现老节点互相同步
            degradeSync();
            return;
        }
        int aliveSeeds = 0;
        for (ScatterNode seed : seeds) {
            boolean ok = syncWithSeed(seed);
            if (ok) {
                aliveSeeds++;
                announcedSeeds.add(seed.getNodeId());
                // 扩散：向 seed 推送自身 hash
                pushSelf(seed);
            } else {
                announcedSeeds.remove(seed.getNodeId());
            }
        }
        if (aliveSeeds == 0) {
            // 所有 seed 掉线：降级 + 标记
            markSeedDown();
            degradeSync();
        }
    }

    /** 与单个 seed 同步：拉取完整服务表合并（hash 同步）。 */
    private boolean syncWithSeed(ScatterNode seed) {
        ScatterContext ctx = new ScatterContext(genRequestId() + "",
                setting.getServicePath(), setting.getTimeoutMillis());
        ScatterResult<List<Discovery>> result = ScatterSyncHelper.fetch(ctx, seed, setting.getTimeoutMillis());
        if (result != null && result.isSuccess() && result.getData() != null) {
            mergeRemote(result.getData());
            return true;
        }
        return false;
    }

    /** 向 seed 推送自身 hash（新节点接入下发一次）。 */
    private void pushSelf(ScatterNode seed) {
        try {
            Discovery self = getServiceAll(setting.getServicePath()).stream()
                    .filter(d -> setting.getNodeId().equals(d.getServerId()))
                    .findFirst().orElse(null);
            if (self == null) {
                return;
            }
            byte[] payload = Json.toJson(self).getBytes(StandardCharsets.UTF_8);
            ScatterFrame push = new ScatterFrame(ScatterProtocol.TYPE_PUSH,
                    genRequestId(),
                    setting.getServicePath(), payload);
            boolean ack = ScatterSyncHelper.push(seed, push, setting.getTimeoutMillis());
            if (ack) {
                log.debug("已向 seed 推送自身: {}", seed.getNodeId());
            }
        } catch (Exception e) {
            log.debug("推送 seed 失败: {} - {}", seed.getNodeId(), e.getMessage());
        }
    }

    /** seed 全掉线：标记本地 seed 引导条目降级（不依赖 announcedSeeds——其会在同步失败时被清空）。 */
    private void markSeedDown() {
        for (Discovery d : getServiceAll(setting.getServicePath())) {
            if (d.getServerId() == null) {
                continue;
            }
            // 仅标记 seed 引导条目（metadata.seed=true）
            if (d.getMetadata() != null && "true".equals(d.getMetadata().get(METADATA_SEED))) {
                java.util.Map<String, String> meta = new java.util.HashMap<>(d.getMetadata());
                meta.put(METADATA_SEED_DOWN, "true");
                Discovery copy = Discovery.builder()
                        .id(d.getId()).serverId(d.getServerId()).scatterId(d.getScatterId())
                        .protocol(d.getProtocol()).host(d.getHost()).port(d.getPort())
                        .timeout(d.getTimeout()).weight(d.getWeight()).uriSpec(d.getUriSpec())
                        .metadata(meta).build();
                updateService(setting.getServicePath(), copy);
            }
        }
    }

    /** 降级：与已发现的老节点（非 seed）互相同步，保证 seed 掉线时集群仍可收敛。 */
    private void degradeSync() {
        Set<Discovery> services = getServiceAll(setting.getServicePath());
        List<Discovery> candidates = new ArrayList<>();
        for (Discovery d : services) {
            if (d.getServerId() == null || setting.getNodeId().equals(d.getServerId())) {
                continue;
            }
            if (isSeedNode(d)) {
                continue;
            }
            if (isUnroutable(d.getHost())) {
                continue;
            }
            candidates.add(d);
        }
        // 按 nodeId 排序（最小 nodeId 优先），取 gossipTargetCount 台
        candidates.sort(Comparator.comparing(Discovery::getServerId, Comparator.nullsLast(String::compareTo)));
        int limit = Math.min(setting.getGossipTargetCount(), candidates.size());
        // 并发同步，避免单节点阻塞导致其他候选节点同步延迟
        CompletableFuture<?>[] futures = new CompletableFuture[limit];
        for (int i = 0; i < limit; i++) {
            Discovery d = candidates.get(i);
            ScatterNode node = new ScatterNode(d.getServerId(), d.getHost(), d.getPort(),
                    d.getProtocol(), getGroupId(), setting.getServicePath());
            futures[i] = CompletableFuture.runAsync(() -> syncWith(node), DEGRADE_SYNC_EXECUTOR);
        }
        CompletableFuture.allOf(futures).join();
        // 降级选举：若 seed 全掉线，选举最小 nodeId 的老节点为新引导并广播
        electNewSeed(candidates);
    }

    /** 选举：seed 全掉线时，最小 nodeId 的节点成为新引导并广播 ELEC（自己参与比较，非仅远端）。 */
    private void electNewSeed(List<Discovery> candidates) {
        if (candidates.isEmpty()) {
            // 无其他存活节点：自己是唯一节点，无需广播
            return;
        }
        Discovery minRemote = candidates.getFirst(); // 已按 nodeId 排序（最小优先）
        String selfId = setting.getNodeId();
        // 自己 vs 远端最小 nodeId：自己更小则自己成为新引导并广播
        if (selfId.compareTo(minRemote.getServerId()) < 0) {
            Discovery self = getServiceAll(setting.getServicePath()).stream()
                    .filter(d -> selfId.equals(d.getServerId()))
                    .findFirst().orElse(null);
            if (self != null) {
                broadcastElection(self);
            }
        }
    }

    /** 向其他节点广播选举通知（ELEC 帧，携带新引导信息）。 */
    private void broadcastElection(Discovery elected) {
        byte[] payload = Json.toJson(elected).getBytes(StandardCharsets.UTF_8);
        ScatterFrame elec = new ScatterFrame(ScatterProtocol.TYPE_ELEC,
                genRequestId(),
                setting.getServicePath(), payload);
        List<ScatterNode> targets = new ArrayList<>();
        Set<Discovery> services = getServiceAll(setting.getServicePath());
        for (Discovery d : services) {
            if (d.getServerId() == null || setting.getNodeId().equals(d.getServerId())) {
                continue;
            }
            if (isUnroutable(d.getHost())) {
                continue;
            }
            targets.add(new ScatterNode(d.getServerId(), d.getHost(), d.getPort(),
                    d.getProtocol(), getGroupId(), setting.getServicePath()));
        }
        ScatterSyncHelper.broadcast(targets, elec, setting.getTimeoutMillis());
        log.info("seed 全掉线，选举新引导节点: {}", elected.getServerId());
    }

    /** 与普通节点同步（复用路由模式的 syncWith）。 */
    private void syncWith(ScatterNode node) {
        ScatterContext ctx = new ScatterContext(genRequestId() + "",
                setting.getServicePath(), setting.getTimeoutMillis());
        ScatterResult<List<Discovery>> result = ScatterSyncHelper.fetch(ctx, node, setting.getTimeoutMillis());
        if (result != null && result.isSuccess() && result.getData() != null) {
            mergeRemote(result.getData());
        }
    }

    /** 注册 seed 引导条目（带 seed 标记，不参与心跳剔除）。 */
    @Override
    public void registerSelf() {
        super.registerSelf();
        List<ScatterNode> seeds = resolveSeeds();
        // 未配置 seed：自动将本节点注册为自身 seed（网关模式）
        // 使用 port+2 作为 scatter 通信端口，确保其他节点可通过该端口连接
        if (seeds.isEmpty()) {
            String selfNodeId = setting.getNodeId();
            // 使用 scatterPort（已由 DefaultScatter 填充为 nodeServer 实际端口）
            // 若未设置则回退到 port+2
            int scatterPort = setting.getScatterPort() > 0
                    ? setting.getScatterPort()
                    : (setting.getPort() > 0 ? setting.getPort() + 2 : setting.getPort());
            String selfAddr = setting.effectiveHost() + ":" + scatterPort;
            Discovery selfSeed = Discovery.builder()
                    .id(selfNodeId)
                    .serverId(selfNodeId)
                    .scatterId(getGroupId())
                    .protocol(setting.getProtocol())
                    .host(setting.effectiveHost())
                    .port(scatterPort)
                    .timeout((int) setting.getTimeoutMillis())
                    .weight(1.0)
                    .uriSpec(setting.getServicePath())
                    .metadata(java.util.Map.of(METADATA_SEED, "true"))
                    .build();
            updateService(setting.getServicePath(), selfSeed);
            log.info("未配置 seed，本节点 {} 自动成为网关，scatter 端口={}", selfNodeId, scatterPort);
            return;
        }
        for (ScatterNode seed : seeds) {
            // serverId 使用 nodeId 而非 seed 地址，与 AbstractScatterDiscovery.registerSelf 保持一致
            // 避免 seed 条目以 "host:port" 为 serverId 导致移除逻辑失效
            Discovery node = Discovery.builder()
                    .id(seed.getNodeId())
                    .serverId(setting.getNodeId())
                    .scatterId(getGroupId())
                    .protocol(setting.getProtocol())
                    .host(seed.getHost())
                    .port(seed.getPort())
                    .timeout((int) setting.getTimeoutMillis())
                    .weight(1.0)
                    .uriSpec(setting.getServicePath())
                    .metadata(java.util.Map.of(METADATA_SEED, "true"))
                    .build();
            updateService(setting.getServicePath(), node);
        }
    }
}
