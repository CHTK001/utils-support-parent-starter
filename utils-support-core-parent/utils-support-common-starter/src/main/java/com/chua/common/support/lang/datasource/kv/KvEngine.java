package com.chua.common.support.lang.datasource.kv;

import java.time.Duration;
import java.util.Map;

/**
 * 键值对（KV）引擎统一契约，替代 {@link KvOperations} 和 {@link KvTemplate}。
 *
 * <p>该接口定义了与具体后端无关的 KV 操作集合，涵盖 Redis、ChronicleMap、
 * RocksDB、LevelDB 等后端的通用能力：{@code get / put / delete / containsKey / incr}、
 * 带过期时间的写入、前缀批量查询、以及基于 {@link KvKeyOps} 的链式操作。</p>
 *
 * <p><b>前缀查询</b>：{@link #findAllByPrefix(String)} 返回所有以指定前缀开头的键值对，
 * 后端实现可自行决定使用 SCAN（Redis）或线性扫描（ChronicleMap），但必须保证
 * 在数据量较大时不会一次性返回全部结果。</p>
 *
 * <p><b>链式操作</b>：{@link #key(String)} 返回键级链式操作器，支持在单次调用中完成
 * 「设值 + 过期」的组合操作，例如 {@code engine.key("user:1").expire(3600).set("张三")}</p>
 *
 * <h2>SPI 契约</h2>
 * <pre>{@code
 * // META-INF/services/com.chua.common.support.lang.datasource.kv.KvOperations
 * // com.chua.redis.support.client.RedisClient
 * // com.chua.chronicle.support.kv.ChronicleMapKv
 *
 * Properties props = new Properties();
 * props.setProperty("host", "127.0.0.1");
 * props.setProperty("port", "6379");
 * KvEngine engine = ServiceProvider.of(KvEngine.class).getNewExtension("redis", props);
 * engine.put("token", "abc123", Duration.ofHours(1));
 * Map<String, String> all = engine.findAllByPrefix("user:");
 * }</pre>
 *
 * <h2>Spring 注入</h2>
 * <pre>{@code
 * @Autowired
 * private KvEngine kvEngine;
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface KvEngine extends KvOperations {

    // ==================== 核心 KV 操作 ====================

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

    // ==================== TTL 操作 ====================

    /**
     * 写入带过期时间的键值对。
     *
     * <p>不支持 TTL 的后端（如 ChronicleMap）默认抛出 {@link UnsupportedOperationException}。</p>
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
     *
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
     *
     * <p>不支持 TTL 的后端默认抛出 {@link UnsupportedOperationException}。</p>
     *
     * @param key     键，不可为 null
     * @param seconds 过期秒数，必须大于 0
     */
    default void expire(String key, long seconds) {
        throw new UnsupportedOperationException("该 KV 后端不支持 TTL");
    }

    // ==================== 前缀查询 ====================

    /**
     * 查找所有以指定前缀开头的键值对。
     *
     * <p>返回的 Map 中，键为匹配的完整键名，值为对应的值。键顺序不保证。
     * 对于不支持前缀查询的后端（如 ChronicleMap），默认实现为线性扫描所有键，
     * 在数据量较大时可能性能较差，实现类应自行优化。</p>
     *
     * @param prefix 键前缀，不可为 null
     * @return 匹配前缀的键值对映射；无匹配时返回空 Map
     */
    Map<String, String> findAllByPrefix(String prefix);

    // ==================== 链式操作 ====================

    /**
     * 绑定到指定键，返回键级链式操作器。
     *
     * <p>支持在单次链式调用中完成「设值 + 过期」的组合操作：
     * {@code engine.key("user:1").expire(3600).set("张三")}</p>
     *
     * @param key 目标键，不可为 null
     * @return 键级操作器
     */
    default KvKeyOps key(String key) {
        return new KvKeyOps(this, key);
    }
}
