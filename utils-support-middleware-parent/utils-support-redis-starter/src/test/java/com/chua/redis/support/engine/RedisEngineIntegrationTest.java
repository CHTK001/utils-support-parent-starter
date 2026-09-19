package com.chua.redis.support.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Redis 引擎集成测试（真实连接）。
 *
 * <p>基于 Testcontainers：自动拉起 {@code redis:7-alpine} 容器作为真实 Redis，
 * 无需外部服务与环境变量，验证以下能力：</p>
 * <ul>
 *   <li>execute 命令分发：SET / GET / DEL / INCR / HSET / EXPIRE</li>
 *   <li>未知命令抛 {@link UnsupportedOperationException}</li>
 *   <li>getPoolPublic 获取 JedisPool 直连验证底层数据</li>
 * </ul>
 *
 * <p>需要本机 Docker 环境；容器随测试类启动、结束后自动回收。</p>
 *
 * @author CH
 */
class RedisEngineIntegrationTest {

    /**
     * 真实 Redis 容器（7-alpine）
    */
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * 被测引擎
    */
    private RedisEngine engine;

    /**
     * 启动容器。
     */
    @BeforeAll
    static void startContainer() {
        REDIS.start();
    }

    /**
     * 回收容器。
     */
    @AfterAll
    static void stopContainer() {
        REDIS.stop();
    }

    /**
     * 初始化引擎并注册数据源。
     */
    @BeforeEach
    void setUp() {
        engine = new RedisEngine();
        engine.addDataSource("default", REDIS.getHost(), REDIS.getFirstMappedPort());
    }

    /**
     * 释放连接池。
     */
    @AfterEach
    void tearDown() {
        engine.close();
    }

    /**
     * 验证 SET 命令返回 1，且底层真实可读。
     */
    @Test
    void testSetAndGet() {
        assertEquals(1, engine.execute("SET itest:name Alice"));
        assertEquals(1, engine.execute("GET itest:name"));
        // 不存在的键命中 0
        assertEquals(0, engine.execute("GET itest:not_exist"));
    }

    /**
     * 验证 DEL 命令返回删除键数，删除后 GET 命中 0。
     */
    @Test
    void testDel() {
        engine.execute("SET itest:tmp hello");
        assertEquals(1, engine.execute("DEL itest:tmp"));
        assertEquals(0, engine.execute("GET itest:tmp"));
        // 删除不存在的键返回 0
        assertEquals(0, engine.execute("DEL itest:not_exist"));
    }

    /**
     * 验证 INCR 命令返回递增后的新值。
     */
    @Test
    void testIncr() {
        engine.execute("SET itest:counter 1");
        assertEquals(2, engine.execute("INCR itest:counter"));
        assertEquals(3, engine.execute("INCR itest:counter"));
    }

    /**
     * 验证 HSET 命令返回 hset 结果。
     */
    @Test
    void testHset() {
        assertEquals(1, engine.execute("HSET itest:hash name Alice"));
        assertEquals(1, engine.execute("HSET itest:hash age 30"));
    }

    /**
     * 验证 EXPIRE 命令返回设置结果。
     */
    @Test
    void testExpire() {
        engine.execute("SET itest:ttl value");
        assertEquals(1, engine.execute("EXPIRE itest:ttl 100"));
        // 不存在的键 EXPIRE 返回 0
        assertEquals(0, engine.execute("EXPIRE itest:not_exist 100"));
    }

    /**
     * 验证未知命令抛 UnsupportedOperationException。
     */
    @Test
    void testUnknownCommand() {
        assertThrows(UnsupportedOperationException.class, () -> engine.execute("LPUSH itest:list a"));
    }

    /**
     * 验证 getPoolPublic 获取 JedisPool 可直连底层（ping + 取值）。
     */
    @Test
    void testJedisPoolDirect() {
        engine.execute("SET itest:direct hello");
        JedisPool pool = engine.getPoolPublic("default");
        try (Jedis jedis = pool.getResource()) {
            assertEquals("PONG", jedis.ping());
            assertEquals("hello", jedis.get("itest:direct"));
        }
    }

    /**
     * 验证 execute 的额外 params 会追加到命令尾部。
     */
    @Test
    void testExecuteWithParams() {
        // SET 关键字 值 可从 params 追加：SET itest:p + "value"
        assertEquals(1, engine.execute("SET itest:p", "value"));
        assertEquals(1, engine.execute("GET itest:p"));
        assertEquals("value", engine.getPoolPublic("default").getResource().get("itest:p"));
    }
}