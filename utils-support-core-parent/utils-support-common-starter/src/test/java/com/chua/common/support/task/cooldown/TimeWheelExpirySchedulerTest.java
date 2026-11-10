package com.chua.common.support.task.cooldown;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间轮调度器用例：主动到期回调、先进先出、成功即取消。
 *
 * @author CH
 * @since 4.0.0.53
 */
class TimeWheelExpirySchedulerTest {

    /**
     * 待关闭的调度器
     */
    private TimeWheelExpiryScheduler scheduler;

    /**
     * 测试用作用域
     */
    private static final String SCOPE = "test-time-wheel";

    /**
     * 清理作用域、策略与调度器
     */
    @AfterEach
    void tearDown() {
        CooldownGate.of(SCOPE).reset();
        CooldownGate.of(SCOPE).scheduler(null);
        CooldownPolicies.clear();
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    /**
     * 到期主动回调，并按登记顺序先进先出
     */
    @Test
    void recoversInInsertionOrder() throws Exception {
        scheduler = new TimeWheelExpiryScheduler();
        CooldownPolicies.register("k|", new CooldownPolicy(80L, 80L, Backoff.fixed()));
        CooldownGate gate = CooldownGate.of(SCOPE).scheduler(scheduler);
        CountDownLatch latch = new CountDownLatch(3);
        List<String> order = Collections.synchronizedList(new ArrayList<>(3));
        gate.subscribeRecover("k|", key -> {
            order.add(key);
            latch.countDown();
        });

        gate.fail("k|1", "429");
        gate.fail("k|2", "429");
        gate.fail("k|3", "429");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "到期回调未在预期时间内触发");
        assertEquals(List.of("k|1", "k|2", "k|3"), order);
        assertEquals(0, gate.size(), "到期后状态应被清理");
        assertEquals(0, scheduler.size(), "到期后任务应从登记表移除");
    }

    /**
     * 成功解除冷却时取消到期任务，不再回调
     */
    @Test
    void passCancelsRecovery() throws Exception {
        scheduler = new TimeWheelExpiryScheduler();
        CooldownPolicies.register("k|", new CooldownPolicy(80L, 80L, Backoff.fixed()));
        CooldownGate gate = CooldownGate.of(SCOPE).scheduler(scheduler);
        AtomicInteger recovered = new AtomicInteger();
        gate.subscribeRecover("k|", key -> recovered.incrementAndGet());

        gate.fail("k|1", "429");
        gate.pass("k|1");

        Thread.sleep(250L);

        assertEquals(0, recovered.get(), "成功恢复后不应再触发到期回调");
        assertEquals(0, gate.size());
    }

    /**
     * 重复失败会重排到期时间，不会按旧时间提前回调
     */
    @Test
    void refailReschedulesDeadline() throws Exception {
        scheduler = new TimeWheelExpiryScheduler();
        CooldownPolicies.register("k|", new CooldownPolicy(60L, 60L, Backoff.fixed()));
        CooldownGate gate = CooldownGate.of(SCOPE).scheduler(scheduler);
        AtomicInteger recovered = new AtomicInteger();
        gate.subscribeRecover("k|", key -> recovered.incrementAndGet());

        gate.fail("k|1", "429");
        Thread.sleep(30L);
        gate.fail("k|1", "429");

        Thread.sleep(200L);
        assertEquals(1, recovered.get(), "重复失败只应恢复一次");
        assertEquals(0, gate.size());
    }
}
