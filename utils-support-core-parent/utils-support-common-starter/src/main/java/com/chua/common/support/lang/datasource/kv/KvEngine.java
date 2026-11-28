package com.chua.common.support.lang.datasource.kv;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import com.chua.common.support.utils.StringUtils;

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
 * // 注册文件：META-INF/extensions/com.chua.common.support.lang.datasource.kv.KvEngine
 * // 内容格式：别名=实现类全限定名，例如
 * // redis=com.chua.redis.support.client.RedisClient
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
@SuppressWarnings("removal")
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

    /**
     * 是否支持跨调用方的原子比较更新（包括条件创建与条件删除）。
     *
     * @return 支持返回 true；不得使用进程内锁冒充共享存储的原子性
     */
    default boolean supportsCompareAndSet() {
        return false;
    }

    /**
     * 值与预期完全一致时原子更新；比较与写入必须由后端作为一个操作执行。
     *
     * <p>expectedValue 为 null 表示键不存在；newValue 为 null 表示删除。
     * ttl 非空时设置新有效期，必须至少为一毫秒；ttl 为空时保留原有有效期，
     * 因而创建新键时必须指定 ttl。空字符串是普通值，不等同于 null。</p>
     *
     * @param key 键，不可为空
     * @param expectedValue 预期旧值；null 表示不存在
     * @param newValue 新值；null 表示删除
     * @param ttl 新有效期；更新已有键或删除时允许为 null
     * @return 比较成功且操作完成返回 true，否则 false
     */
    default boolean compareAndSet(String key, String expectedValue, String newValue, Duration ttl) {
        throw new UnsupportedOperationException("该 KV 后端不支持原子比较更新");
    }

    // ==================== TTL 操作 ====================

    /**
     * 当前后端是否支持 TTL 操作（{@link #put(String, String, Duration)} /
     * {@link #ttl(String)} / {@link #expire(String, long)}）。
     *
     * <p>调用方可据此选择带 TTL 的写入路径，避免依赖捕获
     * {@link UnsupportedOperationException} 做流程判断。
     * 覆写了任一 TTL 操作的实现类必须同时覆写本方法返回 {@code true}。</p>
     *
     * @return 支持 TTL 返回 true，否则 false
     */
    default boolean supportsTtl() {
        return false;
    }

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

    /**
     * 按前缀分批读取：返回前缀范围内键名字典序大于 {@code afterKeyExclusive}（排他）的
     * 键值对中，键名最小的 {@code maxRows} 条，按键名字典序升序排列。
     *
     * <p>用于大数据量流式扫描：调用方把本批返回的最后一条键名作为下一批的
     * {@code afterKeyExclusive} 循环拉取，逐批处理、随时终止，
     * 避免一次性物化全量数据把堆打爆（大库全量 {@link #findAllByPrefix(String)} 有 OOM 风险）。</p>
     *
     * <p>默认实现退化为全量读取后内存切片，兼容未做范围扫描优化的后端；
     * 具备有序范围扫描能力的后端（如 JDBC 主键范围扫描）应覆写本方法，
     * 把 {@code key &gt; after ORDER BY key LIMIT n} 下推到存储层。</p>
     *
     * @param prefix            键前缀，不可为 null
     * @param afterKeyExclusive 起始键（排他）；null 或空串表示从前缀起点开始
     * @param maxRows           本批最多返回条数，小于 1 时按 1 处理
     * @return 键名字典序升序的键值对；无更多数据时返回空 Map
     */
    default Map<String, String> findAllByPrefix(String prefix, String afterKeyExclusive, int maxRows) {
        NavigableMap<String, String> sorted = new TreeMap<>(findAllByPrefix(prefix));
        if (StringUtils.isNotEmpty(afterKeyExclusive)) {
            sorted = sorted.tailMap(afterKeyExclusive, false);
        }
        int rows = Math.max(1, maxRows);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (result.size() >= rows) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * 按前缀分批倒序读取：返回前缀范围内键名字典序小于 {@code beforeKeyExclusive}（排他）的
     * 键值对中，键名最大的 {@code maxRows} 条，按键名字典序降序排列。
     *
     * <p>与 {@link #findAllByPrefix(String, String, int)} 的升序游标互补：调用方把本批
     * 返回的最后一条键名（本批中最小的键名）作为下一批的 {@code beforeKeyExclusive}
     * 循环拉取，即可从最大键开始向更小键方向流式扫描、随时终止——
     * 适用于「取最新 N 条」这类只关心近期数据的场景。</p>
     *
     * <p>默认实现退化为全量读取后内存倒序切片，兼容未做范围扫描优化的后端
     * （大前缀下有 OOM 风险且每批重复全量扫描，仅建议低频调用）；
     * 具备有序范围扫描能力的后端应覆写本方法，
     * 把 {@code key &lt; before ORDER BY key DESC LIMIT n} 下推到存储层。</p>
     *
     * @param prefix             键前缀，不可为 null
     * @param beforeKeyExclusive 起始键（排他，向更小键方向扫描）；null 或空串表示从前缀最大键开始
     * @param maxRows            本批最多返回条数，小于 1 时按 1 处理
     * @return 键名字典序降序的键值对；无更多数据时返回空 Map
     */
    default Map<String, String> findAllByPrefixDesc(String prefix, String beforeKeyExclusive, int maxRows) {
        NavigableMap<String, String> sorted = new TreeMap<>(findAllByPrefix(prefix));
        if (StringUtils.isNotEmpty(beforeKeyExclusive)) {
            sorted = sorted.headMap(beforeKeyExclusive, false);
        }
        int rows = Math.max(1, maxRows);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sorted.descendingMap().entrySet()) {
            if (result.size() >= rows) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

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
