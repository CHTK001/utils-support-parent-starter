package com.chua.common.support.task.cooldown;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷却恢复的先进先出压力验证。
 *
 * <p>为什么需要压力而不是单次断言：单次用例
 * （{@code TimeWheelExpirySchedulerTest#recoversInInsertionOrder}）在单独跑时一直是绿的，
 * 只有在全量测试的高负载下才偶发 {@code [k|2, k|1, k|3]}。原因是要害在
 * 「派发线程抢在同批兄弟入队之前就把第一个元素取走了」—— 这取决于线程调度，
 * 单次运行几乎撞不上，必须把同样的场景重复足够多次才能稳定暴露。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class TimeWheelFifoStressTest {

    /**
     * 轮数：每轮 3 个同到期时刻的键
     */
    private static final int ROUNDS = 120;

    /**
     * 待关闭的调度器
     */
    private TimeWheelExpiryScheduler scheduler;

    /**
     * 测试用作用域
     */
    private static final String SCOPE = "test-time-wheel-fifo";

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
     * 反复构造「同一到期时刻登记 3 个键」，每次都要求按登记顺序恢复。
     */
    @Test
    @Timeout(180)
    @DisplayName("同到期时刻的批量登记必须始终按登记顺序恢复")
    void sameDeadlineBatchAlwaysRecoversInInsertionOrder() throws Exception {
        scheduler = new TimeWheelExpiryScheduler();
        CooldownPolicies.register("k|", new CooldownPolicy(20L, 20L, Backoff.fixed()));
        CooldownGate gate = CooldownGate.of(SCOPE).scheduler(scheduler);

        List<String> order = Collections.synchronizedList(new ArrayList<>(ROUNDS * 3));
        gate.subscribeRecover("k|", key -> {
            order.add(key);
        });

        int violations = 0;
        for (int round = 0; round < ROUNDS; round++) {
            String a = "k|a" + round;
            String b = "k|b" + round;
            String c = "k|c" + round;
            gate.fail(a, "429");
            gate.fail(b, "429");
            gate.fail(c, "429");

            // 等这一轮的 3 条全部回调
            long deadline = System.currentTimeMillis() + 5000L;
            int seen;
            do {
                Thread.sleep(2L);
                seen = order.size();
            } while (seen < (round + 1) * 3 && System.currentTimeMillis() < deadline);
            if (seen < (round + 1) * 3) {
                assertTrue(false, "第 " + round + " 轮到期回调未在预期时间内触发");
            }

            // 这一轮的三条必须恰好是 a、b、c 这个顺序
            List<String> triplet = new ArrayList<>(order.subList(round * 3, round * 3 + 3));
            if (!triplet.equals(List.of(a, b, c))) {
                violations++;
                if (violations == 1) {
                    assertEquals(List.of(a, b, c), triplet, "第 " + round + " 轮恢复顺序不是登记顺序");
                }
            }
        }
        assertEquals(0, violations, ROUNDS + " 轮同到期时刻登记中有 " + violations + " 轮恢复顺序错乱");
    }

    /**
     * 用多个线程并发登记，验证序号 tie-break 仍然给出确定的入队次序。
     *
     * <p>并发登记时「登记顺序」本身没有全局定义，但同一个键的重复登记必须取消旧的，
     * 不能出现同一键被恢复两次。</p>
     *
     * @throws Exception 线程等待失败
     */
    @Test
    @Timeout(120)
    @DisplayName("并发重复登记同一键不得重复恢复")
    void concurrentRefailRecoversExactlyOnce() throws Exception {
        scheduler = new TimeWheelExpiryScheduler();
        // 延迟给足：160 次并发 fail 必须在第一次到期之前全部完成，
        // 否则「恢复之后再失败」本来就是两次恢复，测的就不是重复登记了。
        CooldownPolicies.register("m|", new CooldownPolicy(300L, 300L, Backoff.fixed()));
        CooldownGate gate = CooldownGate.of(SCOPE).scheduler(scheduler);
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> hits =
                new java.util.concurrent.ConcurrentHashMap<>();
        gate.subscribeRecover("m|", key -> hits
                .computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet());

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 20; n++) {
                        gate.fail("m|dup", "429");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "refail-" + i);
            workers.add(t);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发登记线程未全部结束");

        // 结构性断言：并发重登记同一个键之后，登记表里只能剩一个待恢复项。
        // 旧实现「先 cancelPending 再 put」不是原子操作，两个线程的 put 会互相覆盖，
        // 被覆盖掉的那个队列项成为孤儿但仍然会到期 —— 登记表项数会大于 1。
        assertEquals(1, scheduler.size(), "同一键并发重复登记后登记表只应剩一个待恢复项");

        Thread.sleep(700L);
        java.util.concurrent.atomic.AtomicInteger count = hits.get("m|dup");
        assertEquals(1, count == null ? 0 : count.get(), "同一键重复登记只应恢复一次");
        assertEquals(0, gate.size(), "恢复后状态应被清理");
    }
}
