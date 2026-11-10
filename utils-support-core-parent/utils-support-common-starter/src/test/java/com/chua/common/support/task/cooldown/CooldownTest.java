package com.chua.common.support.task.cooldown;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型化冷却门面用例：资格、冷却、降级、排序、limit 判定。
 *
 * @author CH
 * @since 4.0.0.53
 */
class CooldownTest {

    /**
     * 每个用例结束后清空，避免跨用例串味
     */
    @AfterEach
    void tearDown() {
        CooldownGate.of("test-cooldown").reset();
        CooldownPolicies.clear();
    }

    /**
     * 资格不通过的对象被滤掉，且不记冷却
     */
    @Test
    void preconditionFiltersWithoutCooling() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key)
                .precondition(Precondition.isTrue(Account::enabled));

        List<Account> all = List.of(new Account("off", false, null, false, 1), new Account("on", true, null, false, 1));
        List<Account> usable = cooldown.select(all);

        assertEquals(1, usable.size());
        assertEquals("on", usable.getFirst().key());
        assertEquals(0, cooldown.gate().size(), "资格失败不应进入冷却");
    }

    /**
     * 全部在冷却时按半开放行返回有资格的候选
     */
    @Test
    void openAllWhenEverythingIsCooling() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key);
        Account a = new Account("a", true, null, false, 1);
        Account b = new Account("b", true, null, false, 1);
        cooldown.fail(a, "429");
        cooldown.fail(b, "429");

        assertEquals(2, cooldown.select(List.of(a, b)).size());
        assertTrue(cooldown.allLimited(List.of(a, b)));
    }

    /**
     * 拒绝降级返回空列表，first 返回 null
     */
    @Test
    void rejectReturnsEmpty() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key)
                .degrade(DegradePolicy.reject());
        Account a = new Account("a", true, null, false, 1);
        cooldown.fail(a, "429");

        assertEquals(0, cooldown.select(List.of(a)).size());
        assertNull(cooldown.first(List.of(a)));
    }

    /**
     * 兜底降级换备用池
     */
    @Test
    void fallbackUsesBackupPool() {
        Account backup = new Account("backup", true, null, false, 1);
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key)
                .degrade(DegradePolicy.fallback(() -> List.of(backup)));
        Account a = new Account("a", true, null, false, 1);
        cooldown.fail(a, "429");

        List<Account> selected = cooldown.select(List.of(a));
        assertEquals(1, selected.size());
        assertEquals("backup", selected.getFirst().key());
    }

    /**
     * 全部没资格时 exhausted 为真，半开也不会放出没资格的候选
     */
    @Test
    void exhaustedWhenAllFailPrecondition() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key)
                .precondition(Precondition.isTrue(Account::enabled));
        List<Account> all = List.of(new Account("a", false, null, false, 1));

        assertTrue(cooldown.exhausted(all));
        assertTrue(cooldown.allLimited(all));
        assertEquals(0, cooldown.select(all).size());
    }

    /**
     * 排序：快到期的优先，其次免费优先
     */
    @Test
    void ranksByExpiryThenFree() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key)
                .rank(Ranker.expiringFirst(Account::expiresAt))
                .rank(Ranker.freeFirst(Account::free));

        Account late = new Account("late", true, 9_000L, true, 1);
        Account soon = new Account("soon", true, 1_000L, false, 1);
        Account never = new Account("never", true, null, false, 1);

        List<Account> ranked = cooldown.select(List.of(late, never, soon));
        assertEquals(List.of("soon", "late", "never"), ranked.stream().map(Account::key).toList());
    }

    /**
     * 排序：便宜优先、延迟低的优先
     */
    @Test
    void ranksByCostAndLatency() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key)
                .rank(Ranker.cheapestFirst(Account::cost))
                .rank(Ranker.fastestFirst(item -> (long) item.cost()));

        Account expensive = new Account("expensive", true, null, false, 9);
        Account cheapSlow = new Account("cheapSlow", true, null, false, 1);
        Account cheapFast = new Account("cheapFast", true, null, false, 1);

        List<Account> ranked = cooldown.select(List.of(expensive, cheapSlow, cheapFast));
        assertEquals(List.of("cheapSlow", "cheapFast", "expensive"), ranked.stream().map(Account::key).toList());
    }

    /**
     * 数组入参、成功后立即解除冷却、快照可读
     */
    @Test
    void arrayInputAndSnapshot() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key);
        Account a = new Account("a", true, null, false, 1);

        cooldown.fail(a, "HTTP 429");
        assertFalse(cooldown.canUse(a));
        CooldownSnapshot snapshot = cooldown.snapshot(a);
        assertTrue(snapshot.blocked());
        assertEquals(1, snapshot.failures());
        assertNotNull(snapshot.reason());

        cooldown.pass(a);
        assertTrue(cooldown.canUse(a));
        assertEquals(1, cooldown.select(new Account[]{a}).size());
    }

    /**
     * first 取排序后的第一个可用候选
     */
    @Test
    void firstReturnsTopRankedUsable() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key)
                .rank(Ranker.expiringFirst(Account::expiresAt));
        Account late = new Account("late", true, 9_000L, false, 1);
        Account soon = new Account("soon", true, 1_000L, false, 1);

        assertEquals("soon", cooldown.first(List.of(late, soon)).key());
    }

    /**
     * 空候选不报错
     */
    @Test
    void emptyCandidatesAreSafe() {
        Cooldown<Account> cooldown = Cooldown.of("test-cooldown", Account::key);

        assertEquals(List.of(), cooldown.select((List<Account>) null));
        assertEquals(List.of(), cooldown.select(new ArrayList<>()));
        assertNull(cooldown.first(List.of()));
        assertTrue(cooldown.allLimited(List.of()));
    }

    /**
     * 测试用账号。
     *
     * @param key       标识
     * @param enabled   是否启用
     * @param expiresAt 到期时刻
     * @param free      是否免费
     * @param cost      单价
     */
    private record Account(String key, boolean enabled, Long expiresAt, boolean free, double cost) {
    }
}
