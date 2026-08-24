package com.chua.common.support.task.taskrunner;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskRunner 全场景单元测试。
 *
 * <p>覆盖：串行依赖、数据依赖、并行重叠、五种完成策略、超时、重试、
 * 熔断降级、环检测、参数校验、三种执行出口与事件流。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class TaskRunnerTest {

    /**
     * 场景1：afterNode 串行链按拓扑序执行，dependsNode 可读取前置结果。
     */
    @Test
    void serialChainWithDependencies() {
        var runner = TaskRunner.of("serial-chain")
                .policy(CompletionPolicy.allSuccess())
                .task("a", ctx -> 1)
                .task("b", ctx -> ctx.<Integer>get("a", Integer.class) + 10)
                .task("c", ctx -> {
                    var def = ctx.get("b");
                    return ((Number) def).intValue() + 100;
                })
                .task("b", ctx -> ctx.<Integer>get("a", Integer.class) + 10);
        // 上面的重复注册应抛异常，单独验证；重新构建合法 runner
        var dupRunner = assertThrows(IllegalStateException.class, () -> TaskRunner.of("dup")
                .policy(CompletionPolicy.allSuccess())
                .task("x", ctx -> "v")
                .task("x", ctx -> "v2"));
        assertNotNull(dupRunner);

        var result = runner.executeSync(null);
        assertTrue(result.success());
        assertEquals(TaskResult.Status.SUCCESS,
                result.findNode("c").orElseThrow().status());
        assertEquals(111, result.findNode("c").orElseThrow().data());
    }

    /**
     * 场景2：最外层无依赖任务并行执行（通过闩锁验证时间重叠）。
     */
    @Test
    void topLevelTasksRunInParallel() throws Exception {
        var latchA = new CountDownLatch(1);
        var latchB = new CountDownLatch(1);
        var bothStarted = new CountDownLatch(1);
        var started = new AtomicInteger();

        var runner = TaskRunner.of("parallel-top")
                .policy(CompletionPolicy.allSuccess())
                .task("slow-a", ctx -> {
                    if (started.incrementAndGet() == 2) {
                        bothStarted.countDown();
                    }
                    await(latchB);
                    return "A";
                })
                .task("slow-b", ctx -> {
                    if (started.incrementAndGet() == 2) {
                        bothStarted.countDown();
                    }
                    await(latchA);
                    return "B";
                });

        var future = runner.execute(null);
        // 若串行执行，第二个任务永远不会开始 → bothStarted 超时即失败
        assertTrue(bothStarted.await(3, TimeUnit.SECONDS), "两个顶层任务未并行启动");
        latchA.countDown();
        latchB.countDown();
        var result = future.get(5, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(2, result.countByStatus(TaskResult.Status.SUCCESS));
    }

    /**
     * 场景3：allSuccess 策略下任一失败即整体失败，下游节点级联跳过。
     */
    @Test
    void allSuccessFailFastSkipsDownstream() {
        var runner = TaskRunner.of("fail-fast")
                .policy(CompletionPolicy.allSuccess())
                .task("boom", ctx -> {
                    throw new IllegalArgumentException("bad input");
                })
                .task("downstream", ctx -> "never").afterNode("boom");

        var result = runner.executeSync(null);
        assertFalse(result.success());
        assertEquals(TaskResult.Status.FAILED, result.findNode("boom").orElseThrow().status());
        assertEquals(TaskResult.Status.SKIPPED, result.findNode("downstream").orElseThrow().status());
        assertNotNull(result.error());
    }

    /**
     * 场景4：anySuccess 策略下首个成功即整体成功，其余被取消跳过。
     */
    @Test
    void anySuccessEarlyExitCancelsRest() {
        var release = new CountDownLatch(1);
        var runner = TaskRunner.of("any-success")
                .policy(CompletionPolicy.anySuccess())
                .task("quick-ok", ctx -> "fast")
                .task("blocked-1", ctx -> {
                    await(release);
                    return "late-1";
                })
                .task("blocked-2", ctx -> {
                    await(release);
                    return "late-2";
                });

        var start = System.currentTimeMillis();
        var result = runner.executeSync(null);
        var elapsed = System.currentTimeMillis() - start;
        release.countDown();
        assertTrue(result.success(), "任一成功应整体成功");
        assertEquals(TaskResult.Status.SUCCESS, result.findNode("quick-ok").orElseThrow().status());
        assertTrue(elapsed < 2500, "提前取消不应等待阻塞任务, elapsed=" + elapsed + "ms");
    }

    /**
     * 场景5：成功率策略 — 80% 达标与不达标两个分支。
     */
    @Test
    void successRateThreshold() {
        var passed = buildRateRunner("rate-pass", false).executeSync(null);
        assertTrue(passed.success(), "4/5=80% 应达标");

        var rejected = buildRateRunner("rate-reject", true).executeSync(null);
        assertFalse(rejected.success(), "3/5=60% 不应达标");
    }

    /**
     * 构建成功率验证用 runner：5 个并行任务中固定 n 个失败。
     */
    private TaskRunner buildRateRunner(String name, boolean threeFails) {
        var runner = TaskRunner.of(name).policy(CompletionPolicy.successRate(0.8));
        for (var i = 0; i < 5; i++) {
            final var idx = i;
            final var shouldFail = idx < (threeFails ? 3 : 2);
            runner.task("t-" + idx, ctx -> {
                if (shouldFail) {
                    throw new IllegalStateException("planned-fail-" + idx);
                }
                return "ok-" + idx;
            });
        }
        return runner;
    }

    /**
     * 场景6：successAtLeast / failAtLeast 阈值策略。
     */
    @Test
    void thresholdPolicies() {
        var atLeast = TaskRunner.of("at-least")
                .policy(CompletionPolicy.successAtLeast(2))
                .task("s1", ctx -> "ok")
                .task("f1", ctx -> {
                    throw new IllegalStateException("x");
                })
                .task("s2", ctx -> "ok")
                .executeSync(null);
        assertTrue(atLeast.success(), "成功数 2 >= 阈值 2 应通过");

        var failLimit = TaskRunner.of("fail-limit")
                .policy(CompletionPolicy.failAtLeast(2))
                .task("f1", ctx -> {
                    throw new IllegalStateException("x");
                })
                .task("f2", ctx -> {
                    throw new IllegalStateException("y");
                })
                .task("s1", ctx -> "ok")
                .executeSync(null);
        assertFalse(failLimit.success(), "失败数 2 >= 阈值 2 应判败");
    }

    /**
     * 场景7：单任务超时判失败，且不影响其他任务。
     */
    @Test
    void perTaskTimeout() {
        var runner = TaskRunner.of("timeout-case")
                .policy(CompletionPolicy.anySuccess())
                .task("slow", ctx -> {
                    ThreadUtils_sleep(Duration.ofMillis(600));
                    return "never";
                }).timeout(Duration.ofMillis(120))
                .task("healthy", ctx -> "fine");

        var result = runner.executeSync(null);
        assertTrue(result.success(), "anySuccess 下健康任务保底");
        assertEquals(TaskResult.Status.FAILED, result.findNode("slow").orElseThrow().status());
        assertEquals(TaskResult.Status.SUCCESS, result.findNode("healthy").orElseThrow().status());
    }

    /**
     * 场景8：重试 — 第 3 次尝试成功，全局 retry 生效。
     */
    @Test
    void globalRetryRecoversFlakyTask() {
        var attempts = new AtomicInteger();
        var runner = TaskRunner.of("retry-case")
                .retry(5)
                .policy(CompletionPolicy.allSuccess())
                .task("flaky", ctx -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("transient");
                    }
                    return "recovered";
                });

        var result = runner.executeSync(null);
        assertTrue(result.success());
        assertEquals(3, attempts.get());
        assertEquals("recovered", result.findNode("flaky").orElseThrow().data());
    }

    /**
     * 场景9：熔断降级 — 失败任务走 fallback 返回兜底值记为成功。
     */
    @Test
    void circuitBreakerFallbackDegradation() {
        var runner = TaskRunner.of("cb-fallback")
                .policy(CompletionPolicy.allSuccess())
                .task("unstable-cb-demo", ctx -> {
                    throw new IllegalStateException("remote down");
                })
                .failureThreshold(2)
                .waitDuration(60_000)
                .circuitBreaker()
                .fallback(ctx -> "cached-value");

        var result = runner.executeSync(null);
        assertTrue(result.success(), "降级结果应视为成功");
        assertEquals("cached-value", result.findNode("unstable-cb-demo").orElseThrow().data());

        var second = runner.executeSync(null);
        assertTrue(second.success());
        assertEquals("cached-value", second.findNode("unstable-cb-demo").orElseThrow().data(),
                "熔断打开后仍应返回降级值");
    }

    /**
     * 场景10：循环依赖在构建期被拒绝。
     */
    @Test
    void cycleDetection() {
        var error = assertThrows(IllegalStateException.class,
                () -> TaskRunner.of("cycle")
                        .policy(CompletionPolicy.allSuccess())
                        .task("p", ctx -> 1).afterNode("r")
                        .task("q", ctx -> 2).afterNode("p")
                        .task("r", ctx -> 3).afterNode("q")
                        .executeSync(null));
        assertTrue(error.getMessage().contains("循环依赖"));
    }

    /**
     * 场景11：未设置策略时拒绝执行。
     */
    @Test
    void policyIsRequired() {
        var runner = TaskRunner.of("no-policy").task("a", ctx -> 1);
        var error = assertThrows(IllegalStateException.class, () -> runner.executeSync(null));
        assertTrue(error.getMessage().contains("policy"));
    }

    /**
     * 场景12：异步出口 CompletableFuture 正常回填。
     */
    @Test
    void asyncExecutionCompletes() throws Exception {
        var runner = TaskRunner.of("async-out")
                .policy(CompletionPolicy.allSuccess())
                .task("work", ctx -> "done");

        CompletableFuture<RunResult> future = runner.execute("payload");
        var result = future.get(5, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals("done", result.findNode("work").orElseThrow().data());
    }

    /**
     * 场景13：响应式出口 Mono 惰性订阅并发出结果。
     */
    @Test
    void reactiveExecutionEmitsResult() {
        var runner = TaskRunner.of("reactive-out")
                .policy(CompletionPolicy.allSuccess())
                .task("rx-task", ctx -> "rx-value");

        Mono<RunResult> mono = runner.executeReactor(null);
        var result = mono.block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals("rx-value", result.findNode("rx-task").orElseThrow().data());
    }

    /**
     * 场景14：watch() 收到完整生命周期事件流。
     */
    @Test
    void watchStreamCarriesLifecycleEvents() {
        var runner = TaskRunner.of("events")
                .policy(CompletionPolicy.allSuccess())
                .task("evt-node", ctx -> "ok");

        var seen = new AtomicReference<List<RunnerEvent>>();
        var subscription = runner.watch()
                .collectList()
                .subscribe(seen::set);

        runner.executeSync(null);
        subscription.dispose();
        // 同步路径结束后事件已同步发布完成
        var events = seen.get();
        assertNotNull(events, "事件流应有内容");
        assertEquals(RunnerEvent.Type.RUN_STARTED, events.getFirst().type());
        assertTrue(events.stream().anyMatch(e -> e.type() == RunnerEvent.Type.NODE_COMPLETED));
        assertEquals(RunnerEvent.Type.RUN_COMPLETED, events.getLast().type());
    }

    /**
     * 场景15：非法参数校验 — 空名称、负重试、坏阈值、空依赖数组。
     */
    @Test
    void parameterValidation() {
        assertThrows(IllegalArgumentException.class, () -> TaskRunner.of(" "));
        var runner = TaskRunner.of("validation");
        assertThrows(IllegalArgumentException.class, () -> runner.retry(-1));
        assertThrows(IllegalArgumentException.class,
                () -> runner.task("v", ctx -> 1).retry(-2));
        assertThrows(IllegalArgumentException.class,
                () -> CompletionPolicy.successAtLeast(0));
        assertThrows(IllegalArgumentException.class,
                () -> CompletionPolicy.failAtLeast(0));
        assertThrows(IllegalArgumentException.class,
                () -> CompletionPolicy.successRate(1.5));
        assertThrows(IllegalArgumentException.class,
                () -> CompletionPolicy.successRate(0));
        assertThrows(IllegalArgumentException.class,
                () -> runner.task("dep-test", ctx -> 1).afterNode());
    }

    /**
     * 忽略中断的闩锁等待辅助方法。
     *
     * @param latch 目标闩锁
     */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch await 超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 睡眠辅助（测试专用）。
     *
     * @param d 时长
     */
    private static void ThreadUtils_sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
