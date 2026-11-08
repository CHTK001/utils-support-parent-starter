package com.chua.deeplearning.support.agentscope.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoalRegistry} 的绑定、状态代理与容量保护测试。
 *
 * @author CH
 */
class GoalRegistryTest {

    /**
     * 构造目标。
     *
     * @param session 会话标识
     * @return 目标
     */
    private static SessionGoal goal(String session) {
        return SessionGoal.create("g-" + session, session, "目标-" + session, "", 1000L);
    }

    @Test
    void bindThenFindAndStatus() {
        GoalRegistry registry = new GoalRegistry(10);
        GoalTracker tracker = registry.bind(goal("s1"));

        assertTrue(registry.find("s1").isPresent());
        assertSame(tracker, registry.find("s1").orElseThrow());
        GoalStatus status = registry.status("s1");
        assertNotNull(status);
        assertEquals("g-s1", status.id());
        assertEquals(GoalState.PENDING, status.state());
        assertEquals(1, registry.size());
    }

    @Test
    void pauseResumeCancelThroughRegistry() {
        GoalRegistry registry = new GoalRegistry(10);
        registry.bind(goal("s1"));
        registry.find("s1").orElseThrow().start(1000L);

        assertEquals(GoalState.PAUSED, registry.pause("s1", 1100L).state());
        assertEquals(GoalState.RUNNING, registry.resume("s1", 1200L).state());
        assertEquals(GoalState.CANCELED, registry.cancel("s1", "用户取消", 1300L).state());
    }

    @Test
    void removeReturnsStatusThenNull() {
        GoalRegistry registry = new GoalRegistry(10);
        registry.bind(goal("s1"));
        assertNotNull(registry.remove("s1"));
        assertNull(registry.remove("s1"));
        assertEquals(0, registry.size());
        assertTrue(registry.find("s1").isEmpty());
    }

    @Test
    void statusOfUnknownSessionIsNull() {
        GoalRegistry registry = new GoalRegistry(10);
        assertNull(registry.status("nope"));
        assertTrue(registry.find("nope").isEmpty());
    }

    @Test
    void pauseUnknownSessionThrows() {
        GoalRegistry registry = new GoalRegistry(10);
        assertThrows(IllegalStateException.class, () -> registry.pause("nope", 1000L));
    }

    @Test
    void capacityEvictsTerminalFirst() {
        GoalRegistry registry = new GoalRegistry(2);
        registry.bind(goal("s1")).start(1000L);
        registry.bind(goal("s2")).start(1000L);
        registry.find("s1").orElseThrow().complete("done", 1100L);

        // s1 已终态，容量满时应被淘汰以腾出位置
        registry.bind(goal("s3"));
        assertEquals(2, registry.size());
        assertTrue(registry.find("s1").isEmpty(), "终态目标应被淘汰");
        assertTrue(registry.find("s3").isPresent());
    }

    @Test
    void capacityRefusesWhenNoTerminalToEvict() {
        GoalRegistry registry = new GoalRegistry(2);
        registry.bind(goal("s1")).start(1000L);
        registry.bind(goal("s2")).start(1000L);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry.bind(goal("s3")));
        assertTrue(e.getMessage().contains("容量已达上限"), e.getMessage());
        assertEquals(2, registry.size(), "拒绝写入时不能破坏已有目标");
    }

    @Test
    void sameSessionRebindDoesNotConsumeCapacity() {
        GoalRegistry registry = new GoalRegistry(1);
        registry.bind(goal("s1"));
        registry.bind(goal("s1"));
        assertEquals(1, registry.size());
        assertEquals("g-s1", registry.status("s1").id());
    }

    @Test
    void clearRemovesEverything() {
        GoalRegistry registry = new GoalRegistry(10);
        registry.bind(goal("s1"));
        registry.bind(goal("s2"));
        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test
    void sharedIsSingleton() {
        assertSame(GoalRegistry.shared(), GoalRegistry.shared());
    }

    @Test
    void maxGoalsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new GoalRegistry(0));
    }
}
