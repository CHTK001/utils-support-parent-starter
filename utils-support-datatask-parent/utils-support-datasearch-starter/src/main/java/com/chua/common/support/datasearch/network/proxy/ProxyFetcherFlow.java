package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.spi.ServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
* 代理获取器门面，支持链式调用、多源聚合和轮询获取。
*
* <pre>{@code
* // 从所有 SPI 实现获取代理
* List<String> proxies = ProxyFetcherFlow.of().fetchAll();
*
* // 指定源获取
* String proxy = ProxyFetcherFlow.of("free-api").fetchOne();
*
* // 轮询获取
* String proxy = ProxyFetcherFlow.of("free-api").roundRobin().fetchOne();
* }</pre>cherFlow.of("free-api").roundRobin().fetchOne();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public final class ProxyFetcherFlow {

    /**
    * 代理源名称
    */
    private final String sourceName;

    /**
    * 是否轮询模式
    */
    private boolean roundRobin;

    /**
    * 降级回调
    */
    private Supplier<String> fallback;

    /**
    * 创建 代理获取流 实例
    * @param sourceName 源名称
    */
    private ProxyFetcherFlow(String sourceName) {
        this.sourceName = sourceName;
    }

    /**
    * 创建代理获取门面（从所有 SPI 实现聚合）。
    *
    * @return 门面实例
    */
    public static ProxyFetcherFlow of() {
        return new ProxyFetcherFlow(null);
    }

    /**
    * 创建代理获取门面（指定源名称）。
    *
    * @param sourceName 代理源名称
    * @return 门面实例
    */
    public static ProxyFetcherFlow of(String sourceName) {
        return new ProxyFetcherFlow(sourceName);
    }

    /**
    * 设置轮询模式。
    *
    * @return this
    */
    public ProxyFetcherFlow roundRobin() {
        this.roundRobin = true;
        return this;
    }

    /**
    * 设置降级回调。
    *
    * @param fallback 降级回调
    * @return this
    */
    public ProxyFetcherFlow fallback(Supplier<String> fallback) {
        this.fallback = fallback;
        return this;
    }

    /**
    * 获取一个代理。
    *
    * @return 代理地址，格式 "主机:端口"
    */
    public String fetchOne() {
        List<String> proxies = fetchProxies();
        if (proxies.isEmpty()) {
            if (fallback != null) {
                return fallback.get();
            }
            return null;
        }
        if (roundRobin) {
            return roundRobinSelect(proxies);
        }
        return proxies.get((int) (Math.random() * proxies.size()));
    }

    /**
    * 获取所有代理。
    *
    * @return 代理地址列表
    */
    public List<String> fetchAll() {
        return fetchProxies();
    }

    /**
    * 获取代理
    *
    * @return 获取代理的结果
    */
    private List<String> fetchProxies() {
        Map<String, ProxyFetcher> fetchers = ServiceProvider.of(ProxyFetcher.class).list();
        if (fetchers.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ProxyFetcher fetcher : fetchers.values()) {
            if (sourceName == null || sourceName.equals(fetcher.getSourceName())) {
                try {
                    result.addAll(fetcher.fetchProxies());
                } catch (Exception ignored) {
                    // 单个源失败不影响其他源
                }
            }
        }
        return result;
    }

    /**
    * 轮询计数器缓存。
    */
    private static final Map<String, AtomicInteger> ROUND_ROBIN_CACHE = new ConcurrentHashMap<>();

    /**
    * roundrobin选择
    *
    * @param proxies 代理
    * @return roundrobin选择的结果
    */
    private String roundRobinSelect(List<String> proxies) {
        String key = sourceName != null ? sourceName : "all";
        AtomicInteger counter = ROUND_ROBIN_CACHE.computeIfAbsent(key, k -> new AtomicInteger(0));
        int index = counter.getAndIncrement() % proxies.size();
        return proxies.get(index);
    }
}
