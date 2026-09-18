package com.chua.redis.support.engine;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;

/**
* 基于 redisreactorengine 的响应式 KV 工具类，提供便捷的响应式 KV 操作。
*
* <p>此类不实现 KvEngine 接口（接口为同步），而是作为响应式操作的便捷封装。
* 所有操作通过 boundedElastic 调度器执行，避免阻塞 Reactor 事件循环线程。</p>
*
* @author CH
* @since 4.0.0.43
 */
public class RedisReactiveKvEngine {

    /**
    * 底层响应式引擎
    */
    private final RedisReactorEngine engine;

    /**
    * 使用指定引擎构造响应式 KV 引擎。
    *
    * @param engine 底层 redisreactorengine 实例
    */
    public RedisReactiveKvEngine(RedisReactorEngine engine) {
        this.engine = engine;
    }

    /**
    * 响应式获取键值。
    *
    * @param key 键
    * @return 值 Mono，不存在返回空 Mono
    */
    public Mono<String> get(String key) {
        return engine.get(key);
    }

    /**
    * 响应式写入键值对（永不过期）。
    *
    * @param key   键
    * @param value 值
    * @return 完成 Mono
    */
    public Mono<Void> set(String key, String value) {
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
    public Mono<Void> setex(String key, String value, long ttl) {
        return engine.setex(key, value, ttl);
    }

    /**
    * 响应式判断键是否存在。
    *
    * @param key 键
    * @return 存在返回 Mono.TRUE
    */
    public Mono<Boolean> exists(String key) {
        return engine.exists(key);
    }

    /**
    * 响应式删除键。
    *
    * @param key 键
    * @return 删除成功返回 Mono.TRUE
    */
    public Mono<Boolean> delete(String key) {
        return engine.delete(key);
    }

    /**
    * 响应式原子递增。
    *
    * @param key 键
    * @return 递增后的值 Mono
    */
    public Mono<Long> incr(String key) {
        return engine.incr(key);
    }

    /**
    * 响应式原子递减。
    *
    * @param key 键
    * @return 递减后的值 Mono
    */
    public Mono<Long> decr(String key) {
        return engine.decr(key);
    }

    /**
    * 响应式获取键的剩余生存时间（秒）。
    *
    * @param key 键
    * @return 剩余秒数 Mono，-1 表示无过期，-2 表示键不存在
    */
    public Mono<Long> ttl(String key) {
        return engine.ttl(key);
    }

    /**
    * 响应式 哈希 获取。
    *
    * @param key   哈希键
    * @param field 字段名
    * @return 字段值 Mono
    */
    public Mono<String> hget(String key, String field) {
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
    public Mono<Void> hset(String key, String field, String value) {
        return engine.hset(key, field, value);
    }

    /**
    * 响应式 哈希 GETALL，返回所有字段的 Flux。
    *
    * @param key 哈希键
    * @return 字段值对 Flux
    */
    public Flux<Map.Entry<String, String>> hgetall(String key) {
        return engine.hgetall(key);
    }

    /**
    * 响应式 键 扫描（前缀匹配），返回匹配键的 Flux。
    *
    * @param pattern 匹配模式（如 "用户:*"）
    * @return 匹配的键 Flux
    */
    public Flux<String> scanKeys(String pattern) {
        return engine.scanKeys(pattern);
    }

    /**
    * 响应式前缀扫描并获取值，返回匹配键值对的 Flux。
    *
    * @param prefix 键前缀（如 "用户:"）
    * @return 匹配键值对 Flux
    */
    public Flux<Map.Entry<String, String>> findAllByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Flux.empty();
        }
        return engine.scanKeys(prefix + "*")
                .flatMap(key -> engine.get(key)
                        .map(value -> new AbstractMap.SimpleEntry<>(key, value != null ? value : ""))
                        .onErrorResume(e -> Mono.empty()));
    }

    /**
    * 响应式 列表 LPUSH。
    *
    * @param key    列表键
    * @param values 值数组
    * @return 列表长度 Mono
    */
    public Mono<Long> lpush(String key, String... values) {
        return engine.lpush(key, values);
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
        return engine.lrange(key, start, end);
    }

    /**
    * 响应式 设置 SMEMBERS。
    *
    * @param key 集合键
    * @return 成员列表 Flux
    */
    public Flux<String> smembers(String key) {
        return engine.smembers(key);
    }

    /**
    * 响应式 设置 SADD。
    *
    * @param key    集合键
    * @param values 值数组
    * @return 新增成员数 Mono
    */
    public Mono<Long> sadd(String key, String... values) {
        return engine.sadd(key, values);
    }

    /**
    * 响应式执行任意 Redis 命令。
    *
    * @param command Redis 命令字符串
    * @return 结果 Mono
    */
    public Mono<Object> execCommand(String command) {
        return engine.execCommand(command);
    }

    /**
    * 响应式批量执行命令。
    *
    * @param commands 命令列表
    * @return 结果列表 Flux
    */
    public Flux<Object> execBatch(List<String> commands) {
        return engine.execBatch(commands);
    }
}
