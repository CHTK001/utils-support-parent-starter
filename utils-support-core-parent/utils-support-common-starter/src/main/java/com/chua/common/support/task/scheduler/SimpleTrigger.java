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

    /**
    * 下一次触发时间：当前时间加上固定间隔。
     */
    @Override
    public LocalDateTime nextExecutionTime() {
        return LocalDateTime.now().plus(interval);
    }

    /**
    * 以指定基准时间计算下一次触发时间。
     */
    @Override
    public LocalDateTime nextExecutionTime(LocalDateTime from) {
        return from.plus(interval);
    }

    /**
    * 获取从当前时间起的前 数量 次触发时间。
     */
    @Override
    public List<LocalDateTime> getFireTimes(int count) {
        LocalDateTime base = LocalDateTime.now();
        return getFireTimes(count, base);
    }

    /**
    * 获取从指定基准时间起的前 数量 次触发时间，按间隔递推。
     */
    @Override
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