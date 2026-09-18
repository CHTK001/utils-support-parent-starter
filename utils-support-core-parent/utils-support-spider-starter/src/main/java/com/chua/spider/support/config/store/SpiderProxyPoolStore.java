package com.chua.spider.support.config.store;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.spider.support.config.model.SpiderProxy;
import com.chua.spider.support.config.model.SpiderProxyPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
* 代理池内存存储。
*
* @author CH
* @since 4.0.0.42
 */
public class SpiderProxyPoolStore {

    /**
    * 自增 标识 生成器
    */
    private final AtomicLong idGenerator = new AtomicLong(1);

    /**
    * 代理池编码 -> 代理池映射
    */
    private final Map<String, SpiderProxyPool> storage = new ConcurrentHashMap<>();

    /**
    * 轮询计数器（按代理池隔离）
    */
    private final Map<String, AtomicLong> roundCounters = new ConcurrentHashMap<>();

    /**
    * 分页查询代理池。
    * @param pageNo pageno
    * @param pageSize page大小
    * @param keyword keyword
    * @return page的结果
    */
    public PageResult<SpiderProxyPool> page(int pageNo, int pageSize, String keyword) {
        List<SpiderProxyPool> all = new ArrayList<>(storage.values());
        if (StringUtils.isNotEmpty(keyword)) {
            String kw = keyword.toLowerCase();
            all.removeIf(p -> !(p.getPoolCode() != null && p.getPoolCode().toLowerCase().contains(kw))
                    && !(p.getPoolName() != null && p.getPoolName().toLowerCase().contains(kw)));
        }
        int total = all.size();
        int from = (pageNo - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<SpiderProxyPool> records = from >= total ? Collections.emptyList()
                : all.subList(from, to);
        return new PageResult<>(records, total, pageSize, pageNo, (total + pageSize - 1) / pageSize);
    }

    /**
    * 按编码查询代理池。
    * @param poolCode 游泳池编码
    * @return 获取的结果
    */
    public SpiderProxyPool get(String poolCode) {
        return storage.get(poolCode);
    }

    /**
    * 保存或更新代理池。
    * @param pool 游泳池
    * @return 保存的结果
    */
    public SpiderProxyPool save(SpiderProxyPool pool) {
        if (pool.getPoolId() == null) {
            pool.setPoolId(idGenerator.getAndIncrement());
        }
        storage.put(pool.getPoolCode(), pool);
        return pool;
    }

    /**
    * 删除代理池。
    * @param poolCode 游泳池编码
    * @return 移除的结果
    */
    public boolean remove(String poolCode) {
        if (storage.remove(poolCode) != null) {
            roundCounters.remove(poolCode);
            return true;
        }
        return false;
    }

    /**
    * 从池中挑选一个代理。
    *
    * <p>ROUND 策略按顺序轮询；RANDOM 策略随机选取。</p>
    * @param poolCode 游泳池编码
    * @return 下一个代理的结果
    */
    public SpiderProxy nextProxy(String poolCode) {
        SpiderProxyPool pool = storage.get(poolCode);
        if (pool == null || CollectionUtils.isEmpty(pool.getProxies())) {
            return null;
        }
        List<SpiderProxy> proxies = pool.getProxies();
        String strategy = pool.getPoolStrategy() == null ? "ROUND" : pool.getPoolStrategy();
        if ("RANDOM".equalsIgnoreCase(strategy)) {
            int idx = (int) (Math.random() * proxies.size());
            return proxies.get(idx);
        }
        AtomicLong counter = roundCounters.computeIfAbsent(poolCode, k -> new AtomicLong(0));
        long n = counter.getAndIncrement();
        return proxies.get((int) (n % proxies.size()));
    }

    /**
    * 分页结果。
    */
    public record PageResult<T>(
            List<T> records,
            long total,
            int size,
            int current,
            int pages
    ) {
    }
}
