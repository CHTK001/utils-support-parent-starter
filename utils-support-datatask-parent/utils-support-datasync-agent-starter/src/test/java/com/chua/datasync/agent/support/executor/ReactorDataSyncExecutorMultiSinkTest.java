package com.chua.datasync.agent.support.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一源多目标的派发行为测试。
 *
 * <p><b>为什么必须有这些用例</b>：多目标是靠一个执行器挂多个 sink 实现的。
 * 原实现用单个 {@code directConsumer} 字段存消费者，注册第二个 sink 会直接覆盖
 * 第一个——表现不是报错，而是「某个目标一直零写入」，属于最难发现的一类缺陷。
 * 这里把「多 sink 各自收到数据」「改配置后旧 sink 不再收到」锁死成用例。</p>
 *
 * @author CH
 */
class ReactorDataSyncExecutorMultiSinkTest {

    /**
     * 两个 sink 都要收到同一批数据，且各自独立。
     */
    @Test
    @DisplayName("直连模式下多个 sink 各自收到数据")
    void multipleSinksEachReceiveData() {
        ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor("agent-multi", true);
        executor.setDirectDispatch(true);

        List<Map<String, Object>> toA = new ArrayList<>();
        List<Map<String, Object>> toB = new ArrayList<>();
        executor.subscribe("sink-a", toA::addAll);
        executor.subscribe("sink-b", toB::addAll);

        List<Map<String, Object>> batch = List.of(Map.of("id", 1), Map.of("id", 2));
        executor.publish("sink-a", batch);
        executor.publish("sink-b", batch);

        assertEquals(2, toA.size(), "目标 A 应收到整批数据");
        assertEquals(2, toB.size(), "目标 B 应收到整批数据");
        assertEquals(1, toA.get(0).get("id"));
        assertEquals(1, toB.get(0).get("id"));
    }

    /**
     * 发布到某个 sinkId 不应打到别的 sink。
     */
    @Test
    @DisplayName("按 sinkId 路由，不会串数据到其它目标")
    void publishRoutesBySinkIdOnly() {
        ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor("agent-route", true);
        executor.setDirectDispatch(true);

        AtomicInteger countA = new AtomicInteger();
        AtomicInteger countB = new AtomicInteger();
        executor.subscribe("sink-a", data -> countA.addAndGet(data.size()));
        executor.subscribe("sink-b", data -> countB.addAndGet(data.size()));

        executor.publish("sink-a", List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));

        assertEquals(3, countA.get(), "A 目标应收到 3 行");
        assertEquals(0, countB.get(), "B 目标不应收到 A 目标的数据");
    }

    /**
     * 换 sink 实例后，旧消费者必须失效。
     *
     * <p>对应「改了目标表配置不生效」：旧订阅留在表里，数据仍写旧表。</p>
     */
    @Test
    @DisplayName("同一 sinkId 重新订阅会替换旧消费者")
    void resubscribeReplacesOldConsumer() {
        ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor("agent-resub", true);
        executor.setDirectDispatch(true);

        AtomicInteger oldCount = new AtomicInteger();
        AtomicInteger newCount = new AtomicInteger();
        executor.subscribe("sink-a", data -> oldCount.addAndGet(data.size()));
        executor.subscribe("sink-a", data -> newCount.addAndGet(data.size()));

        executor.publish("sink-a", List.of(Map.of("id", 1)));

        assertEquals(0, oldCount.get(), "旧消费者不应再收到数据");
        assertEquals(1, newCount.get(), "新消费者应收到数据");
    }

    /**
     * 取消订阅后不再投递。
     */
    @Test
    @DisplayName("取消订阅后不再收到数据")
    void unsubscribeStopsDelivery() {
        ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor("agent-unsub", true);
        executor.setDirectDispatch(true);

        AtomicInteger count = new AtomicInteger();
        executor.subscribe("sink-a", data -> count.addAndGet(data.size()));
        executor.unsubscribe("sink-a");
        executor.publish("sink-a", List.of(Map.of("id", 1)));

        assertEquals(0, count.get(), "取消订阅后不应再收到数据");
    }

    /**
     * 没有订阅者时不应抛异常打断调度线程。
     */
    @Test
    @DisplayName("无订阅者时发布不抛异常")
    void publishWithoutSubscriberDoesNotThrow() {
        ReactorDataSyncExecutor executor = new ReactorDataSyncExecutor("agent-empty", true);
        executor.setDirectDispatch(true);
        executor.publish("sink-missing", List.of(Map.of("id", 1)));
        assertTrue(true, "无订阅者时应记录告警而不是抛出");
    }
}
