package com.chua.redis.support.client;

import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.common.support.spi.annotations.Spi;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.Transaction;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Redis 链式客户端，全功能封装 Jedis。
 *
 * <p>采用 Builder 模式 + 链式 API，支持 String/Hash/List/Set/ZSet 全类型操作。</p>
 *
 * <p>实现了 {@link KvEngine} 接口，支持统一 KV 操作契约，包括前缀查询
 * {@link KvEngine#findAllByPrefix(String)} 和链式操作 {@link KvEngine#key(String)}。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 创建客户端
 * RedisClient client = RedisClient.create("127.0.0.1", 6379);
 *
 * // String 操作
 * client.set("key", "value");
 * String val = client.get("key");
 *
 * // 链式操作
 * client.key("user:1").set("张三").expire(3600);
 *
 * // Hash 操作
 * client.hset("user:1", "name", "张三");
 * client.hget("user:1", "name");
 *
 * // Pipeline
 * client.pipeline(pipe -> {
 *     pipe.set("k1", "v1");
 *     pipe.set("k2", "v2");
 *     pipe.set("k3", "v3");
 * });
 *
 * // 自动资源管理
 * try (RedisClient c = RedisClient.create()) {
 *     c.set("hello", "world");
 * }
 * }</pre>
 *
 * @author CH
 * @since 2026/07/18
 */
/**
 * Redis KV 后端 SPI 名称。
 */
@Spi("redis")
@Slf4j
@Getter
public class RedisClient implements AutoCloseable, KvEngine {

    private final JedisPool pool;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeout;

    private RedisClient(JedisPool pool, String host, int port, String password, int database, int timeout) {
        this.pool = pool;
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.timeout = timeout;
    }

    /**
     * 基于已有的 Jedis 连接池构造客户端（复用连接池，不新建）。
     * <p>供数据源层将既有 {@link JedisPool} 包装为 {@link KvEngine} 视图使用。</p>
     *
     * @param pool 已有的 Jedis 连接池，不可为 null
     */
    public RedisClient(JedisPool pool) {
        this(pool, null, 0, null, 0, 0);
    }

    /**
     * 基于 SPI 配置构造客户端，供 {@code ServiceProvider.of(KvEngine.class).getNewExtension("redis", props)} 调用。
     * <p>支持属性：host（默认 127.0.0.1）、port（默认 6379）、password、database（默认 0）、
     * timeout（毫秒，默认 3000）、maxTotal（默认 20）、maxIdle（默认 10）、minIdle（默认 2）。</p>
     *
     * @param properties SPI 配置，不可为 null
     */
    public RedisClient(Properties properties) {
        this(buildPool(properties),
                properties.getProperty("host", "127.0.0.1"),
                Integer.parseInt(properties.getProperty("port", "6379")),
                properties.getProperty("password"),
                Integer.parseInt(properties.getProperty("database", "0")),
                Integer.parseInt(properties.getProperty("timeout", "3000")));
    }

    /**
     * 根据 SPI 配置构建 Jedis 连接池。
     *
     * @param properties SPI 配置，不可为 null
     * @return Jedis 连接池
     */
    private static JedisPool buildPool(Properties properties) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(Integer.parseInt(properties.getProperty("maxTotal", "20")));
        config.setMaxIdle(Integer.parseInt(properties.getProperty("maxIdle", "10")));
        config.setMinIdle(Integer.parseInt(properties.getProperty("minIdle", "2")));
        config.setTestOnBorrow(true);

        String host = properties.getProperty("host", "127.0.0.1");
        int port = Integer.parseInt(properties.getProperty("port", "6379"));
        int timeout = Integer.parseInt(properties.getProperty("timeout", "3000"));
        String password = properties.getProperty("password");
        int database = Integer.parseInt(properties.getProperty("database", "0"));
        if (password != null && !password.isEmpty()) {
            return new JedisPool(config, host, port, timeout, password, database);
        }
        return new JedisPool(config, host, port, timeout, null, database);
    }

    // ==================== 工厂方法 ====================

    public static RedisClient create() {
        return create("127.0.0.1", 6379);
    }

    public static RedisClient create(String host, int port) {
        return builder().host(host).port(port).build();
    }

    public static RedisClient create(String host, int port, String password) {
        return builder().host(host).port(port).password(password).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 连接操作 ====================

    /**
     * Ping 测试连接。
     *
     * @return PONG
     */
    public String ping() {
        return execute(j -> j.ping());
    }

    /**
     * 切换数据库。
     *
     * @param index 数据库索引
     * @return OK
     */
    public String select(int index) {
        return execute(j -> {
            j.select(index);
            return "OK";
        });
    }

    // ==================== String 操作 ====================

    /**
     * 设置键值。
     */
    public String set(String key, String value) {
        return execute(j -> j.set(key, value));
    }

    /**
     * 设置键值（带过期时间）。
     */
    public String set(String key, String value, int expireSeconds) {
        return execute(j -> j.setex(key, expireSeconds, value));
    }

    /**
     * 设置键值（NX/XX 模式）。
     *
     * @param key   键
     * @param value 值
     * @param nx    仅当键不存在时设置
     * @return OK 或 null
     */
    public String setNx(String key, String value, boolean nx) {
        if (nx) {
            return execute(j -> String.valueOf(j.setnx(key, value)));
        }
        return execute(j -> j.set(key, value));
    }

    /**
     * 获取值。
     */
    @Override
    public String get(String key) {
        return execute(j -> j.get(key));
    }

    /**
     * 写入键值对（永不过期），实现 {@link KvEngine} 契约。
     *
     * @param key   键，不可为 null
     * @param value 值，可为 null
     */
    @Override
    public void put(String key, String value) {
        set(key, value);
    }

    /**
     * 写入带过期时间的键值对，实现 {@link KvEngine} 契约。
     *
     * @param key   键，不可为 null
     * @param value 值，可为 null
     * @param ttl   过期时长，不可为 null
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        int expireSeconds = (int) Math.min(ttl.getSeconds(), Integer.MAX_VALUE);
        set(key, value, expireSeconds);
    }

    /**
     * 判断键是否存在，实现 {@link KvEngine} 契约。
     *
     * @param key 键，不可为 null
     * @return 存在返回 true，否则返回 false
     */
    @Override
    public boolean containsKey(String key) {
        return exists(key);
    }

    /**
     * 删除指定键，实现 {@link KvEngine} 契约。
     *
     * @param key 键，不可为 null
     * @return 删除成功返回 true，否则返回 false
     */
    @Override
    public boolean delete(String key) {
        return del(key) > 0;
    }

    /**
     * 获取并设置过期时间。
     */
    public String getSet(String key, String value) {
        return execute(j -> j.getSet(key, value));
    }

    /**
     * 值递增，实现 {@link KvEngine} 契约。
     */
    @Override
    public long incr(String key) {
        return execute(j -> j.incr(key));
    }

    /**
     * 值递增指定步长。
     */
    public long incrBy(String key, long increment) {
        return execute(j -> j.incrBy(key, increment));
    }

    /**
     * 值递减。
     */
    public long decr(String key) {
        return execute(j -> j.decr(key));
    }

    /**
     * 值递减指定步长。
     */
    public long decrBy(String key, long decrement) {
        return execute(j -> j.decrBy(key, decrement));
    }

    /**
     * 追加值。
     */
    public long append(String key, String value) {
        return execute(j -> j.append(key, value));
    }

    /**
     * 获取值长度。
     */
    public long strlen(String key) {
        return execute(j -> j.strlen(key));
    }

    /**
     * 批量获取。
     */
    public List<String> mget(String... keys) {
        return execute(j -> j.mget(keys));
    }

    /**
     * 批量设置。
     */
    public String mset(String... keysAndValues) {
        return execute(j -> j.mset(keysAndValues));
    }

    // ==================== Key 操作 ====================

    /**
     * 删除键。
     */
    public long del(String... keys) {
        return execute(j -> j.del(keys));
    }

    /**
     * 判断键是否存在。
     */
    public boolean exists(String key) {
        return execute(j -> j.exists(key));
    }

    /**
     * 设置过期时间（秒），实现 {@link KvEngine} 契约。
     */
    @Override
    public void expire(String key, long seconds) {
        execute(j -> j.expire(key, (int) seconds));
    }

    /**
     * 设置过期时间（毫秒）。
     */
    public long pexpire(String key, long milliseconds) {
        return execute(j -> j.pexpire(key, milliseconds));
    }

    /**
     * 获取剩余生存时间（秒），实现 {@link KvEngine} 契约。
     */
    @Override
    public long ttl(String key) {
        return execute(j -> j.ttl(key));
    }

    /**
     * 获取剩余生存时间（毫秒）。
     */
    public long pttl(String key) {
        return execute(j -> j.pttl(key));
    }

    /**
     * 移除过期时间。
     */
    public long persist(String key) {
        return execute(j -> j.persist(key));
    }

    /**
     * 重命名键。
     */
    public String rename(String key, String newKey) {
        return execute(j -> j.rename(key, newKey));
    }

    /**
     * 获取键的类型。
     */
    public String type(String key) {
        return execute(j -> j.type(key));
    }

    /**
     * 搜索键（生产环境慎用）。
     */
    public Set<String> keys(String pattern) {
        return execute(j -> j.keys(pattern));
    }

    /**
     * 查找所有以指定前缀开头的键值对，使用 SCAN 命令避免阻塞。
     *
     * <p>实现 {@link KvEngine#findAllByPrefix(String)} 契约。</p>
     *
     * @param prefix 键前缀，不可为 null
     * @return 匹配前缀的键值对映射；无匹配时返回空 Map
     */
    @Override
    public Map<String, String> findAllByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Map.of();
        }
        return execute(j -> {
            Map<String, String> result = new LinkedHashMap<>();
            String pattern = prefix + "*";
            ScanParams scanParams = new ScanParams().count(1000);
            String cursor = "0";
            while (true) {
                ScanResult<String> scanResult = j.scan(cursor, scanParams);
                List<String> keys = scanResult.getResult();
                for (String key : keys) {
                    if (key.startsWith(prefix)) {
                        result.put(key, j.get(key));
                    }
                }
                cursor = scanResult.getCursor();
                if (cursor.equals("0")) {
                    break;
                }
            }
            return result;
        });
    }

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 字段值。
     */
    public long hset(String key, String field, String value) {
        return execute(j -> j.hset(key, field, value));
    }

    /**
     * 批量设置 Hash 字段。
     */
    public long hset(String key, Map<String, String> fieldValues) {
        return execute(j -> j.hset(key, fieldValues));
    }

    /**
     * 获取 Hash 字段值。
     */
    public String hget(String key, String field) {
        return execute(j -> j.hget(key, field));
    }

    /**
     * 获取所有 Hash 字段。
     */
    public Map<String, String> hgetAll(String key) {
        return execute(j -> j.hgetAll(key));
    }

    /**
     * 删除 Hash 字段。
     */
    public long hdel(String key, String... fields) {
        return execute(j -> j.hdel(key, fields));
    }

    /**
     * 判断 Hash 字段是否存在。
     */
    public boolean hexists(String key, String field) {
        return execute(j -> j.hexists(key, field));
    }

    /**
     * Hash 字段递增。
     */
    public long hincrBy(String key, String field, long increment) {
        return execute(j -> j.hincrBy(key, field, increment));
    }

    /**
     * 获取所有 Hash 字段名。
     */
    public Set<String> hkeys(String key) {
        return execute(j -> j.hkeys(key));
    }

    /**
     * 获取所有 Hash 值。
     */
    public List<String> hvals(String key) {
        return execute(j -> j.hvals(key));
    }

    /**
     * 获取 Hash 字段数量。
     */
    public long hlen(String key) {
        return execute(j -> j.hlen(key));
    }

    // ==================== List 操作 ====================

    /**
     * 左侧推入。
     */
    public long lpush(String key, String... values) {
        return execute(j -> j.lpush(key, values));
    }

    /**
     * 右侧推入。
     */
    public long rpush(String key, String... values) {
        return execute(j -> j.rpush(key, values));
    }

    /**
     * 左侧弹出。
     */
    public String lpop(String key) {
        return execute(j -> j.lpop(key));
    }

    /**
     * 右侧弹出。
     */
    public String rpop(String key) {
        return execute(j -> j.rpop(key));
    }

    /**
     * 获取列表范围。
     */
    public List<String> lrange(String key, long start, long stop) {
        return execute(j -> j.lrange(key, start, stop));
    }

    /**
     * 获取列表长度。
     */
    public long llen(String key) {
        return execute(j -> j.llen(key));
    }

    /**
     * 按索引获取元素。
     */
    public String lindex(String key, long index) {
        return execute(j -> j.lindex(key, index));
    }

    /**
     * 按索引设置元素。
     */
    public String lset(String key, long index, String value) {
        return execute(j -> j.lset(key, index, value));
    }

    /**
     * 修剪列表。
     */
    public String ltrim(String key, long start, long stop) {
        return execute(j -> j.ltrim(key, start, stop));
    }

    /**
     * 阻塞左侧弹出。
     */
    public List<String> blpop(int timeout, String key) {
        return execute(j -> j.blpop(timeout, key));
    }

    /**
     * 阻塞右侧弹出。
     */
    public List<String> brpop(int timeout, String key) {
        return execute(j -> j.brpop(timeout, key));
    }

    // ==================== Set 操作 ====================

    /**
     * 添加集合元素。
     */
    public long sadd(String key, String... members) {
        return execute(j -> j.sadd(key, members));
    }

    /**
     * 获取集合所有元素。
     */
    public Set<String> smembers(String key) {
        return execute(j -> j.smembers(key));
    }

    /**
     * 判断是否为集合成员。
     */
    public boolean sismember(String key, String member) {
        return execute(j -> j.sismember(key, member));
    }

    /**
     * 获取集合大小。
     */
    public long scard(String key) {
        return execute(j -> j.scard(key));
    }

    /**
     * 移除集合元素。
     */
    public long srem(String key, String... members) {
        return execute(j -> j.srem(key, members));
    }

    /**
     * 随机获取集合元素。
     */
    public String srandmember(String key) {
        return execute(j -> j.srandmember(key));
    }

    /**
     * 集合差集。
     */
    public Set<String> sdiff(String... keys) {
        return execute(j -> j.sdiff(keys));
    }

    /**
     * 集合交集。
     */
    public Set<String> sinter(String... keys) {
        return execute(j -> j.sinter(keys));
    }

    /**
     * 集合并集。
     */
    public Set<String> sunion(String... keys) {
        return execute(j -> j.sunion(keys));
    }

    // ==================== ZSet 操作 ====================

    /**
     * 添加有序集合元素。
     */
    public long zadd(String key, double score, String member) {
        return execute(j -> j.zadd(key, score, member));
    }

    /**
     * 批量添加有序集合元素。
     */
    public long zadd(String key, Map<String, Double> scoreMembers) {
        return execute(j -> j.zadd(key, scoreMembers));
    }

    /**
     * 获取有序集合分数。
     */
    public Double zscore(String key, String member) {
        return execute(j -> j.zscore(key, member));
    }

    /**
     * 有序集合分数递增。
     */
    public double zincrby(String key, double increment, String member) {
        return execute(j -> j.zincrby(key, increment, member));
    }

    /**
     * 获取有序集合排名（升序）。
     */
    public Long zrank(String key, String member) {
        return execute(j -> j.zrank(key, member));
    }

    /**
     * 获取有序集合排名（降序）。
     */
    public Long zrevrank(String key, String member) {
        return execute(j -> j.zrevrank(key, member));
    }

    /**
     * 获取有序集合范围（升序）。
     */
    public List<String> zrange(String key, long start, long stop) {
        return execute(j -> j.zrange(key, start, stop));
    }

    /**
     * 获取有序集合范围（降序）。
     */
    public List<String> zrevrange(String key, long start, long stop) {
        return execute(j -> j.zrevrange(key, start, stop));
    }

    /**
     * 按分数范围获取。
     */
    public List<String> zrangeByScore(String key, double min, double max) {
        return execute(j -> j.zrangeByScore(key, min, max));
    }

    /**
     * 获取有序集合大小。
     */
    public long zcard(String key) {
        return execute(j -> j.zcard(key));
    }

    /**
     * 按分数范围计数。
     */
    public long zcount(String key, double min, double max) {
        return execute(j -> j.zcount(key, min, max));
    }

    /**
     * 移除有序集合元素。
     */
    public long zrem(String key, String... members) {
        return execute(j -> j.zrem(key, members));
    }

    // ==================== 通用执行 ====================

    /**
     * 在 Pipeline 中批量执行。
     *
     * @param action Pipeline 操作
     * @return Pipeline 结果
     */
    public <T> T pipeline(Function<Pipeline, T> action) {
        try (Jedis jedis = pool.getResource()) {
            Pipeline pipe = jedis.pipelined();
            try {
                T result = action.apply(pipe);
                pipe.sync();
                return result;
            } catch (Exception e) {
                pipe.sync();
                throw e;
            }
        }
    }

    /**
     * 执行事务。
     *
     * @param action 事务操作
     * @return 事务结果
     */
    public <T> T transaction(Function<Transaction, T> action) {
        try (Jedis jedis = pool.getResource()) {
            Transaction tx = jedis.multi();
            try {
                T result = action.apply(tx);
                tx.exec();
                return result;
            } catch (Exception e) {
                tx.discard();
                throw e;
            }
        }
    }

    /**
     * 发布消息。
     */
    public long publish(String channel, String message) {
        return execute(j -> j.publish(channel, message));
    }

    /**
     * 执行 Lua 脚本。
     */
    public Object eval(String script, List<String> keys, List<String> args) {
        return execute(j -> j.eval(script, keys, args));
    }

    // ==================== 内部方法 ====================

    private <T> T execute(java.util.function.Function<Jedis, T> action) {
        try (Jedis jedis = pool.getResource()) {
            return action.apply(jedis);
        } catch (Exception e) {
            throw new RedisClientException("Redis 操作失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    // ==================== Builder ====================

    public static class Builder {
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password;
        private int database = 0;
        private int timeout = 3000;
        private int maxTotal = 20;
        private int maxIdle = 10;
        private int minIdle = 2;
        private boolean testOnBorrow = true;

        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder database(int database) { this.database = database; return this; }
        public Builder timeout(int timeout) { this.timeout = timeout; return this; }
        public Builder maxTotal(int maxTotal) { this.maxTotal = maxTotal; return this; }
        public Builder maxIdle(int maxIdle) { this.maxIdle = maxIdle; return this; }
        public Builder minIdle(int minIdle) { this.minIdle = minIdle; return this; }
        public Builder testOnBorrow(boolean testOnBorrow) { this.testOnBorrow = testOnBorrow; return this; }

        public RedisClient build() {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(maxTotal);
            config.setMaxIdle(maxIdle);
            config.setMinIdle(minIdle);
            config.setTestOnBorrow(testOnBorrow);

            JedisPool pool;
            if (password != null && !password.isEmpty()) {
                pool = new JedisPool(config, host, port, timeout, password, database);
            } else {
                pool = new JedisPool(config, host, port, timeout, null, database);
            }
            return new RedisClient(pool, host, port, password, database, timeout);
        }
    }

    // ==================== 异常类 ====================

    public static class RedisClientException extends RuntimeException {
        public RedisClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
