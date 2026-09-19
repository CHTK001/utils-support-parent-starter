package com.chua.redis.support.engine;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.ReactorEngine;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Lettuce 的 Redis 响应式引擎，实现 reactorengine 接口。
 *
 * <p>不依赖 R2DBC，完全基于 Lettuce 的响应式 API 实现。
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
 *
 * // 前缀扫描
 * Flux<String> keys = engine.scanKeys("user:*");
 * }</pre>scanKeys("user:*");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SuppressWarnings("rawtypes")
@ Spi("redis")
/**
 * RedisReactor引擎类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class RedisReactorEngine implements ReactorEngine {

    /**
     * Redis 连接 URL 协议前缀
     */
    private static final String REDIS_PREFIX = "redis://";

    /**
     * 数据源名称 -> Lettuce redis客户端
     */
    private final Map<String, RedisClient> lettuceClients = new ConcurrentHashMap<>();

    /**
     * 数据源名称 -> 超时时间
     */
    private final Map<String, Duration> timeouts = new ConcurrentHashMap<>();

    /**
     * 数据源名称 -> 共享 Lettuce 连接（Lettuce 连接线程安全，随命令复用，避免每命令建连）
     */
    private final Map<String, StatefulRedisConnection<String, String>> connections = new ConcurrentHashMap<>();

    /**
     * 默认命令超时
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

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
     * @param redisUrl Redis 连接 URL（如 redis://127.0.0.1:6379，可带 /db 路径与 rediss:// 协议）
     * @return this
     */
    public RedisReactorEngine addDataSource(String name, String redisUrl) {
        return addDataSource(name, redisUrl, null);
    }

    /**
     * 添加 Redis 数据源（带认证）。
     *
     * @param name     数据源名称
     * @param redisUrl Redis 连接 URL
     * @param password 密码，可为 空（无认证）
     * @return this
     */
    public RedisReactorEngine addDataSource(String name, String redisUrl, String password) {
        if (redisUrl == null || redisUrl.isEmpty()) {
            throw new IllegalArgumentException("Redis URL cannot be null or empty");
        }
        String url = attachPassword(normalizeUrl(redisUrl), password);
        lettuceClients.put(name, RedisClient.create(url));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        log.info("Redis 数据源已添加: name={}, url={}", name, maskUrl(url));
        return this;
    }

    /**
     * 补全协议前缀（已是 redis:// / rediss:// / unix:// 的不动，不强制附加数据库段）。
     *
     * @param redisUrl 原始 URL
     * @return 规范化 URL
     */
    private static String normalizeUrl(String redisUrl) {
        String url = redisUrl.trim();
        if (url.startsWith("redis://") || url.startsWith("rediss://") || url.startsWith("unix://")) {
            return url;
        }
        return REDIS_PREFIX + url;
    }

    /**
     * 把独立传入的密码按 userinfo 形式并入 URL（密码做百分号编码，避免正则/特殊字符破坏 URL）。
     * <p>URL 已自带密码段（{@code :pwd@}）时以 URL 为准，不覆盖。</p>
     *
     * @param url      规范化 URL
     * @param password 密码，可为 空
     * @return 含凭据的 URL
     */
    private static String attachPassword(String url, String password) {
        if (password == null || password.isEmpty()) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        String prefix = url.substring(0, schemeEnd + 3);
        String rest = url.substring(schemeEnd + 3);
        int pathStart = rest.indexOf('/');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        String tail = pathStart < 0 ? "" : rest.substring(pathStart);
        int at = authority.indexOf('@');
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            if (userInfo.indexOf(':') >= 0 || userInfo.isEmpty()) {
                // URL 已带密码段，URL 优先
                return url;
            }
            return prefix + userInfo + ":" + encodePassword(password) + "@" + authority.substring(at + 1) + tail;
        }
        return prefix + ":" + encodePassword(password) + "@" + authority + tail;
    }

    /**
     * 密码百分号编码（URLEncoder 的 + 号语义不适用 userinfo，替换为 %20）。
     *
     * @param password 原始密码
     * @return 编码后密码
     */
    private static String encodePassword(String password) {
        return java.net.URLEncoder.encode(password, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 掩码 URL 中的凭据段用于日志输出。
     *
     * @param url 含凭据的 URL
     * @return 密码替换为 *** 的 URL
     */
    private static String maskUrl(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        String prefix = url.substring(0, schemeEnd + 3);
        String rest = url.substring(schemeEnd + 3);
        int pathStart = rest.indexOf('/');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        String tail = pathStart < 0 ? "" : rest.substring(pathStart);
        int at = authority.indexOf('@');
        if (at < 0) {
            return url;
        }
        String userInfo = authority.substring(0, at);
        int colon = userInfo.indexOf(':');
        String masked = colon < 0 ? userInfo : userInfo.substring(0, colon) + ":***";
        return prefix + masked + "@" + authority.substring(at + 1) + tail;
    }

    /**
     * 取数据源的共享命令句柄（懒建连，连接线程安全可跨命令复用）。
     *
     * @param name 数据源名称
     * @return 同步命令句柄
     */
    private RedisCommands<String, String> commands(String name) {
        StatefulRedisConnection<String, String> conn = connections.compute(name, (k, existing) -> {
            if (existing != null && existing.isOpen()) {
                return existing;
            }
            RedisClient client = lettuceClients.get(k);
            if (client == null) {
                throw new IllegalStateException("Redis 数据源未配置: " + k);
            }
            StatefulRedisConnection<String, String> created = client.connect();
            created.setTimeout(timeouts.getOrDefault(k, DEFAULT_TIMEOUT));
            return created;
        });
        return conn.sync();
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
     * 获取指定数据源的 Lettuce redis客户端。
     *
     * @param name 数据源名称
     * @return Lettuce Redis客户端，未配置返回 空
     */
    public RedisClient getLettuceClient(String name) {
        return lettuceClients.get(name);
    }

    // ==================== ReactorEngine 接口实现 ====================

    /**
     * Redis 不支持 SQL Lambda 查询，抛出 unsupportedoperation异常。
     */
    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException("Redis 不支持 SQL Lambda 查询，请使用 get()/execute() 等响应式方法");
    }

    /**
     * Redis 不支持 SQL Lambda 更新，抛出 unsupportedoperation异常。
     */
    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException("Redis 不支持 SQL Lambda 更新，请使用 set()/hset() 等响应式方法");
    }

    /**
     * Redis 不支持 SQL Lambda 删除，抛出 unsupportedoperation异常。
     */
    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException("Redis 不支持 SQL Lambda 删除，请使用 delete() 等响应式方法");
    }

    /**
     * 响应式执行任意 Redis 命令，返回单行 映射 结果（列名 -> 值）。
     *
     * <p>支持命令：GET、SET、DEL、EXISTS、TTL、INCR、LPUSH、RPUSH、LPOP、RPOP、
     * HGET、HSET、HGETALL、SMEMBERS、ZADD、ZRANGE 等。</p>
     *
     * @param sql    Redis 命令（大写，如 "获取 mykey"）
     * @param params 命令参数
     * @return 结果行 Flux，单条记录
     */
    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        String name = defaultDataSourceName;
        if (name == null) {
            return Flux.error(new IllegalStateException("未配置 Redis 数据源"));
        }
        try {
            Object result = executeSingleCommand(name, sql, params);
            if (result == null) {
                return Flux.empty();
            }
            return Flux.just(Collections.singletonMap("value", result));
        } catch (Exception e) {
            return Flux.error(e);
        }
    }

    /**
     * 响应式执行任意 Redis 命令并映射为类型化对象。
     *
     * @param sql     Redis 命令
     * @param rowType 目标类型（仅支持 字符串/Long/Integer/布尔值）
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
        try {
            Object result = executeSingleCommand(name, sql, params);
            if (result == null) {
                return Flux.empty();
            }
            return Flux.just(convertTo(result, rowType));
        } catch (Exception e) {
            return Flux.error(e);
        }
    }

    /**
     * 响应式执行 Redis 写操作命令。
     *
     * <p>支持命令：SET、DEL、INCR、DECR、LPUSH、RPUSH、HSET、HDEL、SADD、ZADD 等。</p>
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
            return toAffectedRows(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 命令结果折算受影响行数：数值取自身，布尔真为 1，其余非空结果为 1，空结果为 0。
     *
     * @param result 原始结果
     * @return 行数
     */
    private static int toAffectedRows(Object result) {
        if (result instanceof Number num) {
            return num.intValue();
        }
        if (result instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return result != null ? 1 : 0;
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
                .map(params -> toAffectedRows(executeSingleCommand(name, sql, params)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== 底层执行方法 ====================

    /**
     * 解析并执行单条 Redis 命令，返回原始结果对象。
     *
     * <p>支持两种调用模式：</p>
     * <ul>
     *   <li>直接调用（结构化）：{@code executeSingleCommand(name, "GET", "key")} — command为命令名，params为参数</li>
     *   <li>通过 execute()/execCommand()：{@code executeSingleCommand(name, "GET mykey")} — command为完整命令字符串</li>
     * </ul>
     *
     * @param name    数据源名称
     * @param command 命令字符串（如 "获取" 或 "获取 mykey"）
     * @param params  额外参数（结构化调用时使用）
     * @return 命令执行结果
     */
    @SuppressWarnings({"unchecked"})
    private Object executeSingleCommand(String name, String command, Object... params) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis 命令为空");
        }
        String[] parts = command.trim().split("\\s+");
 // 仅命令名大写；键值保持原样（Redis 键大小写敏感）
        String cmd = parts[0].toUpperCase(Locale.ROOT);
        List<String> argList = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            argList.add(parts[i]);
        }
        if (params != null) {
            for (Object p : params) {
                argList.add(p == null ? "" : p.toString());
            }
        }
        String[] args = argList.toArray(new String[0]);
        requireArgs(cmd, args);
        RedisCommands<String, String> conn = commands(name);
        try {
            Object result;
            switch (cmd) {
                case "GET":
                    result = conn.get(args[0]);
                    break;
                case "SET":
                    conn.set(args[0], args.length > 1 ? args[1] : "");
                    result = "OK";
                    break;
                case "DEL":
                    result = conn.del(args);
                    break;
                case "EXISTS":
                    result = conn.exists(args);
                    break;
                case "TTL":
                    result = conn.ttl(args[0]);
                    break;
                case "PTTL":
                    result = conn.pttl(args[0]);
                    break;
                case "INCR":
                    result = conn.incr(args[0]);
                    break;
                case "INCRBY":
                    result = conn.incrby(args[0], parseLong(Converter.convertIfNecessary(args[1], Long.class), args[1]));
                    break;
                case "DECR":
                    result = conn.decr(args[0]);
                    break;
                case "DECRBY":
                    result = conn.decrby(args[0], parseLong(Converter.convertIfNecessary(args[1], Long.class), args[1]));
                    break;
                case "APPEND":
                    result = conn.append(args[0], args[1]);
                    break;
                case "STRLEN":
                    result = conn.strlen(args[0]);
                    break;
                case "LPUSH":
                    result = conn.lpush(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "RPUSH":
                    result = conn.rpush(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "LPOP":
                    result = conn.lpop(args[0]);
                    break;
                case "RPOP":
                    result = conn.rpop(args[0]);
                    break;
                case "LRANGE":
                    String lrangeKey = args[0];
                    long lStartIdx = parseLong(Converter.convertIfNecessary(args[1], Long.class), args[1]);
                    long lEndIdx = parseLong(Converter.convertIfNecessary(args[2], Long.class), args[2]);
                    result = conn.lrange(lrangeKey, lStartIdx, lEndIdx);
                    break;
                case "LLEN":
                    result = conn.llen(args[0]);
                    break;
                case "HGET":
                    result = conn.hget(args[0], args[1]);
                    break;
                case "HSET":
                    result = conn.hset(args[0], args[1], args[2]);
                    break;
                case "HDEL":
                    result = conn.hdel(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "HGETALL":
                    result = conn.hgetall(args[0]);
                    break;
                case "HEXISTS":
                    result = conn.hexists(args[0], args[1]);
                    break;
                case "HLEN":
                    result = conn.hlen(args[0]);
                    break;
                case "SADD":
                    result = conn.sadd(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "SMEMBERS":
                    result = new ArrayList<>(conn.smembers(args[0]));
                    break;
                case "SREM":
                    result = conn.srem(args[0], Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "SCARD":
                    result = conn.scard(args[0]);
                    break;
                case "ZADD":
                    double score = parseDouble(Converter.convertIfNecessary(args[1], Double.class), args[1]);
                    result = conn.zadd(args[0], score, args[2]);
                    break;
                case "ZRANGE":
                    long zStart = parseLong(Converter.convertIfNecessary(args[1], Long.class), args[1]);
                    long zEnd = parseLong(Converter.convertIfNecessary(args[2], Long.class), args[2]);
                    result = conn.zrange(args[0], zStart, zEnd);
                    break;
                case "ZCARD":
                    result = conn.zcard(args[0]);
                    break;
                case "KEYS":
                    result = new ArrayList<>(conn.keys(args[0]));
                    break;
                case "DBSIZE":
                    result = conn.dbsize();
                    break;
                case "PING":
                    result = conn.ping();
                    break;
                case "SETEX":
                    conn.setex(args[0], parseLong(Converter.convertIfNecessary(args[1], Long.class), args[1]), args[2]);
                    result = "OK";
                    break;
                case "EXPIRE":
                    result = conn.expire(args[0], parseLong(Converter.convertIfNecessary(args[1], Long.class), args[1]));
                    break;
                case "FLUSHDB":
                    conn.flushdb();
                    result = 1;
                    break;
                case "SELECT":
                    int db = parseInt(Converter.convertIfNecessary(args[0], Integer.class), args[0]);
                    conn.select(db);
                    result = 1;
                    break;
                case "AUTH":
                    if (args.length > 0) {
                        conn.auth(args[0]);
                    }
                    result = "OK";
                    break;
                default:
                    throw new IllegalArgumentException("不支持的 Redis 命令: " + cmd);
            }
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
 // 不打印命令参数，避免 AUTH/SETEX 等敏感值进日志
            log.error("执行 Redis 命令失败: cmd={}", cmd, e);
            throw new IllegalStateException("Redis 命令执行失败: " + cmd, e);
        }
    }

    /**
     * 各支持命令的最少参数数（未列出的命令只校验命令合法）
     */
    private static final Map<String, Integer> MIN_ARGS = Map.ofEntries(
            Map.entry("GET", 1), Map.entry("SET", 1), Map.entry("SETEX", 3), Map.entry("DEL", 1),
            Map.entry("EXISTS", 1), Map.entry("TTL", 1), Map.entry("PTTL", 1), Map.entry("EXPIRE", 2),
            Map.entry("INCR", 1), Map.entry("INCRBY", 2), Map.entry("DECR", 1), Map.entry("DECRBY", 2),
            Map.entry("APPEND", 2), Map.entry("STRLEN", 1), Map.entry("LPUSH", 2), Map.entry("RPUSH", 2),
            Map.entry("LPOP", 1), Map.entry("RPOP", 1), Map.entry("LRANGE", 3), Map.entry("LLEN", 1),
            Map.entry("HGET", 2), Map.entry("HSET", 3), Map.entry("HDEL", 2), Map.entry("HGETALL", 1),
            Map.entry("HEXISTS", 2), Map.entry("HLEN", 1), Map.entry("SADD", 2), Map.entry("SMEMBERS", 1),
            Map.entry("SREM", 2), Map.entry("SCARD", 1), Map.entry("ZADD", 3), Map.entry("ZRANGE", 3),
            Map.entry("ZCARD", 1), Map.entry("KEYS", 1), Map.entry("SELECT", 1), Map.entry("AUTH", 1));

    /**
     * 校验命令参数数量，不足时显式抛（避免深层数组越界异常）。
     *
     * @param cmd  命令名（大写）
     * @param args 参数数组
     */
    private static void requireArgs(String cmd, String[] args) {
        int min = MIN_ARGS.getOrDefault(cmd, 0);
        if (args.length < min) {
            throw new IllegalArgumentException("Redis 命令 " + cmd + " 至少需要 " + min
                    + " 个参数，实际 " + args.length);
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
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(result, rowType);
        if (converted != null) {
            return (T) converted;
        }
        throw new IllegalStateException("无法将结果转换为 " + rowType.getName() + ": " + result);
    }

    // ==================== 高级响应式 KV 操作 ====================

    /**
     * 响应式 获取 操作。
     *
     * @param key 键
     * @return 值 Mono，不存在返回空 Mono
     */
    public Mono<String> get(String key) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "GET", key);
            return result != null ? result.toString() : null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式 设置 操作（永不过期）。
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
        return Mono.fromCallable(() -> executeSingleCommand(defaultDataSourceName, "SETEX", key, ttl, value))
                .subscribeOn(Schedulers.boundedElastic())
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
            Object result = executeSingleCommand(defaultDataSourceName, "DEL", key);
            return result instanceof Number num ? num.longValue() > 0 : false;
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
            Object result = executeSingleCommand(defaultDataSourceName, "EXISTS", key);
            return result instanceof Number num ? num.longValue() > 0 : false;
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
     * 响应式 EXPIRE 操作（为已存在键设置过期时间，不改动原值）。
     *
     * @param key     键
     * @param seconds 过期秒数
     * @return 设置成功（键存在）返回 Mono.TRUE
     */
    public Mono<Boolean> expire(String key, long seconds) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "EXPIRE", key, seconds);
            if (result instanceof Boolean bool) {
                return bool;
            }
            return result instanceof Number num && num.longValue() > 0;
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
     * 响应式 哈希 获取。
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
     * 响应式 哈希 设置。
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
     * 响应式 哈希 GETALL，返回所有字段的 Flux。
     *
     * @param key 哈希键
     * @return 字段值对 Flux
     */
    public Flux<Map.Entry<String, String>> hgetall(String key) {
        return Mono.fromCallable(() -> {
            Object result = executeSingleCommand(defaultDataSourceName, "HGETALL", key);
            if (result instanceof Map<?, ?> map) {
                List<Map.Entry<String, String>> entries = new ArrayList<>();
                map.forEach((k, v) -> entries.add(new AbstractMap.SimpleEntry<>(
                        k.toString(), v != null ? v.toString() : null)));
                return entries;
            }
            return Collections.emptyList();
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> {
                    @SuppressWarnings("unchecked")
                    List<Map.Entry<String, String>> typedList = (List) list;
                    return Flux.fromIterable(typedList);
                });
    }

    /**
     * 响应式 键 扫描（SCAN 游标 增量 遍历，避免 KEYS 阻塞 服务端）。
     *
     * @param pattern 匹配模式（如 "user:*"，空 表示 全 量）
     * @return 匹配的键 Flux
     */
    public Flux<String> scanKeys(String pattern) {
        return Mono.fromCallable(() -> {
            RedisCommands<String, String> conn = commands(defaultDataSourceName);
            ScanArgs scanArgs = pattern == null || pattern.isEmpty()
                    ? ScanArgs.Builder.limit(500)
                    : ScanArgs.Builder.matches(pattern).limit(500);
            List<String> keys = new ArrayList<>();
            KeyScanCursor<String> cursor = conn.scan(scanArgs);
            while (true) {
                keys.addAll(cursor.getKeys());
                if (cursor.isFinished()) {
                    break;
                }
                cursor = conn.scan(cursor, scanArgs);
            }
            return keys;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * 响应式 列表 LPUSH。
     *
     * @param key    列表键
     * @param values 值数组
     * @return 列表长度 Mono
     */
    public Mono<Long> lpush(String key, String... values) {
        return Mono.fromCallable(() ->
                commands(defaultDataSourceName).lpush(key, values))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 响应式 列表 LRANGE。
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
     * 响应式 设置 SMEMBERS。
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
     * 响应式 设置 SADD。
     *
     * @param key    集合键
     * @param values 值数组
     * @return 新增成员数 Mono
     */
    public Mono<Long> sadd(String key, String... values) {
        return Mono.fromCallable(() ->
                commands(defaultDataSourceName).sadd(key, values))
                .subscribeOn(Schedulers.boundedElastic());
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
     * @param commands 命令列表，每项为 "CMD 参数1 参数2 ..." 格式
     * @return 结果列表 Flux
     */
    public Flux<Object> execBatch(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromIterable(commands)
                .flatMap(cmd -> Mono.fromCallable(() -> executeSingleCommand(defaultDataSourceName, cmd))
                        .subscribeOn(Schedulers.boundedElastic()))
                .filter(Objects::nonNull);
    }

    // ==================== 资源管理 ====================

    /**
     * 关闭引擎，释放所有共享连接与 Lettuce 客户端。
     */
    public void close() {
        for (StatefulRedisConnection<String, String> conn : connections.values()) {
            try {
                conn.close();
            } catch (Exception e) {
                log.warn("关闭 Redis 共享连接失败: {}", e.getMessage());
            }
        }
        connections.clear();
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
     * 设置默认数据源超时时间（已建立的共享连接同步生效）。
     *
     * @param timeout 超时时长
     * @return this
     */
    public RedisReactorEngine setTimeout(Duration timeout) {
        String name = defaultDataSourceName;
        if (name == null) {
            throw new IllegalStateException("未配置 Redis 数据源");
        }
        return setTimeout(name, timeout);
    }

    /**
     * 为指定数据源设置超时时间（已建立的共享连接同步生效）。
     *
     * @param name    数据源名称
     * @param timeout 超时时长
     * @return this
     */
    public RedisReactorEngine setTimeout(String name, Duration timeout) {
        timeouts.put(name, timeout);
        StatefulRedisConnection<String, String> conn = connections.get(name);
        if (conn != null && conn.isOpen()) {
            conn.setTimeout(timeout);
        }
        return this;
    }

    // ==================== 类型转换辅助方法 ====================

    /**
     * 使用 转换器 转换字符串为 Long，转换失败时兜底 解析long。
     *
     * @param converted 转换结果（可能为 空）
     * @param raw       原始字符串
     * @return Long 值
     */
    private static long parseLong(Long converted, String raw) {
        if (converted != null) {
            return converted;
        }
        return Long.parseLong(raw);
    }

    /**
     * 使用 转换器 转换字符串为 Integer，转换失败时兜底 解析int。
     *
     * @param converted 转换结果（可能为 空）
     * @param raw       原始字符串
     * @return Integer 值
     */
    private static int parseInt(Integer converted, String raw) {
        if (converted != null) {
            return converted;
        }
        return Integer.parseInt(raw);
    }

    /**
     * 使用 转换器 转换字符串为 Double，转换失败时兜底 解析double。
     *
     * @param converted 转换结果（可能为 空）
     * @param raw       原始字符串
     * @return Double 值
     */
    private static double parseDouble(Double converted, String raw) {
        if (converted != null) {
            return converted;
        }
        return Double.parseDouble(raw);
    }
}
