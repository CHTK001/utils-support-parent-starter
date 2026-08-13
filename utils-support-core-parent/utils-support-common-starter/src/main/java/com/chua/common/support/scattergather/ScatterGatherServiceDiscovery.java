package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.Event;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.DigestUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 基于 Scatter-Gather 的服务发现实现。
 * <p>将服务发现查询散射到多个节点，聚合结果后返回。</p>
 *
 * @author CH
 */
@Slf4j
public class ScatterGatherServiceDiscovery extends AbstractServiceDiscovery {

    /**
     * 自动检索执行器
     */
    private ScheduledExecutorService autoDiscoveryExecutor;

    /**
     * 是否已自动检索
     */
    private boolean autoDiscoveryStarted;

    /**
     * 订阅执行器列表（用于资源清理）
     */
    private final List<ScheduledExecutorService> subscribeExecutors = new ArrayList<>();

    /**
     * 轮询索引（round-robin 策略使用）
     */
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    /**
     * 本地查询处理器
     */
    private ScatterGatherQueryHandler queryHandler = context -> null;

    /**
     * 数据解析器
     */
    private ScatterGatherDataParser<Discovery> dataParser = ScatterGatherServiceDiscovery::defaultParse;

    /**
     * 数据标准化器
     */
    private ScatterGatherDataNormalizer<Discovery, Discovery> dataNormalizer = ScatterGatherServiceDiscovery::defaultNormalize;

    /**
     * 聚合器
     */
    private ScatterGatherAggregator<Discovery> aggregator = new DefaultScatterGatherAggregator<>();

    /**
     * 远程客户端
     */
    private ScatterGatherRemoteClient<Discovery> remoteClient = (context, node, timeoutMillis) ->
            ScatterGatherResult.failure(node.getNodeId(), "remote client not configured");

    /**
     * 监听器
     */
    private ScatterGatherNodeListener<Discovery> listener = new ScatterGatherNodeListener<>() {
    };

    /**
     * 线程池
     */
    private ExecutorService executorService;

    /**
     * 订阅定时任务线程池
     */
    private java.util.concurrent.ScheduledExecutorService scheduledExecutor;

    /**
     * 去重器
     */
    private ScatterGatherDeduplicator deduplicator;

    /**
     * 节点配置
     */
    private final ScatterGatherSetting setting = new ScatterGatherSetting();

    /**
     * 是否已启动
     */
    private boolean started;

    /**
     * 发现模式
     */
    private ScatterGatherMode mode;

    /**
     * 默认构造。
     */
    public ScatterGatherServiceDiscovery() {
        super(new com.chua.common.support.network.discovery.DiscoveryOption());
    }

    /**
     * 带配置构造。
     *
     * @param discoveryOption 发现选项
     */
    public ScatterGatherServiceDiscovery(com.chua.common.support.network.discovery.DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    /**
     * 带配置和集群构造。
     *
     * @param discoveryOption 发现选项
     * @param clusterName     集群名
     */
    public ScatterGatherServiceDiscovery(com.chua.common.support.network.discovery.DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    /**
     * 带 ScatterGatherSetting 构造，自动配置远程客户端。
     *
     * @param discoveryOption 发现选项
     * @param setting         节点配置
     */
    @SuppressWarnings("unchecked")
    public ScatterGatherServiceDiscovery(com.chua.common.support.network.discovery.DiscoveryOption discoveryOption, ScatterGatherSetting setting) {
        super(discoveryOption);
        applySetting(setting);
        this.remoteClient = (ScatterGatherRemoteClient<Discovery>)
                (ScatterGatherRemoteClient<?>) new ScatterGatherBuilder<>(this.setting).buildClient();
    }

    /**
     * 应用 ScatterGatherSetting 配置到当前设置。
     * <p>ScatterGatherSetting 使用 Lombok 链式 setter（返回 this），BeanUtils 无法识别，故手动复制。</p>
     *
     * @param setting 节点配置
     */
    private void applySetting(ScatterGatherSetting setting) {
        if (setting == null) {
            return;
        }
        this.setting.setNodeId(setting.getNodeId());
        this.setting.setHost(setting.getHost());
        this.setting.setTcpPort(setting.getTcpPort());
        this.setting.setHttpPort(setting.getHttpPort());
        this.setting.setServicePath(setting.getServicePath());
        this.setting.setApiPath(setting.getApiPath());
        this.setting.setHttpApiPaths(setting.getHttpApiPaths());
        this.setting.setTimeoutMillis(setting.getTimeoutMillis());
        this.setting.setRemoteTimeoutMillis(setting.getRemoteTimeoutMillis());
        this.setting.setBalance(setting.getBalance());
        this.setting.setTransportProtocol(setting.getTransportProtocol());
        this.setting.setTcpMode(setting.getTcpMode());
        this.setting.setBootstrapNode(setting.getBootstrapNode());
        this.setting.setSeedAddresses(setting.getSeedAddresses() == null ? null : new ArrayList<>(setting.getSeedAddresses()));
        this.setting.setDefaultPort(setting.getDefaultPort());
        this.setting.setAutoDiscoveryIntervalMillis(setting.getAutoDiscoveryIntervalMillis());
        this.setting.setCleanupOnClose(setting.isCleanupOnClose());
        this.setting.setMaxRetries(setting.getMaxRetries());
        this.setting.setRetryDelayMillis(setting.getRetryDelayMillis());
        this.setting.setRetryEnabled(setting.isRetryEnabled());
        this.setting.setEnableFallback(setting.isEnableFallback());
        this.setting.setFallbackResult(setting.getFallbackResult());
        this.setting.setFailureThreshold(setting.getFailureThreshold());
        this.setting.setRecoveryThreshold(setting.getRecoveryThreshold());
        this.setting.setRemoteConcurrency(setting.getRemoteConcurrency());
        this.setting.setDeduplicationEnabled(setting.isDeduplicationEnabled());
        this.setting.setDeduplicationTtlMillis(setting.getDeduplicationTtlMillis());
        this.setting.setHeartbeatEnabled(setting.isHeartbeatEnabled());
        this.setting.setHeartbeatIntervalMillis(setting.getHeartbeatIntervalMillis());
        this.setting.setHttpApiEnabled(setting.isHttpApiEnabled());
        this.setting.setUdpBroadcast(setting.isUdpBroadcast());
        this.setting.setUdpBroadcastAddress(setting.getUdpBroadcastAddress());
        this.setting.setUdpFallbackToTcp(setting.isUdpFallbackToTcp());
    }

    // ======================== 配置链式 API ========================

    /**
     * 设置节点ID。
     *
     * @param nodeId 节点ID
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery nodeId(String nodeId) {
        setting.setNodeId(nodeId);
        return this;
    }

    /**
     * 设置主机地址。
     *
     * @param host 主机
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery host(String host) {
        setting.setHost(host);
        return this;
    }

    /**
     * 设置TCP端口。
     *
     * @param tcpPort 端口
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery tcpPort(int tcpPort) {
        setting.setTcpPort(tcpPort);
        return this;
    }

    /**
     * 设置HTTP端口。
     *
     * @param httpPort 端口
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery httpPort(int httpPort) {
        setting.setHttpPort(httpPort);
        return this;
    }

    /**
     * 设置服务路径。
     *
     * @param servicePath 路径
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery servicePath(String servicePath) {
        setting.setServicePath(servicePath);
        return this;
    }

    /**
     * 设置API路径。
     *
     * @param apiPath 路径
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery apiPath(String apiPath) {
        setting.setApiPath(apiPath);
        setting.setHttpApiPaths(apiPath);
        return this;
    }

    /**
     * 设置超时时间。
     *
     * @param timeoutMillis 超时
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery timeoutMillis(long timeoutMillis) {
        setting.setTimeoutMillis(timeoutMillis);
        return this;
    }

    /**
     * 设置负载均衡策略。
     *
     * @param balance 策略
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery balance(String balance) {
        setting.setBalance(balance);
        return this;
    }

    /**
     * 设置查询处理器。
     *
     * @param queryHandler 处理器
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery queryHandler(ScatterGatherQueryHandler queryHandler) {
        this.queryHandler = queryHandler;
        return this;
    }

    /**
     * 设置远程客户端。
     *
     * @param remoteClient 客户端
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery remoteClient(ScatterGatherRemoteClient<Discovery> remoteClient) {
        this.remoteClient = remoteClient;
        return this;
    }

    /**
     * 设置监听器。
     *
     * @param listener 监听器
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery listener(ScatterGatherNodeListener<Discovery> listener) {
        this.listener = listener;
        return this;
    }

    /**
     * 启用HTTP API。
     *
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery enableHttpApi() {
        setting.setHttpApiEnabled(true);
        return this;
    }

    /**
     * 启用去重。
     *
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery enableDeduplication() {
        setting.setDeduplicationEnabled(true);
        return this;
    }

    /**
     * 启用降级。
     *
     * @param fallbackResult 降级结果
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery enableFallback(Object fallbackResult) {
        setting.setEnableFallback(true);
        setting.setFallbackResult(fallbackResult);
        return this;
    }

    /**
     * 设置最大重试次数。
     *
     * @param maxRetries 次数
     * @return 当前实例
     */
    public ScatterGatherServiceDiscovery maxRetries(int maxRetries) {
        setting.setMaxRetries(maxRetries);
        return this;
    }

    // ======================== 生命周期 ========================

    @Override
    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        validate();
        executorService = ThreadUtils.newCachedThreadPool("scatter-gather-executor");
        if (setting.isDeduplicationEnabled()) {
            deduplicator = new ScatterGatherDeduplicator(setting.getDeduplicationTtlMillis());
        }
        // 按 tcpMode 通过模式 SPI 创建并启动发现策略
        this.mode = ServiceProvider.of(ScatterGatherMode.class).getNewExtension(resolveModeKey(), setting);
        if (mode == null) {
            throw new IllegalStateException("未找到对应的 ScatterGatherMode 实现: " + resolveModeKey());
        }
        mode.start(this);
        started = true;
        listener.onStart(setting);
        log.info("ScatterGatherServiceDiscovery 已启动，节点ID: {}，模式: {}", setting.getNodeId(), setting.getTcpMode());
    }

    /**
     * 解析发现模式键，为空时使用默认 seed。
     *
     * @return 模式键
     */
    private String resolveModeKey() {
        if (StringUtils.isBlank(setting.getTcpMode())) {
            return "seed";
        }
        return setting.getTcpMode();
    }

    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        discovery.setUriSpec(prefixed);
        addToCache(prefixed, discovery);
        incrementServiceVersion();
        return this;
    }

    @Override
    public ServiceDiscovery unregisterService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        doUnregister(prefixed, discovery);
        removeFromCache(prefixed, discovery.getServerId());
        incrementServiceVersion();
        return this;
    }

    @Override
    public ServiceDiscovery unregisterService(String path, String serverId) {
        String prefixed = addClusterPrefix(path);
        doUnregister(prefixed, Discovery.builder().serverId(serverId).build());
        removeFromCache(prefixed, serverId);
        incrementServiceVersion();
        return this;
    }

    @Override
    public ServiceDiscovery updateService(String path, Discovery discovery) {
        String prefixed = addClusterPrefix(path);
        String normalizedKey = StringUtils.startWithAppend(prefixed, "/");
        localCache.computeIfPresent(normalizedKey, (k, list) -> {
            for (int i = 0; i < list.size(); i++) {
                if (discovery.getServerId() != null && discovery.getServerId().equals(list.get(i).getServerId())) {
                    Discovery old = list.get(i);
                    list.set(i, discovery);
                    doUpdate(prefixed, old, discovery);
                    return list;
                }
            }
            list.add(discovery);
            addToCache(prefixed, discovery);
            return list;
        });
        incrementServiceVersion();
        return this;
    }

    @Override
    public Discovery getService(String path, String balance, String protocol) {
        String prefixedPath = addClusterPrefix(path);
        String normalizedPath = StringUtils.startWithAppend(prefixedPath, "/");

        // 优先从本地缓存获取
        Set<Discovery> services = getPath(normalizedPath);
        if (!services.isEmpty()) {
            return selectService(new ArrayList<>(services), balance, protocol);
        }

        // 本地无结果时，尝试远程查询
        return remoteQuery(normalizedPath, balance, protocol);
    }

    @Override
    public Set<Discovery> getServiceAll(String path) {
        return getPath(addClusterPrefix(path));
    }

    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, com.chua.common.support.network.discovery.ServiceDiscoveryListener listener) {
        ScheduledExecutorService scheduler = ThreadUtils.newSingleThreadScheduledExecutor(
                ThreadUtils.newThreadFactory("scatter-gather-subscribe"));
        subscribeExecutors.add(scheduler);
        scheduler.scheduleAtFixedRate(() -> {
            String path = StringUtils.startWithAppend(serviceName, "/");
            Set<Discovery> services = getPath(path);
            if (services != null) {
                for (Discovery d : services) {
                    listener.listen(serviceName, d, Event.ADD);
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        stop();
    }

    // ======================== Seed 节点注册 ========================

    /**
     * 注册 seed 节点到本地缓存。
     */
    void registerSeedNodes() {
        if (setting.getSeedAddresses() == null) {
            return;
        }
        for (String address : setting.getSeedAddresses()) {
            if (StringUtils.isBlank(address)) {
                continue;
            }
            SeedAddress seed = SeedAddress.parse(address);
            if (seed == null) {
                log.warn("seed 地址格式无效: {}", address);
                continue;
            }
            int port = seed.effectivePort(setting.getDefaultPort());
            String nodeId = seed.nodeId(setting.getDefaultPort());
            Discovery discovery = Discovery.builder()
                    .id(nodeId)
                    .serverId(nodeId)
                    .protocol("tcp")
                    .host(seed.getHost())
                    .port(port)
                    .timeout((int) setting.getTimeoutMillis())
                    .weight(1D)
                    .metadata(Map.of("seed", "true", "servicePath", setting.getServicePath()))
                    .build();
            registerService(setting.getServicePath(), discovery);
            log.debug("注册 seed 节点: {}:{}", seed.getHost(), port);
        }
    }

    /**
     * 启动自动检索定时任务。
     */
    void startAutoDiscovery() {
        if (autoDiscoveryStarted) {
            return;
        }
        autoDiscoveryStarted = true;
        autoDiscoveryExecutor = ThreadUtils.newSingleThreadScheduledExecutor(
                ThreadUtils.newThreadFactory("scatter-gather-auto-discovery"));
        autoDiscoveryExecutor.scheduleAtFixedRate(this::autoDiscovery,
                setting.getAutoDiscoveryIntervalMillis(),
                setting.getAutoDiscoveryIntervalMillis(),
                TimeUnit.MILLISECONDS);
        log.info("自动检索已启动，间隔: {}ms", setting.getAutoDiscoveryIntervalMillis());
    }

    /**
     * 解析本地缓存中的远程节点，排除 seed 与 bootstrap 引导节点。
     *
     * @return 远程节点列表
     */
    List<ScatterGatherNode> resolveCachedRemoteNodes() {
        Set<Discovery> local = getPath(setting.getServicePath());
        List<ScatterGatherNode> nodes = new ArrayList<>();
        if (local != null && !local.isEmpty()) {
            for (Discovery d : local) {
                if ("seed".equals(d.getMetadata().get("seed")) || "bootstrap".equals(d.getMetadata().get("bootstrap"))) {
                    continue;
                }
                if ("tcp".equalsIgnoreCase(d.getProtocol())) {
                    nodes.add(ScatterGatherNode.from(d));
                }
            }
        }
        return nodes;
    }

    /**
     * 注册 bootstrap 引导节点到本地缓存。
     *
     * @param node 引导节点
     */
    void registerBootstrapNode(ScatterGatherNode node) {
        Discovery discovery = Discovery.builder()
                .id(node.getNodeId())
                .serverId(node.getNodeId())
                .protocol(node.getProtocol())
                .host(node.getHost())
                .port(node.getPort())
                .timeout((int) setting.getTimeoutMillis())
                .weight(1D)
                .metadata(Map.of("bootstrap", "true", "servicePath", setting.getServicePath()))
                .build();
        registerService(setting.getServicePath(), discovery);
        log.debug("注册 bootstrap 引导节点: {}:{}", node.getHost(), node.getPort());
    }

    /**
     * 执行 bootstrap 一次性 hash 交换。
     * <p>向引导节点发送本节点身份指纹，引导节点回传其指纹，用于节点互认。</p>
     *
     * @param node 引导节点
     */
    void exchangeBootstrapHash(ScatterGatherNode node) {
        String localHash = computeNodeHash();
        ScatterGatherContext ctx = new ScatterGatherContext(
                UUID.randomUUID().toString(),
                setting.getServicePath(),
                setting.getTimeoutMillis(),
                1,
                Map.of("bootstrap", "hash-exchange", "hash", localHash,
                        "nodeId", setting.getNodeId()));
        try {
            ScatterGatherResult<Discovery> result = remoteClient.invoke(ctx, node, setting.getTimeoutMillis());
            if (result != null && result.isSuccess() && result.getData() != null) {
                String remoteHash = result.getData().getMetadata() == null ? null : result.getData().getMetadata().get("hash");
                log.info("bootstrap hash 交换成功，引导节点: {}，远端指纹: {}", node.getNodeId(), remoteHash);
            } else {
                log.debug("bootstrap hash 交换无响应: {}", node.getNodeId());
            }
        } catch (Exception e) {
            log.debug("bootstrap hash 交换失败: {}，原因: {}", node.getNodeId(), e.getMessage());
        }
    }

    /**
     * 计算本节点身份指纹 hash。
     *
     * @return sha256 摘要
     */
    private String computeNodeHash() {
        return DigestUtils.sha256(setting.getNodeId() + ":" + setting.getHost() + ":" + setting.getTcpPort());
    }

    /**
     * 自动检索：从远程节点查询服务列表，更新本地缓存。
     */
    private void autoDiscovery() {
        try {
            ensureRuntime();
            String path = setting.getServicePath();
            Set<Discovery> local = getPath(path);
            List<ScatterGatherNode> remoteNodes = new ArrayList<>();
            if (local != null && !local.isEmpty()) {
                for (Discovery d : local) {
                    if ("seed".equals(d.getMetadata().get("seed"))) {
                        continue;
                    }
                    remoteNodes.add(ScatterGatherNode.from(d));
                }
            }
            // 如果本地没有远程节点，委托当前模式解析
            if (remoteNodes.isEmpty() && mode != null) {
                remoteNodes.addAll(mode.resolveRemoteNodes(this));
            }
            if (remoteNodes.isEmpty()) {
                return;
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (ScatterGatherNode node : remoteNodes) {
                ScatterGatherContext ctx = new ScatterGatherContext(
                        UUID.randomUUID().toString(),
                        path,
                        setting.getTimeoutMillis(),
                        1,
                        Map.of()
                );
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        ScatterGatherResult<Discovery> result = remoteClient.invoke(ctx, node, setting.getTimeoutMillis());
                        if (result != null && result.isSuccess() && result.getData() != null) {
                            Discovery remoteDiscovery = result.getData();
                            String remotePath = StringUtils.startWithAppend(
                                    remoteDiscovery.getUriSpec() != null ? remoteDiscovery.getUriSpec() : path, "/");
                            Discovery merged = Discovery.builder()
                                    .id(remoteDiscovery.getId())
                                    .serverId(remoteDiscovery.getServerId())
                                    .protocol(remoteDiscovery.getProtocol())
                                    .host(remoteDiscovery.getHost())
                                    .port(remoteDiscovery.getPort())
                                    .timeout(remoteDiscovery.getTimeout())
                                    .weight(remoteDiscovery.getWeight())
                                    .uriSpec(remoteDiscovery.getUriSpec())
                                    .metadata(remoteDiscovery.getMetadata())
                                    .build();
                            addToCache(remotePath, merged);
                            log.debug("自动检索: 从 {} 发现服务 {}:{}", node.getEndpoint(),
                                    merged.getHost(), merged.getPort());
                        }
                    } catch (Exception e) {
                        log.debug("自动检索节点 {} 失败: {}", node.getEndpoint(), e.getMessage());
                    }
                }, executorService));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(setting.getTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("自动检索异常: {}", e.getMessage());
        }
    }

    // ======================== 远程查询 ========================

    private Discovery remoteQuery(String path, String balance, String protocol) {
        if (remoteClient == null) {
            return null;
        }

        // 从本地缓存或配置中获取远程节点列表
        List<ScatterGatherNode> nodes = resolveRemoteNodes(path);
        if (nodes.isEmpty()) {
            return null;
        }

        long timeout = setting.getTimeoutMillis() > 0 ? setting.getTimeoutMillis() : setting.getRemoteTimeoutMillis();
        List<CompletableFuture<ScatterGatherResult<Discovery>>> futures = new ArrayList<>();
        int count = 0;
        for (ScatterGatherNode node : nodes) {
            if (setting.getRemoteConcurrency() != Integer.MAX_VALUE && count >= setting.getRemoteConcurrency()) {
                break;
            }
            count++;
            ScatterGatherContext ctx = new ScatterGatherContext(
                    UUID.randomUUID().toString(),
                    path,
                    timeout,
                    1,
                    Map.of("balance", balance == null ? "weight" : balance, "protocol", protocol == null ? "" : protocol)
            );
            CompletableFuture<ScatterGatherResult<Discovery>> future = CompletableFuture.supplyAsync(
                    () -> invokeRemoteWithRetry(ctx, node, timeout), executorService);
            futures.add(future);
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            all.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("等待远程结果超时: {}", e.getMessage());
        }

        List<ScatterGatherResult<Discovery>> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            ScatterGatherNode node = nodes.get(i);
            ScatterGatherResult<Discovery> result = getResult(node, futures.get(i));
            results.add(result);
        }

        List<Discovery> discoveries = results.stream()
                .filter(ScatterGatherResult::isSuccess)
                .map(ScatterGatherResult::getData)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return selectService(discoveries, balance, protocol);
    }

    private List<ScatterGatherNode> resolveRemoteNodes(String path) {
        // 从本地缓存中获取已知节点
        Set<Discovery> local = getPath(path);
        List<ScatterGatherNode> nodes = new ArrayList<>();
        if (local != null && !local.isEmpty()) {
            nodes.addAll(local.stream()
                    .filter(d -> "tcp".equalsIgnoreCase(d.getProtocol()))
                    .map(ScatterGatherNode::from)
                    .collect(Collectors.toList()));
        }
        // 本地无节点时，委托当前模式解析
        if (nodes.isEmpty() && mode != null) {
            nodes.addAll(mode.resolveRemoteNodes(this));
        }
        return nodes;
    }

    /**
     * 从 seed 地址列表解析节点。
     *
     * @return 节点列表
     */
    List<ScatterGatherNode> resolveSeedNodes() {
        if (setting.getSeedAddresses() == null || CollectionUtils.isEmpty(setting.getSeedAddresses())) {
            return List.of();
        }
        return setting.getSeedAddresses().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseSeedAddress)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 解析单个 seed 地址，支持 host:port 和 [ipv6]:port 格式。
     *
     * @param address 地址
     * @return 节点
     */
    private ScatterGatherNode parseSeedAddress(String address) {
        SeedAddress seed = SeedAddress.parse(address);
        if (seed == null) {
            log.warn("seed 地址格式无效: {}", address);
            return null;
        }
        int port = seed.effectivePort(setting.getDefaultPort());
        return new ScatterGatherNode(seed.nodeId(setting.getDefaultPort()), seed.getHost(), port, "tcp", null, Map.of());
    }

    private ScatterGatherResult<Discovery> invokeRemoteWithRetry(ScatterGatherContext context, ScatterGatherNode node, long timeout) {
        if (!setting.isRetryEnabled()) {
            return invokeRemote(context, node, timeout);
        }

        ScatterGatherResult<Discovery> lastResult = null;
        int maxRetries = setting.getMaxRetries();
        long retryDelay = setting.getRetryDelayMillis();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ScatterGatherResult<Discovery> result = invokeRemote(context, node, timeout);
                if (result.isSuccess() || attempt == maxRetries) {
                    return result;
                }
                lastResult = result;
            } catch (Exception e) {
                lastResult = ScatterGatherResult.failure(node.getNodeId(), e.getMessage());
                listener.onRetry(node, attempt, e);
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return handleFallback(context, node, lastResult != null ? lastResult :
                ScatterGatherResult.failure(node.getNodeId(), "重试次数耗尽"));
    }

    private ScatterGatherResult<Discovery> invokeRemote(ScatterGatherContext context, ScatterGatherNode node, long timeout) {
        log.debug("invokeRemote: node={} host={}:{}", node.getNodeId(), node.getHost(), node.getPort());
        try {
            ScatterGatherResult<Discovery> result = remoteClient.invoke(context, node, timeout);
            return result;
        } catch (Exception e) {
            return handleError(node, e);
        }
    }

    private ScatterGatherResult<Discovery> handleError(ScatterGatherNode node, Throwable e) {
        if (e instanceof java.util.concurrent.TimeoutException) {
            if (setting.isEnableFallback()) {
                return handleFallback(null, node, ScatterGatherResult.timeout(node.getNodeId(), "请求超时"));
            }
            return ScatterGatherResult.timeout(node.getNodeId(), "请求超时: " + e.getMessage());
        }
        if (setting.isEnableFallback()) {
            return handleFallback(null, node, ScatterGatherResult.failure(node.getNodeId(), e.getMessage()));
        }
        return ScatterGatherResult.failure(node.getNodeId(), e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private ScatterGatherResult<Discovery> handleFallback(ScatterGatherContext context, ScatterGatherNode node, ScatterGatherResult<Discovery> originalResult) {
        if (!setting.isEnableFallback()) {
            return originalResult;
        }
        Object fallbackValue = setting.getFallbackResult();
        listener.onFallback(node, fallbackValue);
        if (originalResult.isTimeout()) {
            return ScatterGatherResult.failure(node.getNodeId(), "超时降级");
        }
        if (!(fallbackValue instanceof Discovery)) {
            log.warn("降级结果类型不正确，期望 Discovery，实际: {}，节点: {}",
                    fallbackValue == null ? "null" : fallbackValue.getClass().getName(), node.getNodeId());
            return ScatterGatherResult.failure(node.getNodeId(), "降级结果类型不正确");
        }
        return ScatterGatherResult.success(node.getNodeId(), (Discovery) fallbackValue);
    }

    private ScatterGatherResult<Discovery> getResult(ScatterGatherNode node, CompletableFuture<ScatterGatherResult<Discovery>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return handleError(node, e);
        }
    }

    // ======================== 默认解析/标准化 ========================

    private static Discovery defaultParse(ScatterGatherContext context, Object source) {
        if (source instanceof Discovery discovery) {
            return discovery;
        }
        if (source instanceof String s) {
            return com.chua.common.support.lang.json.Json.fromJson(s, Discovery.class);
        }
        return null;
    }

    private static Discovery defaultNormalize(ScatterGatherContext context, Discovery input) {
        return input;
    }

    // ======================== 辅助方法 ========================

    private Discovery selectService(List<Discovery> discoveries, String balance, String protocol) {
        if (discoveries.isEmpty()) {
            return null;
        }
        List<Discovery> filtered = discoveries;
        if (protocol != null && !protocol.isEmpty()) {
            filtered = discoveries.stream()
                    .filter(d -> protocol.equalsIgnoreCase(d.getProtocol()))
                    .collect(Collectors.toList());
        }
        if (filtered.isEmpty()) {
            filtered = discoveries;
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        String strategy = balance != null ? balance.toLowerCase() : "random";
        return switch (strategy) {
            case "roundrobin", "round-robin" -> {
                int idx = roundRobinIndex.getAndIncrement() % filtered.size();
                yield filtered.get(idx);
            }
            case "weight" -> {
                double totalWeight = filtered.stream()
                        .mapToDouble(d -> Math.max(0, d.getWeight()))
                        .sum();
                if (totalWeight <= 0) {
                    yield filtered.get(0);
                }
                double r = ThreadLocalRandom.current().nextDouble() * totalWeight;
                double cumulative = 0;
                for (Discovery d : filtered) {
                    cumulative += Math.max(0, d.getWeight());
                    if (r <= cumulative) {
                        yield d;
                    }
                }
                yield filtered.get(filtered.size() - 1);
            }
            default -> filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));
        };
    }

    private void validate() {
        if (queryHandler == null) {
            throw new IllegalStateException("queryHandler 不能为空");
        }
        if (dataParser == null) {
            throw new IllegalStateException("dataParser 不能为空");
        }
        if (dataNormalizer == null) {
            throw new IllegalStateException("dataNormalizer 不能为空");
        }
        if (aggregator == null) {
            throw new IllegalStateException("aggregator 不能为空");
        }
        if (remoteClient == null) {
            throw new IllegalStateException("remoteClient 不能为空");
        }
        if (listener == null) {
            throw new IllegalStateException("listener 不能为空");
        }
    }

    private void ensureRuntime() {
        if (executorService == null || executorService.isShutdown()) {
            executorService = ThreadUtils.newCachedThreadPool("scatter-gather-executor");
        }
    }

    /**
     * 停止服务。
     */
    public synchronized void stop() throws Exception {
        if (!started) {
            return;
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (autoDiscoveryExecutor != null) {
            autoDiscoveryExecutor.shutdownNow();
            autoDiscoveryStarted = false;
        }
        for (ScheduledExecutorService se : subscribeExecutors) {
            se.shutdownNow();
        }
        subscribeExecutors.clear();
        if (deduplicator != null) {
            deduplicator.close();
        }
        started = false;
        listener.onStop(setting);
        // 根据配置决定是否清除缓存
        if (setting.isCleanupOnClose()) {
            localCache.clear();
            log.debug("已清除本地缓存");
        }
        log.info("ScatterGatherServiceDiscovery 已停止");
    }
}
