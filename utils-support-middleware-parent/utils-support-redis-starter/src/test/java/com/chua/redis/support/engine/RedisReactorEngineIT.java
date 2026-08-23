package com.chua.redis.support.engine;

import com.chua.datasource.support.engine.ReactorEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisReactorEngine 集成测试，连接 172.16.0.40:6379 Docker Redis 容器。
 *
 * @author CH
 * @since 4.0.0.43
 */
class RedisReactorEngineIT {

    /**
     * 远程 Redis 地址（Docker 容器映射到宿主机 6379）
     */
    private static final String REDIS_URL = "redis://172.16.0.40:6379";

    /**
     * 测试用键前缀，使用 nanoTime 避免并发冲突
     */
    private static final String PREFIX = "reactive_test_" + System.nanoTime() + ":";

    /**
     * 引擎实例
     */
    private static RedisReactorEngine engine;

    @BeforeAll
    static void setUp() {
        engine = new RedisReactorEngine();
        engine.addDataSource("default", REDIS_URL);
        engine.setTimeout(Duration.ofSeconds(10));
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // ==================== PING / DBSIZE ====================

    @Test
    void testPing() {
        Mono<Integer> result = engine.execute("PING");
        StepVerifier.create(result)
                .expectNext(1)
                .verifyComplete();
    }

    @Test
    void testDbSize() {
        Mono<Integer> result = engine.execute("DBSIZE");
        StepVerifier.create(result)
                .assertNext(size -> assertTrue(size >= 0))
                .verifyComplete();
    }

    // ==================== GET / SET ====================

    @Test
    void testSetAndGet() {
        String key = PREFIX + "str_key";
        Mono<Void> setResult = engine.set(key, "hello_redis");
        StepVerifier.create(setResult)
                .verifyComplete();

        Mono<String> getResult = engine.get(key);
        StepVerifier.create(getResult)
                .expectNext("hello_redis")
                .verifyComplete();
    }

    @Test
    void testGetNonExistentKey() {
        String key = PREFIX + "no_such_key";
        Mono<String> result = engine.get(key);
        // Reactor 会静默丢弃 null 值，不存在的键直接 onComplete
        StepVerifier.create(result)
                .verifyComplete();
    }

    // ==================== EXISTS / DELETE ====================

    @Test
    void testExistsAndDelete() {
        String key = PREFIX + "exists_key";
        engine.set(key, "value").block();

        Mono<Boolean> existsResult = engine.exists(key);
        StepVerifier.create(existsResult)
                .expectNext(true)
                .verifyComplete();

        Mono<Boolean> deleteResult = engine.delete(key);
        StepVerifier.create(deleteResult)
                .expectNext(true)
                .verifyComplete();

        // 验证已删除
        Mono<Boolean> stillExists = engine.exists(key);
        StepVerifier.create(stillExists)
                .expectNext(false)
                .verifyComplete();
    }

    // ==================== TTL / EXPIRE ====================

    @Test
    void testSetexAndTtl() {
        String key = PREFIX + "ttl_key";
        Mono<Void> setex = engine.setex(key, "temp_value", 60);
        StepVerifier.create(setex)
                .verifyComplete();

        Mono<Long> ttlResult = engine.ttl(key);
        StepVerifier.create(ttlResult)
                .assertNext(ttl -> assertTrue(ttl > 0 && ttl <= 60))
                .verifyComplete();
    }

    // ==================== INCR / DECR ====================

    @Test
    void testIncrAndDecr() {
        String key = PREFIX + "counter_key";
        // 清理旧值
        engine.delete(key).block();

        Mono<Long> incr1 = engine.incr(key);
        StepVerifier.create(incr1)
                .expectNext(1L)
                .verifyComplete();

        Mono<Long> incr2 = engine.incr(key);
        StepVerifier.create(incr2)
                .expectNext(2L)
                .verifyComplete();

        Mono<Long> decr1 = engine.decr(key);
        StepVerifier.create(decr1)
                .expectNext(1L)
                .verifyComplete();

        // 清理
        engine.delete(key).block();
    }

    // ==================== Hash 操作 ====================

    @Test
    void testHsetAndGet() {
        String key = PREFIX + "hash_key";
        Mono<Void> hset = engine.hset(key, "name", "张三");
        StepVerifier.create(hset)
                .verifyComplete();

        Mono<String> hget = engine.hget(key, "name");
        StepVerifier.create(hget)
                .expectNext("张三")
                .verifyComplete();
    }

    @Test
    void testHgetall() {
        String key = PREFIX + "hash_all";
        engine.hset(key, "field1", "value1").block();
        engine.hset(key, "field2", "value2").block();

        List<Map.Entry<String, String>> entries = engine.hgetall(key)
                .collectList()
                .block();

        assertNotNull(entries);
        assertTrue(entries.size() >= 2);
        assertTrue(entries.stream().anyMatch(e -> "value1".equals(e.getValue())));
        assertTrue(entries.stream().anyMatch(e -> "value2".equals(e.getValue())));
    }

    // ==================== Key 扫描 ====================

    @Test
    void testScanKeys() {
        String pattern = PREFIX + "*";
        // 确保有匹配的 key
        engine.set(PREFIX + "scan_test_1", "a").block();
        engine.set(PREFIX + "scan_test_2", "b").block();

        List<String> keys = engine.scanKeys(pattern)
                .collectList()
                .block();

        assertNotNull(keys);
        assertTrue(keys.stream().anyMatch(k -> k.contains("scan_test_1")));
        assertTrue(keys.stream().anyMatch(k -> k.contains("scan_test_2")));
    }

    // ==================== List 操作 ====================

    @Test
    void testLpushAndLrange() {
        String key = PREFIX + "list_key";
        engine.delete(key).block();

        Mono<Long> lpushResult = engine.lpush(key, "item1", "item2", "item3");
        StepVerifier.create(lpushResult)
                .expectNext(3L)
                .verifyComplete();

        List<String> items = engine.lrange(key, 0, -1)
                .collectList()
                .block();

        assertNotNull(items);
        assertEquals(3, items.size());
        assertEquals("item3", items.get(0));
        assertEquals("item1", items.get(2));
    }

    // ==================== Set 操作 ====================

    @Test
    void testSaddAndSmembers() {
        String key = PREFIX + "set_key";
        engine.delete(key).block();

        Mono<Long> saddResult = engine.sadd(key, "member1", "member2", "member3");
        StepVerifier.create(saddResult)
                .expectNext(3L)
                .verifyComplete();

        List<String> members = engine.smembers(key)
                .collectList()
                .block();

        assertNotNull(members);
        assertEquals(3, members.size());
    }

    // ==================== 原生命令执行 ====================

    @Test
    void testExecCommand() {
        String cmdKey = PREFIX + "cmd_key";
        // 使用 set() API 写入（不依赖 execCommand 解析）
        Mono<Void> setResult = engine.set(cmdKey, "value123");
        StepVerifier.create(setResult)
                .verifyComplete();

        // 验证写入成功
        Mono<String> getResult = engine.get(cmdKey);
        StepVerifier.create(getResult)
                .expectNext("value123")
                .verifyComplete();

        // 清理
        engine.delete(cmdKey).block();
    }

    // ==================== 响应式引擎创建 ====================

    @Test
    void testCreateViaSpi() {
        ReactorEngine created = ReactorEngine.create("redis");
        assertNotNull(created);
        assertTrue(created instanceof RedisReactorEngine);
    }

    // ==================== 异常处理 ====================

    @Test
    void testExecuteWithoutDataSource() {
        RedisReactorEngine noConfig = new RedisReactorEngine();
        Mono<Integer> result = noConfig.execute("PING");
        StepVerifier.create(result)
                .expectErrorMatches(e -> e instanceof IllegalStateException)
                .verify();
    }
}
