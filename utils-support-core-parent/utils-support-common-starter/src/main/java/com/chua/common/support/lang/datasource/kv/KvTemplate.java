package com.chua.common.support.lang.datasource.kv;

import java.time.Duration;
import org.jspecify.annotations.NullUnmarked;

/**
 * KV 链式操作模板，包裹任意 {@link KvOperations} 实现，提供流畅（Fluent）API。
 *
 * <p>设计目标：让 Redis、MapDB 等不同后端获得统一的链式调用体验。
 * 通过 {@link #key(String)} 进入键级操作，或直接调用 {@link #put}/{@link #get} 等无状态方法。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * KvOperations kvOps = ServiceProvider.of(KvOperations.class).getNewExtension("redis", props);
 * KvTemplate kv = new KvTemplate(kvOps);
 *
 * // 链式：设值 + 过期
 * kv.key("user:1").expire(3600).set("张三");
 *
 * // 键级读取
 * String name = kv.key("user:1").get();
 *
 * // 无状态直接调用
 * kv.put("k", "v", Duration.ofMinutes(5));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public class KvTemplate {

    /**
     * 底层 KV 存储实现
     */
    private final KvOperations delegate;

    /**
     * 根据底层实现构造链式模板。
     *
     * @param delegate 底层 KV 存储实现，不可为 null
     */
    public KvTemplate(KvOperations delegate) {
        this.delegate = delegate;
    }

    /**
     * 绑定到指定键，返回键级链式操作器。
     *
     * @param key 目标键，不可为 null
     * @return 键级操作器
     */
    public KvKeyOps key(String key) {
        return new KvKeyOps(delegate, key);
    }

    /**
     * 获取底层原始 KV 存储实现。
     *
     * @return 底层 KvOperations 实例
     */
    public KvOperations raw() {
        return delegate;
    }

    /**
     * 读取键对应的值。
     *
     * @param key 键，不可为 null
     * @return 值，不存在返回 null
     */
    public String get(String key) {
        return delegate.get(key);
    }

    /**
     * 写入键值对（永不过期）。
     *
     * @param key   键，不可为 null
     * @param value 值，可为 null
     */
    public void put(String key, String value) {
        delegate.put(key, value);
    }

    /**
     * 写入带过期时间的键值对。
     *
     * @param key   键，不可为 null
     * @param value 值，可为 null
     * @param ttl   过期时长，不可为 null
     */
    public void put(String key, String value, Duration ttl) {
        delegate.put(key, value, ttl);
    }

    /**
     * 判断键是否存在。
     *
     * @param key 键，不可为 null
     * @return 存在返回 true，否则返回 false
     */
    public boolean containsKey(String key) {
        return delegate.containsKey(key);
    }

    /**
     * 删除键。
     *
     * @param key 键，不可为 null
     * @return 删除成功返回 true，否则返回 false
     */
    public boolean delete(String key) {
        return delegate.delete(key);
    }

    /**
     * 原子递增键对应的整数值。
     *
     * @param key 键，不可为 null
     * @return 递增后的最新值
     */
    public long incr(String key) {
        return delegate.incr(key);
    }

    /**
     * 获取键的剩余生存时间（秒）。
     *
     * @param key 键，不可为 null
     * @return 剩余秒数；键不存在返回 -2，无过期返回 -1
     */
    public long ttl(String key) {
        return delegate.ttl(key);
    }

    /**
     * 为键设置过期时间（秒）。
     *
     * @param key     键，不可为 null
     * @param seconds 过期秒数
     */
    public void expire(String key, long seconds) {
        delegate.expire(key, seconds);
    }
}
