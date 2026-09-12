package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpMethod;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
* 带 TTL（Time-To-Live）的 HTTP 响应缓存，面向高频调用场景优化。
*
* <p>基于 {@link ConcurrentHashMap} 实现的线程安全缓存，支持：</p>
* <ul>
*   <li><b>惰性过期</b> — 读取时检查 TTL，过期条目在下次访问时自动失效</li>
*   <li><b>容量上限</b> — 缓存条目数超过上限时自动清理过期条目</li>
*   <li><b>命中统计</b> — 记录缓存命中/未命中次数，用于监控和调优</li>
*   <li><b>零锁读取</b> — 基于 ConcurrentHashMap 的分段锁，高并发下读取几乎无竞争</li>
* </ul>
*
* <p><b>缓存策略：</b></p>
* <ul>
*   <li>仅缓存 GET 请求（幂等安全）</li>
*   <li>仅缓存 2xx 成功响应</li>
*   <li>缓存键 = 完整请求 URL（含查询参数）</li>
*   <li>TTL 由每个请求独立指定，支持混合过期策略</li>
* </ul>
*
* <p><b>使用示例：</b></p>
* <pre>{@code
* // 通过 HttpClientBuilder 设置缓存
* ClientResponse resp = HttpClientFactory.of("http://api.example.com")
*     .path("/config")
*     .cache(60000)  // 缓存 60 秒
*     .get();
*
* // 后续相同 URL 的请求在 60 秒内直接返回缓存响应
* ClientResponse cached = HttpClientFactory.of("http://api.example.com")
*     .path("/config")
*     .cache(60000)
*     .get();  // 不发起网络请求，直接返回缓存
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see DefaultHttpClient
 */
public class TtlResponseCache {

    /**
    * 默认最大缓存条目数。
     */
    private static final int DEFAULT_MAX_ENTRIES = 1024;

    /**
    * 缓存条目：响应 + 过期时间戳。
     */
    private record CacheEntry(ClientResponse response, long expireAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /**
    * 缓存存储：Key = 缓存键（method + url），Value = 缓存条目。
     */
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();

    /**
    * 最大缓存条目数。
     */
    private final int maxEntries;

    /**
    * 缓存命中计数器。
     */
    private final LongAdder hitCount = new LongAdder();

    /**
    * 缓存未命中计数器。
     */
    private final LongAdder missCount = new LongAdder();

    /**
    * 缓存驱逐计数器（过期或容量清理）。
     */
    private final LongAdder evictionCount = new LongAdder();

    /**
    * 使用默认容量创建缓存。
     */
    public TtlResponseCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
    * 使用指定容量创建缓存。
    *
    * @param maxEntries 最大缓存条目数，超过后触发过期清理
     */
    public TtlResponseCache(int maxEntries) {
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
    }

    /**
    * 构建缓存键。
    *
    * <p>缓存键由 HTTP 方法和完整 URL 组成，确保不同方法或不同 URL 的请求
    * 不会共享缓存。仅 GET 请求会被缓存。</p>
    *
    * @param request 请求对象
    * @return 缓存键字符串，如果不应该缓存返回 null
     */
    public String buildCacheKey(ClientRequest request) {
        // 仅缓存 GET 请求
        if (request.getMethod() != HttpMethod.GET) {
            return null;
        }
        return "GET:" + request.getUrl();
    }

    /**
    * 从缓存中获取响应。
    *
    * <p>如果缓存命中且未过期，返回缓存的响应副本（防止调用方修改影响缓存）。
    * 如果未命中或已过期，返回 null。</p>
    *
    * @param cacheKey 缓存键
    * @return 缓存的响应副本，未命中返回 null
     */
    public ClientResponse get(String cacheKey) {
        if (cacheKey == null) {
            return null;
        }
        CacheEntry entry = store.get(cacheKey);
        if (entry == null) {
            missCount.increment();
            return null;
        }
        if (entry.isExpired()) {
            // 惰性过期：移除过期条目
            store.remove(cacheKey, entry);
            evictionCount.increment();
            missCount.increment();
            return null;
        }
        hitCount.increment();
        // 返回副本，防止调用方修改 body/headers 影响缓存
        return cloneResponse(entry.response());
    }

    /**
    * 将响应写入缓存。
    *
    * <p>仅缓存 2xx 成功响应。非成功响应（4xx/5xx）不会被缓存，
    * 避免缓存错误结果导致后续请求全部拿到错误数据。</p>
    *
    * @param cacheKey 缓存键
    * @param response 响应对象
    * @param ttlMs    缓存有效期（毫秒）
     */
    public void put(String cacheKey, ClientResponse response, long ttlMs) {
        if (cacheKey == null || response == null || ttlMs <= 0) {
            return;
        }
        // 仅缓存成功响应
        if (!response.isSuccess()) {
            return;
        }
        // 容量检查
        if (store.size() >= maxEntries) {
            evictExpired();
        }
        long expireAt = System.currentTimeMillis() + ttlMs;
        store.put(cacheKey, new CacheEntry(cloneResponse(response), expireAt));
    }

    /**
    * 主动失效指定缓存键。
    *
    * @param cacheKey 缓存键
     */
    public void invalidate(String cacheKey) {
        if (cacheKey != null) {
            CacheEntry removed = store.remove(cacheKey);
            if (removed != null) {
                evictionCount.increment();
            }
        }
    }

    /**
    * 清空所有缓存条目。
     */
    public void clear() {
        int size = store.size();
        store.clear();
        evictionCount.add(size);
    }

    /**
    * 清理所有已过期的缓存条目。
    *
    * <p>可定期调用此方法防止过期条目占用内存。
    * 在高并发场景中由 {@link #put} 自动触发，通常无需手动调用。</p>
     */
    public void evictExpired() {
        Iterator<Map.Entry<String, CacheEntry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                evictionCount.increment();
            }
        }
    }

    /**
    * 获取当前缓存条目数（含过期但未清理的条目）。
    *
    * @return 缓存条目数
     */
    public int size() {
        return store.size();
    }

    /**
    * 获取缓存命中次数。
    *
    * @return 命中次数
     */
    public long getHitCount() {
        return hitCount.sum();
    }

    /**
    * 获取缓存未命中次数。
    *
    * @return 未命中次数
     */
    public long getMissCount() {
        return missCount.sum();
    }

    /**
    * 获取缓存驱逐次数。
    *
    * @return 驱逐次数
     */
    public long getEvictionCount() {
        return evictionCount.sum();
    }

    /**
    * 获取缓存命中率。
    *
    * @return 命中率（0.0 ~ 1.0），无请求记录时返回 0.0
     */
    public double getHitRate() {
        long hits = hitCount.sum();
        long misses = missCount.sum();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    /**
    * 克隆响应对象（浅拷贝 + body 数组深拷贝）。
    *
    * <p>防止调用方修改响应体后影响缓存中的原始数据。</p>
    *
    * @param original 原始响应
    * @return 独立的副本
     */
    private ClientResponse cloneResponse(ClientResponse original) {
        ClientResponse copy = new ClientResponse();
        copy.setStatusCode(original.getStatusCode());
        copy.setMessage(original.getMessage());
        copy.setVersion(original.getVersion());
        copy.setHeaders(original.getHeaders());
        // body 数组深拷贝
        byte[] originalBody = original.getBody();
        if (originalBody != null) {
            byte[] bodyCopy = new byte[originalBody.length];
            System.arraycopy(originalBody, 0, bodyCopy, 0, originalBody.length);
            copy.setBody(bodyCopy);
        }
        return copy;
    }

    @Override
    /** ToString */
    public String toString() {
        return String.format("TtlResponseCache[size=%d, hits=%d, misses=%d, hitRate=%.1f%%, evictions=%d]",
                store.size(), hitCount.sum(), missCount.sum(), getHitRate() * 100, evictionCount.sum());
    }
}
