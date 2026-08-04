package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.AbstractServiceDiscovery;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.Event;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullUnmarked;

/**
 * 基于 Scatter-Gather 的服务发现实现。
 * <p>将服务发现查询散射到多个节点，聚合结果后返回。</p>
 *
 * @author CH
 */
@NullUnmarked
@Slf4j
public class ScatterGatherServiceDiscovery extends AbstractServiceDiscovery {

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
        executorService = Executors.newCachedThreadPool();
        if (setting.isDeduplicationEnabled()) {
            deduplicator = new ScatterGatherDeduplicator(setting.getDeduplicationTtlMillis());
        }
        started = true;
        listener.onStart(setting);
        log.info("ScatterGatherServiceDiscovery 已启动，节点ID: {}", setting.getNodeId());
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
        // 简化实现：定时轮询本地缓存并通知
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "scatter-gather-subscribe"));
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
        if (local == null || local.isEmpty()) {
            return List.of();
        }
        return local.stream()
                .filter(d -> "tcp".equalsIgnoreCase(d.getProtocol()))
                .map(ScatterGatherNode::from)
                .collect(Collectors.toList());
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
        // 简单轮询
        return filtered.get(new Random().nextInt(filtered.size()));
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
            executorService = Executors.newCachedThreadPool();
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
        if (deduplicator != null) {
            deduplicator.close();
        }
        started = false;
        listener.onStop(setting);
        log.info("ScatterGatherServiceDiscovery 已停止");
    }
}
