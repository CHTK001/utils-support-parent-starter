package com.chua.deeplearning.support.agentscope.goal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoalLoop} 的推进、判定、轮次上限与暂停/取消语义测试。
 *
 * @author CH
 */
class GoalLoopTest {

    /**
     * 构造一个初始目标。
     *
     * @return 初始目标
     */
    private static SessionGoal goal() {
        return SessionGoal.create("g1", "s1", "做完目标功能", "前后端都要", 1000L);
    }

    /**
     * 轮询等待条件成立。
     *
     * @param condition 条件
     * @param timeoutMs 超时毫秒
     * @return 成立返回 true
     * @throws InterruptedException 被中断时抛出
     */
    private static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    @Test
    void completesOnFirstTurnWhenMarkerPresent() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        GoalStatus status = GoalLoop.run(tracker,
                prompt -> new GoalLoop.TurnResult("已做完，输出 " + GoalLoop.DONE_MARKER, 100L),
                new GoalLoop.MarkerGoalEvaluator(), 5, 0L);

        assertEquals(GoalState.COMPLETED, status.state());
        assertEquals(1, status.rounds());
        assertEquals(100L, status.tokensUsed());
    }

    @Test
    void continuesUntilMarkerThenCompletes() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        AtomicInteger calls = new AtomicInteger();
        GoalStatus status = GoalLoop.run(tracker, prompt -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                return new GoalLoop.TurnResult("第 " + n + " 步完成，继续", 10L);
            }
            return new GoalLoop.TurnResult("全部完成 " + GoalLoop.DONE_MARKER, 10L);
        }, new GoalLoop.MarkerGoalEvaluator(), 5, 0L);

        assertEquals(GoalState.COMPLETED, status.state());
        assertEquals(3, status.rounds());
        assertEquals(30L, status.tokensUsed());
        assertEquals(3, calls.get());
    }

    @Test
    void failMarkerMarksFailed() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        GoalStatus status = GoalLoop.run(tracker,
                prompt -> new GoalLoop.TurnResult("做不到 " + GoalLoop.FAIL_MARKER, 7L),
                new GoalLoop.MarkerGoalEvaluator(), 5, 0L);

        assertEquals(GoalState.FAILED, status.state());
        assertEquals(1, status.rounds());
        assertEquals(7L, status.tokensUsed());
    }

    @Test
    void hittingMaxRoundsFailsInsteadOfLoopingForever() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        AtomicInteger calls = new AtomicInteger();
        GoalStatus status = GoalLoop.run(tracker, prompt -> {
            calls.incrementAndGet();
            return new GoalLoop.TurnResult("还没好", 1L);
        }, new GoalLoop.MarkerGoalEvaluator(), 4, 0L);

        assertEquals(GoalState.FAILED, status.state());
        assertEquals(4, status.rounds());
        assertEquals(4, calls.get(), "必须恰好执行 maxRounds 轮后停止");
        assertTrue(tracker.lastReason().contains("最大轮次"), tracker.lastReason());
    }

    @Test
    void turnExceptionMarksFailedWithoutPropagating() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        GoalStatus status = GoalLoop.run(tracker, prompt -> {
            throw new IllegalStateException("模型炸了");
        }, new GoalLoop.MarkerGoalEvaluator(), 5, 0L);

        assertEquals(GoalState.FAILED, status.state());
        assertTrue(tracker.lastReason().contains("执行异常"), tracker.lastReason());
    }

    @Test
    void pauseStopsProgressAndResumeContinues() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.pause(1100L);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<GoalStatus> result = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                result.set(GoalLoop.run(tracker, prompt -> {
                    calls.incrementAndGet();
                    return new GoalLoop.TurnResult("做完了 " + GoalLoop.DONE_MARKER, 5L);
                }, new GoalLoop.MarkerGoalEvaluator(), 3, 0L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "goal-loop-paused");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(300L);
        assertEquals(0, calls.get(), "暂停期间不应执行任何轮次");
        assertEquals(GoalState.PAUSED, tracker.status().state());

        tracker.resume(1200L);
        assertTrue(waitFor(() -> !worker.isAlive(), 5000L), "恢复后应结束");
        assertEquals(GoalState.COMPLETED, result.get().state());
        assertEquals(1, calls.get());
    }

    @Test
    void cancelWhilePausedStopsTheLoop() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.pause(1100L);
        AtomicReference<GoalStatus> result = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                result.set(GoalLoop.run(tracker,
                        prompt -> new GoalLoop.TurnResult("x", 1L),
                        new GoalLoop.MarkerGoalEvaluator(), 3, 0L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "goal-loop-canceled");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(200L);
        tracker.cancel("用户取消", 1200L);
        assertTrue(waitFor(() -> !worker.isAlive(), 5000L), "取消后应结束");
        assertEquals(GoalState.CANCELED, result.get().state());
    }

    @Test
    void pauseTimeoutAllowsLoopToContinueOnNextRound() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.pause(1100L);
        // 暂停超时 200ms：循环会在超时后继续推进（由业务决定是否接受这种语义）
        GoalStatus status = GoalLoop.run(tracker,
                prompt -> new GoalLoop.TurnResult("继续 " + GoalLoop.DONE_MARKER, 3L),
                new GoalLoop.MarkerGoalEvaluator(), 2, 200L);

        assertEquals(GoalState.COMPLETED, status.state());
    }

    @Test
    void maxRoundsMustBePositive() {
        GoalTracker tracker = new GoalTracker(goal());
        assertThrows(IllegalArgumentException.class, () -> GoalLoop.run(tracker,
                prompt -> new GoalLoop.TurnResult("x", 1L),
                new GoalLoop.MarkerGoalEvaluator(), 0, 0L));
    }

    @Test
    void roundPromptCarriesGoalAndMarkerContract() {
        String prompt = GoalLoop.roundPrompt(goal(), 2);
        assertTrue(prompt.contains("做完目标功能"), prompt);
        assertTrue(prompt.contains("前后端都要"), prompt);
        assertTrue(prompt.contains("第 2 轮"), prompt);
        assertTrue(prompt.contains(GoalLoop.DONE_MARKER), prompt);
        assertTrue(prompt.contains(GoalLoop.FAIL_MARKER), prompt);
    }

    @Test
    void negativeTokensRejectedInTurnResult() {
        assertThrows(IllegalArgumentException.class, () -> new GoalLoop.TurnResult("x", -1L));
    }
}
