package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.ReactorEngine;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import io.lettuce.core.RedisClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Lettuce 的 Redis 响应式引擎，实现 ReactorEngine 接口。
 *
 * <p>不依赖 R2DBC，完全基于 Lettuce 响应式 API 实现。
 * 所有操作通过 boundedElastic 调度器执行，避免阻塞 Reactor 事件循环线程。</p>
 *
 * <p>支持多数据源模式，每个数据源独立维护一个 Lettuce RedisClient 实例。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * RedisReactorEngine engine = new RedisReactorEngine();
 * engine.addDataSource("default", "redis://127.0.0.1:6379");
 *
 * // 响应式 KV 查询
 * Mono<String> value = engine.get("mykey");
 *
 * // 响应式执行 Redis 命令
 * Mono<Integer> result = engine.execute("SET mykey myvalue");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SuppressWarnings("rawtypes")
@Spi("redis")
public class RedisReactorEngine implements ReactorEngine {

    /**
     * Redis 连接 URL 协议前缀
     */
    private static final String REDIS_PREFIX = "redis://";

    /**
     * 数据源名称 -> Lettuce RedisClient
     */
    private final Map<String, RedisClient> lettuceClients = new ConcurrentHashMap<>();

    /**
     * 数据源名称 -> 超时时间
     */
    private final Map<String, Duration> timeouts = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称
     */
    private String defaultDataSourceName;

    /**
     * 无参构造，用于 SPI 加载
     */
    public RedisReactorEngine() {
    }

    /**
     * 添加 Redis 数据源。
     *
     * @param name     数据源名称
     * @param redisUrl Redis 连接 URL（如 redis://127.0.0.1:6379）
     * @return this
     */
    public RedisReactorEngine addDataSource(String name, String redisUrl) {
        if (redisUrl == null || redisUrl.isEmpty()) {
            throw new IllegalArgumentException("Redis URL cannot be null or empty");
        }
        String url = redisUrl;
        if (!url.startsWith(REDIS_PREFIX)) {
            url = REDIS_PREFIX + url;
        }
        if (!url.endsWith("/0")) {
            url = url + "/0";
        }
        lettuceClients.put(name, RedisClient.create(url));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        log.info("Redis 数据源已添加: name={}, url={}", name, url);
        return this;
    }

    /**
     * 添加 Redis 数据源（带认证）。
     *
     * @param name     数据源名称
     * @param redisUrl Redis 连接 URL
     * @param password 密码
     * @return this
     */
    public RedisReactorEngine addDataSource(String name, String redisUrl, String password) {
        if (redisUrl == null || redisUrl.isEmpty()) {
            throw new IllegalArgumentException("Redis URL cannot be null or empty");
        }
        String url = redisUrl;
        if (!url.startsWith(REDIS_PREFIX)) {
            url = REDIS_PREFIX + url;
        }
        if (!url.endsWith("/0")) {
            url = url + "/0";
        }
        if (password != null && !password.isEmpty()) {
            url = url.replaceFirst("://", "://" + password + "@");
        }
        lettuceClients.put(name, RedisClient.create(url));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        log.info("Redis 数据源已添加: name={}, url={}", name, url);
        return this;
    }

    /**
     * 设置默认数据源名称。
     *
     * @param name 数据源名称
     * @return this
     */
    public RedisReactorEngine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    /**
     * 获取默认数据源名称。
     *
     * @return 默认数据源名称
     */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    /**
     * 是否多数据源模式。
     *
     * @return true 表示有多个数据源
     */
    public boolean isMultiDataSource() {
        return lettuceClients.size() > 1;
    }

    /**
     * 获取指定数据源的 Lettuce RedisClient。
     *
     * @param name 数据源名称
     * @return Lettuce RedisClient，未配置返回 null
     */
    public RedisClient getLettuceClient(String name) {
        return lettuceClients.get(name);
    }

    // ==================== ReactorEngine 接口实现 ====================

    /**
     * Redis 不支持 SQL Lambda 查询，抛出 UnsupportedOperationException。
     */
    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException("Redis 不支持 SQL Lambda 查询，请使用 get()/execute() 等响应式方法");
    }

    /**
     * Redis 不支持 SQL Lambda 更新，抛出 UnsupportedOperationException。
     */
    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException("Redis 不支持 SQL Lambda 更新，请使用 set()/hset() 等响应式方法");
    }

    /**
     * Redis 不支持 SQL Lambda 删除，抛出 UnsupportedOperationException。
     */
    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException("Redis 不支持 SQL Lambda 删除，请使用 delete() 等响应式方法");
    }

    /**
     * 响应式执行任意 Redis 命令，返回单行 Map 结果（列名 -> 值）。
     *
     * <p>支持命令：GET、SET、DEL、EXISTS、TTL、INCR、LPUSH、RPUSH、LPOP、RPOP、
     * HGET、HSET、HGETALL、SMEMBERS、ZADD、ZRANGE 等。</p>
     *
     * @param sql    Redis 命令（大写，如 "GET mykey"）
     * @param params 命令参数
     * @return 结果行 Flux，单条记录
     */
    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        String name = defaultDataSourceName;
        if (name == null) {
            return Flux.error(new IllegalStateException("未配置 Redis 数据源"));
        }
        return Flux.fromIterable(executeSingleCommand(name, sql, params))
                .map(val -> Collections.singletonMap("value", val));
    }

    /**
     * 响应式执行任意 Redis 命令并映射为类型化对象。
     *
     * @param sql     Redis 命令
     * @param rowType 目标类型（仅支持 String/Long/Integer/Boolean）
     * @param params  命令参数
     * @param <T>     行类型
     * @return 类型化结果 Flux
     */
    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        String name = defaultDataSourceName;
        if (name == null) {
            return Flux.error(new IllegalStateException("未配置 Redis 数据源"));
        }
        Object result = executeSingleCommand(name, sql, params);
        return Flux.just(convertTo(result, rowType));
    }

    /**
     * 响应式执行 Redis 写操作命令。
     *
     * <p>支持命令：SET、DEL、INCR、DECR、LPUSH、RPUSH、HSET、SADD、ZADD 等。</p>
     *
     * @param sql    Redis 命令
     * @param params 命令参数
     * @return 受影响行数 Mono
     */
    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        String name = defaultDataSourceName;
        if (name == null) {
            return Mono.error(new IllegalStateException("未配置 Redis 数据源"));
        }
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(name, sql, params);
            if (result instanceof Number num) {
                return num.intValue();
            }
            return result != null ? 1 : 0;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式批量执行 Redis 命令。
     *
     * @param sql         Redis 命令模板
     * @param batchParams 批量参数列表
     * @return 每批影响行数 Flux
     */
    @Override
    public Flux<Integer> batch(String sql, List<Object[]> batchParams) {
        if (batchParams == null || batchParams.isEmpty()) {
            return Flux.empty();
        }
        String name = defaultDataSourceName;
        if (name == null) {
            return Flux.error(new IllegalStateException("未配置 Redis 数据源"));
        }
        return Flux.fromIterable(batchParams)
                .map(params -> {
                    Object result = executeSingleCommand(name, sql, params);
                    if (result instanceof Number num) {
                        return num.intValue();
                    }
                    return result != null ? 1 : 0;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 底层执行方法 ====================

    /**
     * 解析并执行单条 Redis 命令，返回原始结果对象。
     *
     * @param name    数据源名称
     * @param command 命令字符串（如 "GET mykey"）
     * @param params  额外参数数组
     * @return 命令执行结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object executeSingleCommand(String name, String command, Object... params) {
        RedisClient client = lettuceClients.get(name);
        if (client == null) {
            throw new IllegalStateException("Redis 数据源未配置: " + name);
        }
        Duration timeout = timeouts.getOrDefault(name, Duration.ofSeconds(5));
        try {
            io.lettuce.core.api.StatefulRedisConnection<String, String> conn = client.connect();
            conn.setTimeout(timeout);
            io.lettuce.core.api.sync.RedisStringCommands<String, String> str = conn.sync();
            io.lettuce.core.api.sync.RedisListCommands<String, String> list = conn.sync();
            io.lettuce.core.api.sync.RedisSetCommands<String, String> set = conn.sync();
            io.lettuce.core.api.sync.RedisServerCommands<String, String> server = conn.sync();
            io.lettuce.core.api.sync.RedisKeyCommands<String, String> key = conn.sync();
            io.lettuce.core.api.sync.RedisHashCommands<String, String> hash = conn.sync();
            io.lettuce.core.api.sync.RedisZSetCommands<String, String> zset = conn.sync();

            String[] parts = command.trim().toUpperCase().split("\\s+");
            String cmd = parts[0];
            String[] args;
            if (params != null && params.length > 0) {
                args = new String[params.length];
                for (int i = 0; i < params.length; i++) {
                    args[i] = params[i].toString();
                }
            } else {
                args = Arrays.copyOfRange(parts, 1, parts.length);
            }

            Object result;
            switch (cmd) {
                case "GET":
                    result = str.get(args[0]);
                    break;
                case "SET":
                    str.set(args[0], args.length > 1 ? args[1] : "");
                    result = "OK";
                    break;
                case "DEL":
                    result = key.del(args);
                    break;
                case "EXISTS":
                    result = key.exists(args);
                    break;
                case "TTL":
                    result = key.ttl(args[0]);
                    break;
                case "PTTL":
                    result = key.pttl(args[0]);
                    break;
                case "INCR":
                    result = str.incr(args[0]);
                    break;
                case "INCRBY":
                    result = str.incrby(args[0], Long.parseLong(args[1]));
                    break;
                case "DECR":
                    result = str.decr(args[0]);
                    break;
                case "DECRBY":
                    result = str.decrby(args[0], Long.parseLong(args[1]));
                    break;
                case "APPEND":
                    result = str.append(args[0], args[1]);
                    break;
                case "STRLEN":
                    result = str.strlen(args[0]);
                    break;
                case "LPUSH":
                    result = list.lpush(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "RPUSH":
                    result = list.rpush(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "LPOP":
                    result = list.lpop(args[0]);
                    break;
                case "RPOP":
                    result = list.rpop(args[0]);
                    break;
                case "LRANGE":
                    long lStart = Long.parseLong(args[0]);
                    long lEnd = Long.parseLong(args[1]);
                    result = list.lrange(args[2], lStart, lEnd);
                    break;
                case "LLEN":
                    result = list.llen(args[0]);
                    break;
                case "HGET":
                    result = hash.hget(args[0], args[1]);
                    break;
                case "HSET":
                    result = hash.hset(args[0], args[1], args[2]);
                    break;
                case "HDEL":
                    result = hash.hdel(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "HGETALL":
                    result = hash.hgetall(args[0]);
                    break;
                case "HEXISTS":
                    result = hash.hexists(args[0], args[1]);
                    break;
                case "HLEN":
                    result = hash.hlen(args[0]);
                    break;
                case "SADD":
                    result = set.sadd(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "SMEMBERS":
                    result = new ArrayList<>(set.smembers(args[0]));
                    break;
                case "SREM":
                    result = set.srem(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "SCARD":
                    result = set.scard(args[0]);
                    break;
                case "ZADD":
                    double score = Double.parseDouble(args[1]);
                    result = zset.zadd(args[0], score, args[2]);
                    break;
                case "ZRANGE":
                    long zStart = Long.parseLong(args[1]);
                    long zEnd = Long.parseLong(args[2]);
                    result = zset.zrange(args[0], zStart, zEnd);
                    break;
                case "ZCARD":
                    result = zset.zcard(args[0]);
                    break;
                case "KEYS":
                    result = new ArrayList<>(key.keys(args[0]));
                    break;
                case "DBSIZE":
                    result = server.dbSize();
                    break;
                case "PING":
                    result = server.ping();
                    break;
                case "FLUSHDB":
                    server.flushdb();
                    result = 1;
                    break;
                default:
                    log.warn("不支持的 Redis 命令: {}", cmd);
                    result = null;
            }
            conn.close();
            return result;
        } catch (Exception e) {
            log.error("执行 Redis 命令失败: {} {}", command, Arrays.toString(params), e);
            throw new RuntimeException("Redis 命令执行失败: " + command, e);
        }
    }

    /**
     * 将结果转换为指定类型。
     *
     * @param result  原始结果
     * @param rowType 目标类型
     * @param <T>     目标类型
     * @return 转换后的结果
     */
    @SuppressWarnings("unchecked")
    private <T> T convertTo(Object result, Class<T> rowType) {
        if (result == null) {
            return null;
        }
        if (rowType.isInstance(result)) {
            return (T) result;
        }
        if (rowType == String.class) {
            return (T) result.toString();
        }
        if (rowType == Integer.class || rowType == int.class) {
            if (result instanceof Number num) {
                return (T) Integer.valueOf(num.intValue());
            }
            return (T) Integer.valueOf(result.toString());
        }
        if (rowType == Long.class || rowType == long.class) {
            if (result instanceof Number num) {
                return (T) Long.valueOf(num.longValue());
            }
            return (T) Long.valueOf(result.toString());
        }
        if (rowType == Boolean.class || rowType == boolean.class) {
            if (result instanceof Number num) {
                return (T) Boolean.valueOf(num.intValue() != 0);
            }
            return (T) Boolean.valueOf(result.toString());
        }
        throw new IllegalStateException("无法将结果转换为 " + rowType.getName() + ": " + result);
    }

    // ==================== 高级响应式 KV 操作 ====================

    /**
     * 响应式 GET 操作。
     *
     * @param key 键
     * @return 值 Mono，不存在返回空 Mono
     */
    public Mono<String> get(String key) {
        return Mono.fromCallable(() -> executeSingleCommand(defaultDataSourceName, "GET", key))
                .subscribeOn(Schedulers.boundedElastic())
                .map(r -> r != null ? r.toString() : null);
    }

    /**
     * 响应式 SET 操作（永不过期）。
     *
     * @param key   键
     * @param value 值
     * @return 完成 Mono
     */
    public Mono<Void> set(String key, String value) {
        return Mono.fromCallable(() -> executeSingleCommand(defaultDataSourceName, "SET", key, value))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 响应式 SETEX 操作（带过期时间）。
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时长（秒）
     * @return 完成 Mono
     */
    public Mono<Void> setex(String key, String value, long ttl) {
        return Mono.fromCallable(() -> {
            RedisClient client = lettuceClients.get(defaultDataSourceName);
            if (client == null) {
                throw new IllegalStateException("Redis 数据源未配置");
            }
            Duration timeout = timeouts.getOrDefault(defaultDataSourceName, Duration.ofSeconds(5));
            io.lettuce.core.api.StatefulRedisConnection<String, String> conn = client.connect();
            conn.setTimeout(timeout);
            io.lettuce.core.api.sync.RedisStringCommands<String, String> str = conn.sync();
            str.setex(key, ttl, value);
            conn.close();
            return 1;
        }).subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 响应式删除操作。
     *
     * @param key 键
     * @return 删除成功返回 Mono.TRUE
     */
    public Mono<Boolean> delete(String key) {
        return Mono.fromCallable(() -> {
            Long result = (Long) executeSingleCommand(defaultDataSourceName, "DEL", key);
            return result != null && result > 0;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式判断键是否存在。
     *
     * @param key 键
     * @return 存在返回 Mono.TRUE
     */
    public Mono<Boolean> exists(String key) {
        return Mono.fromCallable(() -> {
            Long result = (Long) executeSingleCommand(defaultDataSourceName, "EXISTS", key);
            return result != null && result > 0;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式获取键的剩余生存时间（秒）。
     *
     * @param key 键
     * @return 剩余秒数 Mono，-1 表示无过期，-2 表示键不存在
     */
    public Mono<Long> ttl(String key) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "TTL", key);
            return result instanceof Number num ? num.longValue() : -2L;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式原子递增。
     *
     * @param key 键
     * @return 递增后的值 Mono
     */
    public Mono<Long> incr(String key) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "INCR", key);
            return result instanceof Number num ? num.longValue() : 0L;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式原子递减。
     *
     * @param key 键
     * @return 递减后的值 Mono
     */
    public Mono<Long> decr(String key) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "DECR", key);
            return result instanceof Number num ? num.longValue() : 0L;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式 Hash GET。
     *
     * @param key   哈希键
     * @param field 字段名
     * @return 字段值 Mono
     */
    public Mono<String> hget(String key, String field) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "HGET", key, field);
            return result != null ? result.toString() : null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式 Hash SET。
     *
     * @param key   哈希键
     * @param field 字段名
     * @param value 字段值
     * @return 完成 Mono
     */
    public Mono<Void> hset(String key, String field, String value) {
        return Mono.fromCallable(() -> executeSingleCommand(defaultDataSourceName, "HSET", key, field, value))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 响应式 Hash GETALL，返回所有字段的 Flux。
     *
     * @param key 哈希键
     * @return 字段值对 Flux
     */
    public Flux<Map.Entry<String, String>> hgetall(String key) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "HGETALL", key);
            if (result instanceof Map map) {
                List<Map.Entry<String, String>> entries = new ArrayList<>();
                map.forEach((k, v) -> entries.add(new AbstractMap.SimpleEntry<>(k.toString(), v != null ? v.toString() : null)));
                return entries;
            }
            return Collections.emptyList();
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> Flux.fromIterable(list));
    }

    /**
     * 响应式 Key 扫描（前缀匹配），返回匹配键的 Flux。
     *
     * @param pattern 匹配模式（如 "user:*"）
     * @return 匹配的键 Flux
     */
    public Flux<String> scanKeys(String pattern) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "KEYS", pattern);
            if (result instanceof Collection<?> coll) {
                return new ArrayList<>((Collection<?>) coll);
            }
            return Collections.emptyList();
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> Flux.fromIterable(list).map(Object::toString));
    }

    /**
     * 响应式 List LPUSH。
     *
     * @param key    列表键
     * @param values 值数组
     * @return 列表长度 Mono
     */
    public Mono<Long> lpush(String key, String... values) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "LPUSH", key, (Object) values);
            return result instanceof Number num ? num.longValue() : 0L;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式 List LRANGE。
     *
     * @param key   列表键
     * @param start 起始索引
     * @param end   结束索引
     * @return 元素列表 Flux
     */
    public Flux<String> lrange(String key, long start, long end) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "LRANGE", key, start, end);
            if (result instanceof Collection<?> coll) {
                return new ArrayList<>((Collection<?>) coll);
            }
            return Collections.emptyList();
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> Flux.fromIterable(list).map(Object::toString));
    }

    /**
     * 响应式 Set SMEMBERS。
     *
     * @param key 集合键
     * @return 成员列表 Flux
     */
    public Flux<String> smembers(String key) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "SMEMBERS", key);
            if (result instanceof Collection<?> coll) {
                return new ArrayList<>((Collection<?>) coll);
            }
            return Collections.emptyList();
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> Flux.fromIterable(list).map(Object::toString));
    }

    /**
     * 响应式 Set SADD。
     *
     * @param key    集合键
     * @param values 值数组
     * @return 新增成员数 Mono
     */
    public Mono<Long> sadd(String key, String... values) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "SADD", key, (Object) values);
            return result instanceof Number num ? num.longValue() : 0L;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式执行任意 Redis 命令（原始命令字符串）。
     *
     * @param command Redis 命令字符串
     * @return 结果 Mono
     */
    public Mono<Object> execCommand(String command) {
        return Mono.fromCallable(() -> executeSingleCommand(defaultDataSourceName, command))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式批量执行命令（Pipeline 模拟）。
     *
     * @param commands 命令列表，每项为 "CMD arg1 arg2 ..." 格式
     * @return 结果列表 Flux
     */
    public Flux<Object> execBatch(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromIterable(commands)
                .map(cmd -> Mono.fromCallable(() -> executeSingleCommand(defaultDataSourceName, cmd))
                        .subscribeOn(Schedulers.boundedElastic())
                        .block())
                .filter(Objects::nonNull);
    }

    // ==================== 资源管理 ====================

    /**
     * 关闭引擎，释放所有 Lettuce 连接。
     */
    public void close() {
        for (RedisClient client : lettuceClients.values()) {
            try {
                client.shutdown();
            } catch (Exception e) {
                log.warn("关闭 Lettuce 客户端失败: {}", e.getMessage());
            }
        }
        lettuceClients.clear();
        timeouts.clear();
        defaultDataSourceName = null;
        log.info("RedisReactorEngine 已关闭");
    }

    /**
     * 设置默认超时时间。
     *
     * @param timeout 超时时长
     * @return this
     */
    public RedisReactorEngine setTimeout(Duration timeout) {
        timeouts.put(defaultDataSourceName, timeout);
        return this;
    }

    /**
     * 为指定数据源设置超时时间。
     *
     * @param name    数据源名称
     * @param timeout 超时时长
     * @return this
     */
    public RedisReactorEngine setTimeout(String name, Duration timeout) {
        timeouts.put(name, timeout);
        return this;
    }
}
