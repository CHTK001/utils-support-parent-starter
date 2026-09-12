package com.chua.redis.support.client;

import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.redis.support.engine.RedisReactorEngine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 响应式 KV 客户端，同时实现同步 {@link KvEngine} 接口与响应式 API。
 *
 * <p>同步方法通过 {@code Mono.block()} 执行，适用于传统同步场景；
 * 响应式方法直接返回 {@link Mono}/{@link Flux}，适用于 Reactor 响应式流。</p>
 *
 * <p>作为 SPI 扩展注册在 {@code com.chua.common.support.lang.datasource.kv.KvEngine} 下，
 * 键为 {@code "redis"}，可通过 {@code ServiceProvider.of(KvEngine.class).getExtension("redis")} 获取。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 同步用法（KvEngine 接口）
 * KvEngine engine = ServiceProvider.of(KvEngine.class).getExtension("redis");
 * String value = engine.get("mykey");
 *
 * // 响应式用法（扩展方法）
 * Mono<String> valueMono = ((RedisClient) engine).reactiveGet("mykey");
 * }</pre>ne).reactiveGet("mykey");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@Getter
@SuppressWarnings("rawtypes")
@Spi("redis")
public class RedisClient implements KvEngine, java.lang.AutoCloseable {

    /**
     * 默认 Redis 连接地址
     */
    private static final String DEFAULT_REDIS_URL = "redis://127.0.0.1:6379";

    /**
     * 默认超时时间（秒）
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 5;

    /**
     * 底层响应式引擎
     */
    private final RedisReactorEngine engine;

    /**
      * 使用默认配置创建 redis客户端。
     * 连接地址为 {@value #DEFAULT_REDIS_URL}。
     */
    public RedisClient() {
        this(DEFAULT_REDIS_URL);
    }

    /**
      * 使用指定地址创建 redis客户端。
     *
     * @param url Redis 连接地址（如 Redis://127.0.0.1:6379）
     */
    public RedisClient(String url) {
        this(url, null);
    }

    /**
      * 使用指定地址和密码创建 redis客户端。
     *
     * @param url      Redis 连接地址
     * @param password 密码（可为 空）
     */
    public RedisClient(String url, String password) {
        this.engine = new RedisReactorEngine();
        if (password != null && !password.isEmpty()) {
            this.engine.addDataSource("default", url, password);
        } else {
            this.engine.addDataSource("default", url);
        }
        this.engine.setTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
        log.info("RedisClient 已初始化，地址: {}", url);
    }

    /**
      * 使用已有引擎创建 redis客户端（不管理引擎生命周期）。
     *
     * @param engine 已有的 redisreactorengine 实例
     */
    public RedisClient(RedisReactorEngine engine) {
        this.engine = engine;
    }

    /**
     * 创建构建器。
     * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== KvEngine 同步接口实现 ====================

    /**
     * 同步获取键值。
     *
     * @param key 键
     * @return 键对应的值；键不存在时返回 空
     */
    @Override
    public String get(String key) {
        return engine.get(key).block();
    }

    /**
     * 同步写入键值对（永不过期）。
     *
     * @param key   键
     * @param value 值
     */
    @Override
    public void put(String key, String value) {
        engine.set(key, value).block();
    }

    /**
     * 同步写入带过期时间的键值对。
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时长
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        engine.setex(key, value, ttl.getSeconds()).block();
    }

    /**
     * 同步判断键是否存在。
     *
     * @param key 键
     * @return 存在返回 true
     */
    @Override
    public boolean containsKey(String key) {
        return Boolean.TRUE.equals(engine.exists(key).block());
    }

    /**
     * 同步删除键。
     *
     * @param key 键
     * @return 删除成功返回 true
     */
    @Override
    public boolean delete(String key) {
        return Boolean.TRUE.equals(engine.delete(key).block());
    }

    /**
     * 同步原子递增。
     *
     * @param key 键
     * @return 递增后的最新值
     */
    @Override
    public long incr(String key) {
        Long result = engine.incr(key).block();
        return result != null ? result : 0L;
    }

    /**
     * 同步获取键的剩余生存时间（秒）。
     *
     * @param key 键
     * @return 剩余秒数；键不存在返回 -2，存在但无过期返回 -1
     */
    @Override
    public long ttl(String key) {
        Long result = engine.ttl(key).block();
        return result != null ? result : -2L;
    }

    /**
     * 同步为已存在的键设置过期时间。
     *
     * @param key     键
     * @param seconds 过期秒数
     */
    @Override
    public void expire(String key, long seconds) {
        engine.setex(key, "dummy_" + System.nanoTime(), seconds).block();
    }

    /**
      * 同步前缀扫描，返回匹配键值对的 映射。
     *
     * @param prefix 键前缀
     * @return 匹配前缀的键值对映射；无匹配时返回空 映射
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> findAllByPrefix(String prefix) {
        List<Map.Entry<String, String>> entries = (List) engine.scanKeys(prefix + "*")
                .flatMap(key -> engine.get(key)
                        .map(value -> new AbstractMap.SimpleEntry<>(
                                key, value != null ? value : ""))
                        .onErrorResume(e -> Mono.empty()))
                .collectList()
                .block();
        if (entries == null) {
            return Map.of();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    // ==================== 响应式扩展方法 ====================

    /**
     * 响应式获取键值。
     *
     * @param key 键
     * @return 值 Mono，不存在返回空 Mono
     */
    public Mono<String> reactiveGet(String key) {
        return engine.get(key);
    }

    /**
     * 响应式写入键值对（永不过期）。
     *
     * @param key   键
     * @param value 值
     * @return 完成 Mono
     */
    public Mono<Void> reactiveSet(String key, String value) {
        return engine.set(key, value);
    }

    /**
     * 响应式写入带过期时间的键值对。
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时长（秒）
     * @return 完成 Mono
     */
    public Mono<Void> reactiveSetex(String key, String value, long ttl) {
        return engine.setex(key, value, ttl);
    }

    /**
     * 响应式判断键是否存在。
     *
     * @param key 键
     * @return 存在返回 Mono.TRUE
     */
    public Mono<Boolean> reactiveExists(String key) {
        return engine.exists(key);
    }

    /**
     * 响应式删除键。
     *
     * @param key 键
     * @return 删除成功返回 Mono.TRUE
     */
    public Mono<Boolean> reactiveDelete(String key) {
        return engine.delete(key);
    }

    /**
     * 响应式原子递增。
     *
     * @param key 键
     * @return 递增后的值 Mono
     */
    public Mono<Long> reactiveIncr(String key) {
        return engine.incr(key);
    }

    /**
     * 响应式原子递减。
     *
     * @param key 键
     * @return 递减后的值 Mono
     */
    public Mono<Long> reactiveDecr(String key) {
        return engine.decr(key);
    }

    /**
     * 响应式获取键的剩余生存时间（秒）。
     *
     * @param key 键
     * @return 剩余秒数 Mono
     */
    public Mono<Long> reactiveTtl(String key) {
        return engine.ttl(key);
    }

    /**
     * 响应式前缀扫描，返回匹配键值对的 Flux。
     *
     * @param prefix 键前缀
     * @return 匹配键值对 Flux
     */
    public Flux<Map.Entry<String, String>> reactiveFindAllByPrefix(String prefix) {
        return engine.scanKeys(prefix + "*")
                .flatMap(key -> engine.get(key)
                        .map(value -> new AbstractMap.SimpleEntry<>(key, value != null ? value : ""))
                        .onErrorResume(e -> Mono.empty()));
    }

    /**
     * 响应式执行任意 Redis 命令。
     *
     * @param command Redis 命令字符串（如 "获取 mykey"）
     * @return 结果 Mono
     */
    public Mono<Object> reactiveExecCommand(String command) {
        return engine.execCommand(command);
    }

    /**
     * 响应式批量执行命令（Pipeline 模拟）。
     *
     * @param commands 命令列表，每项为 "CMD 参数1 参数2 ..." 格式
     * @return 结果列表 Flux
     */
    public Flux<Object> reactiveExecBatch(List<String> commands) {
        return engine.execBatch(commands);
    }

    /**
      * 响应式 哈希 获取。
     *
     * @param key   哈希键
     * @param field 字段名
     * @return 字段值 Mono
     */
    public Mono<String> reactiveHget(String key, String field) {
        return engine.hget(key, field);
    }

    /**
      * 响应式 哈希 设置。
     *
     * @param key   哈希键
     * @param field 字段名
     * @param value 字段值
     * @return 完成 Mono
     */
    public Mono<Void> reactiveHset(String key, String field, String value) {
        return engine.hset(key, field, value);
    }

    /**
      * 响应式 哈希 GETALL。
     *
     * @param key 哈希键
     * @return 字段值对 Flux
     */
    public Flux<Map.Entry<String, String>> reactiveHgetall(String key) {
        return engine.hgetall(key);
    }

    /**
      * 响应式 列表 LRANGE。
     *
     * @param key   列表键
     * @param start 起始索引
     * @param end   结束索引
     * @return 元素列表 Flux
     */
    public Flux<String> reactiveLrange(String key, long start, long end) {
        return engine.lrange(key, start, end);
    }

    /**
      * 响应式 设置 SMEMBERS。
     *
     * @param key 集合键
     * @return 成员列表 Flux
     */
    public Flux<String> reactiveSmembers(String key) {
        return engine.smembers(key);
    }

    /**
     * 关闭引擎，释放 Lettuce 连接资源。
     */
    public void close() {
        engine.close();
        log.info("RedisClient 已关闭");
    }

    /**
      * redis客户端 构建器。
     * @author CH
     * @since 4.0.0
     * @return 构建的结果
     * @param timeoutMs 超时ms
     */
    public static class Builder {
        private String host = "127.0.0.1"; // 主机
        private int port = 6379; // 端口
        private String password = ""; // 密码
        private int database = 0; // database
        /**
         * 主机。
         * @param host 主机
         * @return 主机的结果
         */
        private long timeoutMs = 5000;

        /**
         * 主机。
         * @param host 主机
         * @return 主机的结果
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        /**
         * 端口。
         * @param port 端口
         * @return 端口的结果
         */
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        /**
         * 密码。
         * @param password 密码
         * @return 密码的结果
         */
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        /**
         * database。
         * @param database database
         * @return database的结果
         * @param timeoutMs 超时ms
         */
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder timeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public RedisClient build() {
            String url = "redis://" + host + ":" + port + "/" + database;
            String pwd = (password == null || password.isEmpty()) ? null : password;
            RedisClient client = new RedisClient(url, pwd);
            client.engine.setTimeout(java.time.Duration.ofMillis(timeoutMs));
            return client;
        }
    }
}
