package com.chua.common.support.task.timer;

import java.util.concurrent.TimeUnit;

/**
 * 时间轮接口。
 *
 * <p>时间轮（Hashed Wheel Timer）是一种基于环形队列的高效定时任务调度数据结构。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Timer {

    static Timer newTimer() {
        return new HashedWheelTimer(512, 1, TimeUnit.MILLISECONDS);
    }

    static Timer newTimer(long tickDuration, TimeUnit unit) {
        return new HashedWheelTimer(512, tickDuration, unit);
    }

    static Timer newTimer(int slots, long tickDuration, TimeUnit unit) {
        return new HashedWheelTimer(slots, tickDuration, unit);
    }

    boolean schedule(TimerTask task);

    TimerTask schedule(Runnable task, long delay, TimeUnit timeUnit);

    TimerTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit timeUnit);

    boolean cancel(TimerTask task);

    long getTickCount();

    int getTaskCount();

    boolean isRunning();

    void shutdown();
}