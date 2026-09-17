package com.chua.common.support.lang.datasource.kv;

import java.time.Duration;

/**
* 键值对（KV）存储公共契约，定义与具体后端无关的字符串型 KV 操作集合。
*
* <p><b>已废弃</b>：请使用 {@link KvEngine} 接口替代。{@code KvEngine} 是本接口的超集，
* 额外提供 {@link KvEngine#findAllByPrefix(String)} 前缀查询能力和 {@link KvEngine#key(String)} 链式操作。</p>
*
* <p>本接口作为 {@link KvEngine} 的父接口保留，以维持 SPI 扩展点的向后兼容。
* 所有实现类应直接实现 {@link KvEngine}，而非本接口。</p>
*
* @deprecated 使用 {@link KvEngine} 接口替代。
* @author CH
* @since 4.0.0.42
 */
@Deprecated(forRemoval = true, since = "4.0.0.42")
public interface KvOperations {

    /**
    * 根据键获取对应的值。
    *
    * @param key 键，不可为 null
    * @return 键对应的值；键不存在时返回 null
    */
    String get(String key);

    /**
    * 写入键值对（永不过期）。
    *
    * @param key   键，不可为 null
    * @param value 值，可为 null（等效于删除）
    */
    void put(String key, String value);

    /**
    * 判断键是否存在。
    *
    * @param key 键，不可为 null
    * @return 存在返回 true，否则返回 false
    */
    boolean containsKey(String key);

    /**
    * 删除指定键。
    *
    * @param key 键，不可为 null
    * @return 删除成功（键原本存在）返回 true，否则返回 false
    */
    boolean delete(String key);

    /**
    * 将键对应的整数值原子递增 1。
    *
    * @param key 键，不可为 null
    * @return 递增后的最新值
    */
    long incr(String key);

    /**
    * 写入带过期时间的键值对。
    * <p>不支持 TTL 的后端默认抛出 {@link UnsupportedOperationException}。</p>
    *
    * @param key   键，不可为 null
    * @param value 值，可为 null
    * @param ttl   过期时长，不可为 null
    */
    default void put(String key, String value, Duration ttl) {
        throw new UnsupportedOperationException("该 KV 后端不支持 TTL");
    }

    /**
    * 获取键的剩余生存时间（秒）。
    * <p>不支持 TTL 的后端默认抛出 {@link UnsupportedOperationException}。</p>
    *
    * @param key 键，不可为 null
    * @return 剩余秒数；键不存在返回 -2，存在但无过期返回 -1
    */
    default long ttl(String key) {
        throw new UnsupportedOperationException("该 KV 后端不支持 TTL");
    }

    /**
    * 为已存在的键设置过期时间（秒）。
    * <p>不支持 TTL 的后端默认抛出 {@link UnsupportedOperationException}。</p>
    *
    * @param key     键，不可为 null
    * @param seconds 过期秒数，必须大于 0
    */
    default void expire(String key, long seconds) {
        throw new UnsupportedOperationException("该 KV 后端不支持 TTL");
    }
}
