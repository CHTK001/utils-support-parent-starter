package com.chua.common.support.task.scheduler;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullUnmarked;

/**
 * 固定时间间隔触发器
 *
 * <p>基于固定时间间隔的触发器实现，支持以下两种触发模式：
 * <ul>
 *   <li><strong>固定频率（Fixed Rate）</strong>：以固定的时间间隔执行任务，
 *   不关心前一次任务是否执行完成</li>
 *   <li><strong>固定延迟（Fixed Delay）</strong>：前一次任务执行完成后，
 *   等待固定时间再执行下一次</li>
 * </ul>
 *
 * <p>触发时间点的计算基于创建时刻的基准时间，通过 {@code startTime + N * interval} 的
 * 公式计算。支持自定义初始延迟时间。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 每 5 秒执行一次（无初始延迟）
 * FixedTrigger trigger = new FixedTrigger(5, TimeUnit.SECONDS);
 *
 * // 首次延迟 1 秒后，每 3 秒执行一次
 * FixedTrigger trigger = new FixedTrigger(1000, 3000, TimeUnit.MILLISECONDS);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public class FixedTrigger implements Trigger {

    /**
     * 首次执行前的初始延迟时间
     */
    private final long initialDelay;

    /**
     * 两次任务触发之间的时间间隔
     */
    private final long interval;

    /**
     * 延迟和间隔的时间单位
     */
    private final TimeUnit timeUnit;

    /**
     * 触发器创建的基准时间
     */
    private final LocalDateTime startTime;

    /**
     * 根据间隔创建固定频率触发器（无初始延迟）
     *
     * @param interval 时间间隔
     * @param timeUnit 时间单位
     */
    public FixedTrigger(long interval, TimeUnit timeUnit) {
        this(0, interval, timeUnit);
    }

    /**
     * 根据初始延迟和间隔创建固定频率触发器
     *
     * @param initialDelay 首次执行前的初始延迟
     * @param interval     两次任务触发之间的时间间隔
     * @param timeUnit     时间单位
     */
    public FixedTrigger(long initialDelay, long interval, TimeUnit timeUnit) {
        this.initialDelay = initialDelay;
        this.interval = interval;
        this.timeUnit = timeUnit;
        this.startTime = LocalDateTime.now();
    }

    /**
     * 计算从当前时间开始的下一次执行时间
     *
     * @return 下一次执行时间点
     */
    @Override
    public LocalDateTime nextExecutionTime() {
        return nextExecutionTime(LocalDateTime.now());
    }

    /**
     * 计算从指定时间开始的下一次执行时间
     *
     * <p>计算公式：{@code startTime + (floor(elapsed / interval) + 1) * interval + initialDelay}
     * 其中 elapsed 为从 startTime 到 from 的时间差。
     *
     * @param from 基准时间点
     * @return 下一次执行时间点
     */
    @Override
    public LocalDateTime nextExecutionTime(LocalDateTime from) {
        long nanos = timeUnit.toNanos(interval);
        long elapsed = ChronoUnit.NANOS.between(startTime, from);
        if (elapsed < 0) {
            return startTime.plusNanos(timeUnit.toNanos(initialDelay));
        }
        long periods = elapsed / nanos;
        return startTime.plusNanos((periods + 1) * nanos + timeUnit.toNanos(initialDelay));
    }

    /**
     * 获取从当前时间开始的 N 条执行时间
     *
     * @param count 执行时间点数量
     * @return 按时间排序的执行时间点列表
     */
    @Override
    public List<LocalDateTime> getFireTimes(int count) {
        return getFireTimes(count, LocalDateTime.now());
    }

    /**
     * 获取从指定时间开始的 N 条执行时间
     *
     * @param count 执行时间点数量
     * @param from  基准时间点
     * @return 按时间排序的执行时间点列表
     */
    @Override
    public List<LocalDateTime> getFireTimes(int count, LocalDateTime from) {
        List<LocalDateTime> result = new ArrayList<>(count);
        LocalDateTime current = from;
        for (int i = 0; i < count; i++) {
            current = nextExecutionTime(current);
            result.add(current);
            current = current.plusNanos(1);
        }
        return result;
    }

    /**
     * 获取初始延迟时间
     *
     * @return 初始延迟时间（以构造时指定的时间单位表示）
     */
    public long getInitialDelay() {
        return initialDelay;
    }

    /**
     * 获取固定时间间隔
     *
     * @return 时间间隔值（以构造时指定的时间单位表示）
     */
    public long getInterval() {
        return interval;
    }

    /**
     * 获取时间单位
     *
     * @return 时间单位
     */
    public TimeUnit getTimeUnit() {
        return timeUnit;
    }
}
