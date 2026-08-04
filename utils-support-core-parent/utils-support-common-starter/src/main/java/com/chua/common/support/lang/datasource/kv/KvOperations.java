package com.chua.common.support.lang.datasource.kv;

import java.time.Duration;
import org.jspecify.annotations.NullUnmarked;

/**
 * 键值对（KV）存储公共契约，定义与具体后端无关的字符串型 KV 操作集合。
 *
 * <p>该接口作为 Redis、MapDB、本地内存等任意 KV 后端的统一抽象，
 * 仅涵盖各后端都能支持的 {@code String → String} 基础能力（get / put / delete / containsKey / incr）。</p>
 *
 * <p>部分后端（如 MapDB）原生不支持过期时间（TTL），因此 {@code put(key, value, ttl)}、
 * {@code ttl(key)}、{@code expire(key, seconds)} 三个带过期语义的方法在接口中提供
 * <b>默认实现</b>并抛出 {@link UnsupportedOperationException}，由支持 TTL 的后端（如 Redis）覆盖。</p>
 *
 * <p><b>SPI 契约</b>：本接口是 KV 后端的 SPI 扩展点。各后端实现类使用
 * {@code @Spi("名称")} 标注，并在 {@code META-INF/services/com.chua.common.support.lang.datasource.kv.KvOperations}
 * 中注册，通过 {@code ServiceProvider.of(KvOperations.class).getNewExtension("名称", properties)} 加载。</p>
 *
 * <h2>典型使用</h2>
 * <pre>{@code
 * Properties props = new Properties();
 * props.setProperty("host", "127.0.0.1");
 * props.setProperty("port", "6379");
 * KvOperations kv = ServiceProvider.of(KvOperations.class).getNewExtension("redis", props);
 * kv.put("token", "abc123", Duration.ofHours(1));
 * String token = kv.get("token");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
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
