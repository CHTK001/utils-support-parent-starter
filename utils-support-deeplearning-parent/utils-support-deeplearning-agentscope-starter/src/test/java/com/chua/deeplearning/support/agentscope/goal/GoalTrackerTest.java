package com.chua.deeplearning.support.agentscope.goal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoalTracker} 的状态迁移、暂停/恢复与计账测试。
 *
 * @author CH
 */
class GoalTrackerTest {

    /**
     * 构造一个初始目标。
     *
     * @return 初始（PENDING）目标
     */
    private static SessionGoal goal() {
        return SessionGoal.create("g1", "s1", "把默认角色改成通用", "并自测通过", 1000L);
    }

    @Test
    void startMovesPendingToRunning() {
        GoalTracker tracker = new GoalTracker(goal());
        assertEquals(GoalState.PENDING, tracker.status().state());
        assertEquals("待开始", tracker.status().stateLabel());

        assertEquals(GoalState.RUNNING, tracker.start(2000L).state());
        assertEquals("进行中", tracker.status().stateLabel());
        assertTrue(tracker.shouldContinue());
    }

    @Test
    void pauseAndResumeRoundTrip() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(2000L);

        assertEquals(GoalState.PAUSED, tracker.pause(3000L).state());
        assertEquals("已暂停", tracker.status().stateLabel());
        assertFalse(tracker.status().pausable());
        assertTrue(tracker.status().resumable());

        assertEquals(GoalState.RUNNING, tracker.resume(4000L).state());
        assertEquals("进行中", tracker.status().stateLabel());
    }

    @Test
    void pauseTwiceIsIdempotent() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(2000L);
        tracker.pause(3000L);
        assertEquals(GoalState.PAUSED, tracker.pause(3500L).state());
    }

    @Test
    void resumeWithoutPauseIsRejected() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(2000L);
        assertThrows(IllegalStateException.class, () -> tracker.resume(3000L));
    }

    @Test
    void terminalStateRejectsFurtherTransition() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(2000L);
        tracker.complete("done", 3000L);
        assertTrue(tracker.status().state().terminal());
        assertFalse(tracker.shouldContinue());

        assertThrows(IllegalStateException.class, () -> tracker.pause(4000L));
        assertThrows(IllegalStateException.class, () -> tracker.fail("x", 4000L));
        assertThrows(IllegalStateException.class, () -> tracker.cancel("x", 4000L));
        assertThrows(IllegalStateException.class, () -> tracker.complete("y", 4000L));
    }

    @Test
    void cancelFromRunningIsTerminal() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(2000L);
        GoalStatus status = tracker.cancel("用户叫停", 3000L);
        assertEquals(GoalState.CANCELED, status.state());
        assertEquals("已取消", status.stateLabel());
    }

    @Test
    void tokensAccumulateAndFormat() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(1000L);
        tracker.recordTokens(512L, 1100L);
        assertEquals(512L, tracker.status().tokensUsed());
        assertEquals("512 令牌", tracker.status().tokensLabel());

        tracker.recordTokens(716_488L, 1200L);
        assertEquals(717_000L, tracker.status().tokensUsed());
        assertEquals("717K 令牌", tracker.status().tokensLabel());

        tracker.recordTokens(283_000L, 1300L);
        assertEquals("1M 令牌", tracker.status().tokensLabel());
    }

    @Test
    void negativeTokensRejected() {
        GoalTracker tracker = new GoalTracker(goal());
        assertThrows(IllegalArgumentException.class, () -> tracker.recordTokens(-1L, 1000L));
    }

    @Test
    void roundsIncrement() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.recordRound(1100L);
        tracker.recordRound(1200L);
        assertEquals(2, tracker.status().rounds());
    }

    @Test
    void awaitResumableReturnsImmediatelyWhenRunning() throws InterruptedException {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(1000L);
        long t0 = System.nanoTime();
        GoalStatus status = tracker.awaitResumable(5000L);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(GoalState.RUNNING, status.state());
        assertTrue(elapsedMs < 1000L, "运行态不应阻塞，实测 " + elapsedMs + "ms");
    }

    @Test
    void awaitResumableBlocksWhilePausedThenReturnsOnResume() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(1000L);
        tracker.pause(1100L);

        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<GoalStatus> seen = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                seen.set(tracker.awaitResumable(10_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                returned.countDown();
            }
        }, "goal-waiter");
        waiter.setDaemon(true);
        waiter.start();

        // 暂停期间不应返回
        assertFalse(returned.await(300, TimeUnit.MILLISECONDS), "暂停期间 awaitResumable 不应返回");
        tracker.resume(1200L);
        assertTrue(returned.await(5, TimeUnit.SECONDS), "恢复后应立刻返回");
        assertEquals(GoalState.RUNNING, seen.get().state());
    }

    @Test
    void awaitResumableTimesOutWhileStillPaused() throws InterruptedException {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(1000L);
        tracker.pause(1100L);

        long t0 = System.nanoTime();
        GoalStatus status = tracker.awaitResumable(300L);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(GoalState.PAUSED, status.state());
        assertTrue(elapsedMs >= 250L, "应至少等到超时，实测 " + elapsedMs + "ms");
    }

    @Test
    void awaitResumableReleasesWhenCanceledWhilePaused() throws Exception {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(1000L);
        tracker.pause(1100L);

        AtomicReference<GoalStatus> seen = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                seen.set(tracker.awaitResumable(10_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "goal-cancel-waiter");
        waiter.setDaemon(true);
        waiter.start();
        Thread.sleep(200L);
        tracker.cancel("用户取消", 1300L);
        waiter.join(5000L);
        assertFalse(waiter.isAlive(), "取消后等待线程应退出");
        assertEquals(GoalState.CANCELED, seen.get().state());
    }

    @Test
    void summaryContainsTitleStateAndTokens() {
        GoalTracker tracker = new GoalTracker(goal());
        tracker.start(1000L);
        tracker.recordTokens(717_000L, 1100L);
        String summary = tracker.status().summary();
        assertTrue(summary.contains("把默认角色改成通用"), summary);
        assertTrue(summary.contains("进行中"), summary);
        assertTrue(summary.contains("717K 令牌"), summary);
    }
}
