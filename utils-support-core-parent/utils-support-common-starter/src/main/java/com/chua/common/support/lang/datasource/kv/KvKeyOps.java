package com.chua.common.support.lang.datasource.kv;

import java.time.Duration;

/**
 * 键级链式操作器，由 {@link KvTemplate#key(String)} 创建并绑定到单个键。
 *
 * <p>支持在单次链式调用中完成「设值 + 过期」的组合操作，
 * 例如 {@code template.key("user:1").expire(3600).set("张三")}。</p>
 *
 * <p>所有写操作在调用 {@link #set(String)} 时才真正提交到底层 {@link KvOperations}，
 * 此前通过 {@link #expire(long)} / {@link #expire(Duration)} 累积的过期时间仅作为待提交参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KvKeyOps {

    /**
     * 底层 KV 存储实现
     */
    private final KvOperations delegate;

    /**
     * 当前绑定的键
     */
    private final String key;

    /**
     * 待提交的过期时长，未设置时为 null
     */
    private Duration pendingTtl;

    /**
     * 根据底层实现与键构造键级操作器。
     *
     * @param delegate 底层 KV 存储实现，不可为 null
     * @param key      绑定的键，不可为 null
     */
    KvKeyOps(KvOperations delegate, String key) {
        this.delegate = delegate;
        this.key = key;
    }

    /**
     * 设置待提交的过期时间（秒），在 {@link #set(String)} 时一并提交。
     *
     * @param seconds 过期秒数，必须大于 0
     * @return this，支持链式调用
     */
    public KvKeyOps expire(long seconds) {
        this.pendingTtl = Duration.ofSeconds(seconds);
        return this;
    }

    /**
     * 设置待提交的过期时间，在 {@link #set(String)} 时一并提交。
     *
     * @param ttl 过期时长，不可为 null
     * @return this，支持链式调用
     */
    public KvKeyOps expire(Duration ttl) {
        this.pendingTtl = ttl;
        return this;
    }

    /**
     * 将值写入当前键，若已设置过期时间则带 TTL 提交。
     *
     * @param value 待写入的值，可为 null
     * @return this，支持链式调用
     */
    public KvKeyOps set(String value) {
        if (pendingTtl != null) {
            delegate.put(key, value, pendingTtl);
        } else {
            delegate.put(key, value);
        }
        return this;
    }

    /**
     * 读取当前键的值。
     *
     * @return 当前键的值，不存在返回 null
     */
    public String get() {
        return delegate.get(key);
    }

    /**
     * 判断当前键是否存在。
     *
     * @return 存在返回 true，否则返回 false
     */
    public boolean exists() {
        return delegate.containsKey(key);
    }

    /**
     * 删除当前键。
     *
     * @return 删除成功返回 true，否则返回 false
     */
    public boolean delete() {
        return delegate.delete(key);
    }

    /**
     * 获取当前键的剩余生存时间（秒）。
     *
     * @return 剩余秒数；键不存在返回 -2，无过期返回 -1
     */
    public long ttl() {
        return delegate.ttl(key);
    }

    /**
     * 将当前键对应的整数值原子递增 1。
     *
     * @return 递增后的最新值
     */
    public long incr() {
        return delegate.incr(key);
    }
}
