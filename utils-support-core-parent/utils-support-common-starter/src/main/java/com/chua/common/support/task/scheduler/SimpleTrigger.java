package com.chua.common.support.task.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 简单定时触发器，按固定间隔触发。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SimpleTrigger implements Trigger {

    /**
     * 固定触发间隔
     */
    private final Duration interval;

    /**
     * 构造简单触发器。
     *
     * @param interval 触发间隔
     */
    public SimpleTrigger(Duration interval) {
        this.interval = interval;
    }

    @Override
    /** NextExecutionTime */
    public LocalDateTime nextExecutionTime() {
        return LocalDateTime.now().plus(interval);
    }

    @Override
    /** NextExecutionTime */
    public LocalDateTime nextExecutionTime(LocalDateTime from) {
        return from.plus(interval);
    }

    @Override
    /** 获取FireTimes */
    public List<LocalDateTime> getFireTimes(int count) {
        LocalDateTime base = LocalDateTime.now();
        return getFireTimes(count, base);
    }

    @Override
    /** 获取FireTimes */
    public List<LocalDateTime> getFireTimes(int count, LocalDateTime from) {
        List<LocalDateTime> times = new ArrayList<>(count);
        LocalDateTime t = from.plus(interval);
        for (int i = 0; i < count; i++) {
            times.add(t);
            t = t.plus(interval);
        }
        return times;
    }
}