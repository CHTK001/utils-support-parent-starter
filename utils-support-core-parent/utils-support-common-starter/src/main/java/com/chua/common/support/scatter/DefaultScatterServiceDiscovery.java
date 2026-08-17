package com.chua.common.support.scatter;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.ThreadUtils;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 默认 Scatter 服务发现实现。
 * <p>对等式无中心化发现：seeds 引导 + 定时 gossip 扩散（向已知节点拉取服务列表合并），
 * 网段模式按网段扩散，心跳周期上报并携带 cpu+内存计算的动态权重。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultScatterServiceDiscovery extends AbstractServiceDiscovery implements ScatterServiceDiscovery {

    /**
     * 元数据键：seed 标记
     */
    private static final String METADATA_SEED = "seed";

    /**
     * 元数据值：true
     */
    private static final String METADATA_VALUE_TRUE = "true";

    /**
     * 元数据键：节点最后同步时间（持久化用，加载时过滤过期节点）
     */
    private static final String METADATA_LAST_SEEN = "lastSeen";

    /**
     * 默认权重
     */
    private static final double DEFAULT_WEIGHT = 1D;

    /**
     * 周期全量兜底间隔（轮数）：每 N 轮做一次全量探测，保证最终一致
     */
    private static final long FULL_PROBE_INTERVAL_ROUNDS = 10L;

    /**
     * 节点配置
     */
    private final ScatterSetting setting;

    /**
     * 网段探测轮次计数（首启全量，后续随机抽样，每 N 轮全量兜底）
     */
    private long probeRound;

    /**
     * 远程客户端
     */
    private ScatterRemoteClient<Discovery> remoteClient = (context, node, timeoutMillis) ->
            ScatterResult.failure(node.getNodeId(), "remote client not configured");

    /**
     * 自动发现执行器
     */
    private ScheduledExecutorService autoDiscoveryExecutor;

    /**
     * 执行器
     */
    private ExecutorService executorService;

    /**
     * 是否已启动
     */
    private boolean started;

    /**
     * 构造发现实现。
     *
     * @param setting 节点配置
     */
    public DefaultScatterServiceDiscovery(ScatterSetting setting) {
        super(new DiscoveryOption(), setting == null ? "default" : setting.effectiveGroupId());
        this.setting = setting == null ? new ScatterSetting() : setting;
    }

    /**
     * 设置远程客户端（用于向其他节点拉取服务列表）。
     *
     * @param remoteClient 远程客户端
     * @return 当前实例
     */
    @Override
    public ScatterServiceDiscovery remoteClient(ScatterRemoteClient<Discovery> remoteClient) {
        if (remoteClient != null) {
            this.remoteClient = remoteClient;
        }
        return this;
    }

    /**
     * 获取业务分组。
     *
     * @return 分组标识
     */
    @Override
    public String getGroupId() {
        return setting.effectiveGroupId();
    }

    /**
     * 获取节点配置。
     *
     * @return 配置
     */
    @Override
    public ScatterSetting getSetting() {
        return setting;
    }

    /**
     * 注册服务到本地缓存，并递增服务版本号。
     *
     * @param path      服务路径
     * @param discovery 服务发现数据
     * @return 当前实例
     */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        discovery.setUriSpec(prefixed);
        addToCache(prefixed, discovery);
        incrementServiceVersion();
        return this;
    }

    /**
     * 启动发现：注册自身、注册 seed、启动 gossip 自动发现与动态权重心跳。
     */
    @Override
    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        started = true;
        executorService = ThreadUtils.newCachedThreadPool("scatter-executor");

        // ① 加载持久化节点(后续启动无需重新检索,直接进 hash 表)
        loadPersistedNodes();

        // ② 注册自身（按 groupId 分组）
        registerSelf();

        // ③ 注册 seed 引导节点
        registerSeedNodes();

        // ③ 启动 gossip 自动发现（同步即心跳：动态权重随同步上报，不再单独发心跳）
        autoDiscoveryExecutor = ThreadUtils.newSingleThreadScheduledExecutor(
                ThreadUtils.newThreadFactory("scatter-auto-discovery"));
        autoDiscoveryExecutor.scheduleAtFixedRate(this::autoDiscovery,
                setting.getAutoDiscoveryIntervalMillis(),
                setting.getAutoDiscoveryIntervalMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Scatter 服务发现已启动: node={}, groupId={}, protocol={}",
                setting.getNodeId(), getGroupId(), setting.getProtocol());
    }

    /**
     * 注册本节点服务（按 groupId 分组，携带动态权重）。
     */
    private void registerSelf() {
        Discovery self = Discovery.builder()
                .id(setting.getNodeId())
                .serverId(setting.getNodeId())
                .scatterId(getGroupId())
                .protocol(setting.getProtocol())
                .host(setting.getHost())
                .port(setting.getPort())
                .timeout((int) setting.getTimeoutMillis())
                .weight(computeDynamicWeight())
                .metadata(Map.of(METADATA_SEED, METADATA_VALUE_TRUE))
                .build();
        registerService(setting.getServicePath(), self);
    }

    /**
     * 注册 seed 节点到本地缓存。
     */
    private void registerSeedNodes() {
        if (setting.getSeeds() == null || setting.getSeeds().isEmpty()) {
            return;
        }
        for (String address : setting.getSeeds()) {
            if (address == null || address.isBlank()) {
                continue;
            }
            SeedAddress seed = SeedAddress.parse(address);
            if (seed == null) {
                log.warn("seed 地址格式无效: {}", address);
                continue;
            }
            int port = seed.effectivePort(setting.getDefaultPort() > 0 ? setting.getDefaultPort() : setting.getPort());
            String nodeId = seed.nodeId(port);
            Discovery discovery = Discovery.builder()
                    .id(nodeId)
                    .serverId(nodeId)
                    .scatterId(getGroupId())
                    .protocol(setting.getProtocol())
                    .host(seed.getHost())
                    .port(port)
                    .timeout((int) setting.getTimeoutMillis())
                    .weight(DEFAULT_WEIGHT)
                    .metadata(Map.of(METADATA_SEED, METADATA_VALUE_TRUE))
                    .build();
            registerService(setting.getServicePath(), discovery);
            log.debug("注册 seed 节点: {}:{}", seed.getHost(), port);
        }
    }

    /**
     * gossip 自动发现：向已知节点（seed 或网段）拉取服务列表，合并进本地缓存。
     * <p>同步即心跳：每次同步前更新自身动态权重，远端拉取时即能拿到最新权重。</p>
     * <p>网段策略：首启全量探测一次（解决已开启节点没数据），后续随机抽样扩散
     * （seed 恒在抽样池），每 {@link #FULL_PROBE_INTERVAL_ROUNDS} 轮全量兜底一次保证最终一致。</p>
     */
    private void autoDiscovery() {
        try {
            // 同步即心跳：更新自身动态权重（远端通过 gossip 拉取到最新权重，替代独立心跳）
            if (setting.isDynamicWeight()) {
                updateService(setting.getServicePath(), Discovery.builder()
                        .id(setting.getNodeId())
                        .serverId(setting.getNodeId())
                        .scatterId(getGroupId())
                        .protocol(setting.getProtocol())
                        .host(setting.getHost())
                        .port(setting.getPort())
                        .timeout((int) setting.getTimeoutMillis())
                        .weight(computeDynamicWeight())
                        .metadata(Map.of())
                        .build());
            }
            List<ScatterNode> remoteNodes = new ArrayList<>(resolveSeedNodes());
            // 网段模式：首启全量 / 周期全量兜底 / 随机抽样扩散
            if (setting.getSubnet() != null && !setting.getSubnet().isBlank()) {
                List<ScatterNode> subnetNodes = resolveSubnetNodes();
                if (!subnetNodes.isEmpty()) {
                    boolean fullProbe = probeRound == 0 || probeRound % FULL_PROBE_INTERVAL_ROUNDS == 0;
                    if (fullProbe || subnetNodes.size() <= setting.getGossipTargetCount()) {
                        remoteNodes.addAll(subnetNodes);
                        log.debug("网段全量探测: {} 台主机 (round={})", subnetNodes.size(), probeRound);
                    } else {
                        // 随机抽样扩散：seed 恒在池，网段内随机取 gossipTargetCount 台
                        Collections.shuffle(subnetNodes);
                        remoteNodes.addAll(subnetNodes.subList(0, setting.getGossipTargetCount()));
                        log.debug("网段随机抽样 gossip: {} 台 (round={})", setting.getGossipTargetCount(), probeRound);
                    }
                }
                probeRound++;
            }
            if (remoteNodes.isEmpty()) {
                return;
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ScatterNode node : remoteNodes) {
                ScatterContext ctx = new ScatterContext(UUID.randomUUID().toString(),
                        setting.getServicePath(), setting.getTimeoutMillis(), 1, Map.of());
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        ScatterResult<Discovery> result = remoteClient.invoke(ctx, node, setting.getTimeoutMillis());
                        if (result != null && result.isSuccess() && result.getData() != null) {
                            Discovery remote = result.getData();
                            // 仅合并同分组节点
                            if (getGroupId().equals(remote.getScatterId())) {
                                addToCache(addClusterPrefix(setting.getServicePath()), remote);
                                incrementServiceVersion();
                                log.debug("gossip 发现: 从 {} 合并服务 {}:{}", node.getEndpoint(),
                                        remote.getHost(), remote.getPort());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("gossip 节点 {} 查询失败: {}", node.getEndpoint(), e.getMessage());
                    }
                }, executorService));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(setting.getTimeoutMillis(), TimeUnit.MILLISECONDS);
            // 同步完成:将有效节点定时持久化到本地文件,后续启动直接加载进 hash 表
            persistNodes();
        } catch (Exception e) {
            log.debug("自动发现异常: {}", e.getMessage());
        }
    }

    /**
     * 将本地缓存中的有效节点定时持久化到本地文件。
     * <p>为每个节点写入 lastSeen 时间戳，供下次启动过滤过期节点；后续启动直接加载进 hash 表，无需重新检索。</p>
     */
    private void persistNodes() {
        if (!setting.isPersistenceEnabled()) {
            return;
        }
        try {
            List<Discovery> nodes = new ArrayList<>(getServiceAll(setting.getServicePath()));
            if (nodes.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Discovery node : nodes) {
                Map<String, String> metadata = new java.util.HashMap<>(node.getMetadata() == null ? Map.of() : node.getMetadata());
                metadata.put(METADATA_LAST_SEEN, String.valueOf(now));
                node.setMetadata(metadata);
            }
            java.nio.file.Path path = java.nio.file.Paths.get(setting.getPersistenceFile());
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            java.nio.file.Files.write(path, Json.toJson(nodes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            log.debug("节点持久化完成: {} 个节点 -> {}", nodes.size(), setting.getPersistenceFile());
        } catch (Exception e) {
            log.warn("节点持久化失败: {}", e.getMessage());
        }
    }

    /**
     * 启动时加载持久化节点文件,直接写入本地 hash 表,避免重新检索。
     * <p>仅加载未过期的同分组节点：超过 {@link ScatterSetting#getPersistenceTtlMillis()} 未同步的节点丢弃。</p>
     */
    private void loadPersistedNodes() {
        if (!setting.isPersistenceEnabled()) {
            return;
        }
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(setting.getPersistenceFile());
            if (!java.nio.file.Files.exists(path)) {
                return;
            }
            String json = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) {
                return;
            }
            List<Discovery> nodes = Json.fromJsonToList(json, Discovery.class);
            long now = System.currentTimeMillis();
            long ttl = setting.getPersistenceTtlMillis() > 0 ? setting.getPersistenceTtlMillis() : Long.MAX_VALUE;
            int loaded = 0;
            int expired = 0;
            for (Discovery node : nodes) {
                if (node == null || node.getHost() == null) {
                    continue;
                }
                // 仅加载同分组节点
                if (!getGroupId().equals(node.getScatterId())) {
                    continue;
                }
                // 过滤过期节点（无 lastSeen 视为新鲜，兼容旧文件）
                String lastSeenStr = node.getMetadata() == null ? null : node.getMetadata().get(METADATA_LAST_SEEN);
                if (lastSeenStr != null) {
                    try {
                        long lastSeen = Long.parseLong(lastSeenStr);
                        if (now - lastSeen > ttl) {
                            expired++;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // 时间戳格式异常视为新鲜
                    }
                }
                addToCache(addClusterPrefix(setting.getServicePath()), node);
                loaded++;
            }
            if (loaded > 0) {
                incrementServiceVersion();
                log.info("加载持久化节点: {} 个(过期丢弃 {}), 文件 {}", loaded, expired, setting.getPersistenceFile());
            } else if (expired > 0) {
                log.info("持久化节点全部过期({} 个), 重新检索", expired);
            }
        } catch (Exception e) {
            log.warn("加载持久化节点失败: {}", e.getMessage());
        }
    }

    /**
     * 从 seed 地址列表解析节点。
     *
     * @return 节点列表
     */
    private List<ScatterNode> resolveSeedNodes() {
        if (setting.getSeeds() == null || setting.getSeeds().isEmpty()) {
            return List.of();
        }
        return setting.getSeeds().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseSeedAddress)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 解析单个 seed 地址。
     *
     * @param address 地址
     * @return 节点
     */
    private ScatterNode parseSeedAddress(String address) {
        SeedAddress seed = SeedAddress.parse(address);
        if (seed == null) {
            return null;
        }
        int port = seed.effectivePort(setting.getDefaultPort() > 0 ? setting.getDefaultPort() : setting.getPort());
        return new ScatterNode(seed.nodeId(port), seed.getHost(), port, setting.getProtocol(),
                getGroupId(), setting.getServicePath(), Map.of());
    }

    /**
     * 解析网段模式节点：按 CIDR（如 192.168.1.0/24）展开网段内全部主机，
     * 固定相同端口（默认端口）参与扩散。
     *
     * @return 网段节点列表
     */
    private List<ScatterNode> resolveSubnetNodes() {
        String subnet = setting.getSubnet();
        if (subnet == null || subnet.isBlank()) {
            return List.of();
        }
        String[] parts = subnet.trim().split("/");
        if (parts.length != 2) {
            log.warn("网段格式无效: {}（期望如 192.168.1.0/24）", subnet);
            return List.of();
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            log.warn("网段前缀无效: {}", subnet);
            return List.of();
        }
        if (prefix < 0 || prefix > 32) {
            log.warn("网段前缀越界: {}", subnet);
            return List.of();
        }
        try {
            byte[] base = java.net.InetAddress.getByName(parts[0].trim()).getAddress();
            int hostBits = 32 - prefix;
            long baseIp = toLong(base) & (prefix == 0 ? 0L : (~0L << hostBits));
            long hostCount = 1L << hostBits;
            int port = setting.getDefaultPort() > 0 ? setting.getDefaultPort() : setting.getPort();
            List<ScatterNode> nodes = new ArrayList<>();
            // 跳过网络地址与广播地址（/31、/32 除外）
            for (long i = 1; i < hostCount - 1 && i < 512; i++) {
                long ip = baseIp | i;
                String host = toIp(ip);
                nodes.add(new ScatterNode(host + ":" + port, host, port, setting.getProtocol(),
                        getGroupId(), setting.getServicePath(), Map.of()));
            }
            log.info("网段模式扩散: {} -> {} 台主机，端口 {}", subnet, nodes.size(), port);
            return nodes;
        } catch (Exception e) {
            log.warn("网段解析失败: {} - {}", subnet, e.getMessage());
            return List.of();
        }
    }

    /**
     * IPv4 字节数组转 long。
     *
     * @param bytes 地址字节
     * @return long 值
     */
    private static long toLong(byte[] bytes) {
        long v = 0;
        for (byte b : bytes) {
            v = (v << 8) | (b & 0xFF);
        }
        return v;
    }

    /**
     * long 转 IPv4 点分字符串。
     *
     * @param ip long 值
     * @return 点分地址
     */
    private static String toIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "."
                + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /**
     * 计算动态权重：负载越高权重越低（cpu + 内存）。
     * 取 {@code 1 / (1 + cpuLoad + memoryUsage)}，空闲节点权重趋近 1。
     *
     * @return 权重
     */
    private double computeDynamicWeight() {
        if (!setting.isDynamicWeight()) {
            return DEFAULT_WEIGHT;
        }
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            double cpuLoad = invokeMethod(osBean, "getSystemCpuLoad", 0D);
            double totalMem = invokeMethod(osBean, "getTotalPhysicalMemorySize", 0D);
            double freeMem = invokeMethod(osBean, "getFreePhysicalMemorySize", 0D);
            double memUsage = totalMem > 0 ? (totalMem - freeMem) / totalMem : 0D;
            double load = Math.max(0D, Math.min(1D, cpuLoad)) + Math.max(0D, Math.min(1D, memUsage));
            return 1D / (1D + load);
        } catch (Exception e) {
            return DEFAULT_WEIGHT;
        }
    }

    /**
     * 反射调用 MXBean 方法，避免直接依赖 com.sun.management（兼容不同 JDK）。
     *
     * @param bean    MXBean
     * @param method  方法名
     * @param fallback 失败回退值
     * @return 结果
     */
    private static double invokeMethod(Object bean, String method, double fallback) {
        try {
            Method m = bean.getClass().getMethod(method);
            Object result = m.invoke(bean);
            return result instanceof Number n ? n.doubleValue() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 关闭服务发现，委托 stop。
     *
     * @throws Exception 关闭异常
     */
    @Override
    public void close() throws Exception {
        stop();
    }

    /**
     * 停止发现：关闭自动发现与执行器，按配置清理缓存。
     */
    public synchronized void stop() throws Exception {
        if (!started) {
            return;
        }
        started = false;
        if (autoDiscoveryExecutor != null) {
            autoDiscoveryExecutor.shutdownNow();
            autoDiscoveryExecutor = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        if (setting.isCleanupOnClose()) {
            clearCache();
        }
        log.info("Scatter 服务发现已停止");
    }
}
