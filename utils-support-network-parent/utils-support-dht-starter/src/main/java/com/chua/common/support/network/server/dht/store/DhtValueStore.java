package com.chua.common.support.network.server.dht.store;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
* DHT 键值存储。
* <p>
* 存储键值对并支持 TTL 过期自动清理，用于 DHT 协议的本地值存储。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DhtValueStore {

    /**
    * 键值存储的内部映射
     */
    private final Map<String, DhtValueEntry> store = new ConcurrentHashMap<>();

    /**
    * 默认 TTL（毫秒）
     */
    private final long defaultTtlMs;

    /**
    * 构造值存储。
    *
    * @param defaultTtlMs 默认 TTL（毫秒）
     */
    public DhtValueStore(long defaultTtlMs) {
        this.defaultTtlMs = defaultTtlMs;
    }

    /**
    * 存储键值对，使用默认 TTL。
    *
    * @param key   键
    * @param value 值
     */
    public void put(String key, String value) {
        put(key, value, defaultTtlMs);
    }

    /**
    * 存储键值对，指定 TTL。
    *
    * @param key   键
    * @param value 值
    * @param ttlMs TTL（毫秒）
     */
    public void put(String key, String value, long ttlMs) {
        store.put(key, new DhtValueEntry(value, System.currentTimeMillis() + ttlMs));
    }

    /**
    * 根据键获取值。
    *
    * @param key 键
    * @return 值，如果键不存在或已过期返回 空
     */
    public String get(String key) {
        DhtValueEntry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            store.remove(key);
            return null;
        }
        return entry.value;
    }

    /**
    * 获取所有未过期的键值对。
    *
    * @return 未过期的键值 映射
     */
    public Map<String, String> getAll() {
        expire();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, DhtValueEntry> e : store.entrySet()) {
            if (!e.getValue().isExpired()) {
                result.put(e.getKey(), e.getValue().value());
            }
        }
        return result;
    }

    /**
    * 获取所有未过期的键。
    *
    * @return 未过期的键集合
     */
    public Set<String> keys() {
        expire();
        return store.entrySet().stream()
                .filter(e -> !e.getValue().isExpired())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
    * 移除指定键。
    *
    * @param key 要移除的键
     */
    public void remove(String key) {
        store.remove(key);
    }

    /**
    * 获取当前存储的条目数量（已滤过期）。
    *
    * @return 未过期的条目数
     */
    public int size() {
        expire();
        return store.size();
    }

    /**
    * 清理所有已过期的条目。
     */
    public void expire() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    /**
    * 值条目，携带值和过期时间。
    * @param value 值
    * @param expiresAt expiresat
    * @return dht值entry的结果
     */
    private record DhtValueEntry(String value, long expiresAt) {

        /**
        * 判断当前时间是否已过期。
        *
        * @return 过期返回 true
         */
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }

        /**
        * 判断指定时间是否已过期。
        *
        * @param now 当前时间戳
        * @return 过期返回 true
         */
        boolean isExpired(long now) {
            return now >= expiresAt;
        }
    }
}
