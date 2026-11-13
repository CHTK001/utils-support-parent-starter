package com.chua.common.support.task.cooldown;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷却门面用例。
 *
 * <p>验证五件事：同名作用域共享状态、不同作用域互不串味；
 * 连续失败指数退避且封顶；到期自动恢复；全量冷却时半开放行；
 * 失败事件按前缀订阅。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class CooldownGateTest {

    /**
     * 每个用例结束后清空，避免跨用例串味
     */
    @AfterEach
    void tearDown() {
        CooldownGate.of("test-a").reset();
        CooldownGate.of("test-b").reset();
        CooldownGate.of("default").reset();
        CooldownPolicies.clear();
    }

    /**
     * 渠道注册自己的策略后，只影响该前缀下的键，其余仍用作用域默认值
     */
    @Test
    void registeredPolicyOverridesOnlyItsPrefix() {
        CooldownGate gate = CooldownGate.of("test-a").configure(60_000L, 600_000L);
        CooldownPolicies.register("channel|slow|", 10_000L, 20_000L);

        gate.fail("channel|slow|acc-1", "429");
        gate.fail("channel|other|acc-1", "429");

        long slow = gate.remaining("channel|slow|acc-1");
        long normal = gate.remaining("channel|other|acc-1");
        assertTrue(slow > 9_000L && slow <= 10_000L, "渠道自定义时长未生效: " + slow);
        assertTrue(normal > 59_000L, "未注册的渠道不应被影响: " + normal);
        // [P3C A20 豁免]
        assertEquals(20_000L, gate.backoffMillis("channel|slow|acc-1", 99));
    }

    /**
     * 多个前缀命中时取最长的一个（渠道 > 平台）
     */
    @Test
    void longestPrefixWins() {
        CooldownPolicies.register("p|", 1_000L, 2_000L);
        CooldownPolicies.register("p|m|", 5_000L, 9_000L);

        assertEquals(5_000L, CooldownPolicies.resolve("p|m|key").baseMillis());
        assertEquals(1_000L, CooldownPolicies.resolve("p|m2|key").baseMillis());
        assertNull(CooldownPolicies.resolve("other|key"));
    }

    /**
     * 注册全 0 等于撤销策略，避免配置删掉后仍然生效
     */
    @Test
    void zeroPolicyUnregistersPrefix() {
        CooldownPolicies.register("p|", 1_000L, 2_000L);
        assertEquals(1, CooldownPolicies.size());

        CooldownPolicies.register("p|", 0L, 0L);

        assertEquals(0, CooldownPolicies.size());
        assertEquals(CooldownGate.DEFAULT_BASE_MS,
                CooldownGate.of("test-a").backoffMillis("p|key", 1));
    }

    /**
     * 同名作用域取到同一份状态，不同作用域互不影响
     */
    @Test
    void sameScopeSharesStateAndDifferentScopesIsolate() {
        CooldownGate first = CooldownGate.of("test-a");
        CooldownGate again = CooldownGate.of("test-a");
        CooldownGate other = CooldownGate.of("test-b");

        assertSame(first, again);
        first.fail("acc-1", "429");

        assertTrue(first.blocked("acc-1"));
        assertTrue(again.blocked("acc-1"));
        assertFalse(other.blocked("acc-1"));
        assertEquals(1, first.size());
        assertEquals(0, other.size());
    }

    /**
     * 空白作用域名落到 default
     */
    @Test
    void blankScopeFallsBackToDefault() {
        CooldownGate gate = CooldownGate.of("  ");
        assertSame(CooldownGate.of("default"), gate);
        assertEquals("default", gate.scope());
    }

    /**
     * 连续失败指数退避并封顶，成功一次立即清零
     */
    @Test
    void backoffGrowsCapsAndResetsOnSuccess() {
        // [P3C A20 豁免]
        CooldownGate gate = CooldownGate.of("test-a").configure(1000L, 3000L);
        gate.fail("acc-1", "429");
        long first = gate.remaining("acc-1");
        gate.fail("acc-1", "429");
        long second = gate.remaining("acc-1");
        gate.fail("acc-1", "429");
        long third = gate.remaining("acc-1");

        assertTrue(second > first, "第二次应更久: " + first + " -> " + second);
        assertTrue(third >= second, "第三次不应更短: " + second + " -> " + third);
        // [P3C A20 豁免]
        assertTrue(third <= 3000L, "不得超过封顶: " + third);
        // [P3C A20 豁免]
        assertEquals(3000L, gate.backoffMillis(99));

        gate.pass("acc-1");
        assertFalse(gate.blocked("acc-1"));
        assertEquals(0L, gate.remaining("acc-1"));
        assertEquals(0, gate.size());
    }

    /**
     * 冷却到期自动恢复，不需要外部触发
     */
    @Test
    void cooldownExpiresOnItsOwn() throws Exception {
        // [P3C A20 豁免]
        CooldownGate gate = CooldownGate.of("test-a").configure(60L, 240L);
        gate.fail("acc-1", "429");
        assertTrue(gate.blocked("acc-1"));

        // [P3C A20 豁免]
        Thread.sleep(120L);

        assertFalse(gate.blocked("acc-1"));
        assertEquals(0, gate.size());
    }

    /**
     * 全部候选在冷却时半开放行，不全量拒绝
     */
    @Test
    void filterOpenFallsBackToAllWhenEverythingIsCooling() {
        CooldownGate gate = CooldownGate.of("test-a");
        List<String> candidates = List.of("acc-1", "acc-2");
        gate.fail("acc-1", "429");
        gate.fail("acc-2", "429");

        assertEquals(candidates, gate.filterOpen(candidates, key -> key));

        gate.pass("acc-2");
        assertEquals(List.of("acc-2"), gate.filterOpen(candidates, key -> key));
    }

    /**
     * 失败事件按前缀订阅，退订后不再回调；成功不触发限流事件
     */
    @Test
    void failureNotifiesPrefixSubscribers() {
        CooldownGate gate = CooldownGate.of("test-a");
        AtomicInteger hits = new AtomicInteger();
        gate.subscribe("channel|qoder|", key -> hits.incrementAndGet());

        gate.fail("channel|qoder|acc-1", "429");
        gate.fail("channel|other|acc-1", "429");
        gate.pass("channel|qoder|acc-1");
        assertEquals(1, hits.get());

        gate.subscribe("channel|qoder|", null);
        gate.fail("channel|qoder|acc-1", "429");
        assertEquals(1, hits.get());
    }

    /**
     * 不广播的失败仍然进入冷却（连不上上游的短时排除）
     */
    @Test
    void failWithoutNotifyStillCoolsDown() {
        CooldownGate gate = CooldownGate.of("test-a");
        AtomicInteger hits = new AtomicInteger();
        gate.subscribe("k|", key -> hits.incrementAndGet());

        gate.fail("k|1", "Connection refused", false);

        assertTrue(gate.blocked("k|1"));
        assertEquals(0, hits.get());
    }

    /**
     * reset 同时清状态、订阅并恢复默认参数
     */
    @Test
    void resetClearsStateListenersAndConfig() {
        CooldownGate gate = CooldownGate.of("test-a").configure(1L, 2L);
        AtomicInteger hits = new AtomicInteger();
        gate.subscribe("k|", key -> hits.incrementAndGet());
        gate.fail("k|1", "429");
        assertEquals(1, gate.size());

        gate.reset();
        gate.fail("k|1", "429");

        assertEquals(1, gate.size());
        assertEquals(1, hits.get(), "reset 应清掉订阅，第二次失败不再回调");
        assertEquals(CooldownGate.DEFAULT_BASE_MS, gate.backoffMillis(1));
        assertTrue(gate.remaining("k|1") > 0);
    }
}
