package com.chua.common.support.task.scheduler;

import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 触发器接口
 *
 * <p>定义任务触发时间点的计算规范，是所有触发器实现的顶级抽象接口。
 * 支持两种触发模式：Cron 表达式触发和固定时间间隔触发。
 *
 * <p>核心能力：
 * <ul>
 *   <li>计算下一次执行时间点</li>
 *   <li>从指定时间开始计算下一次执行时间点</li>
 *   <li>批量获取后续 N 条执行时间点</li>
 *   <li>从指定时间开始批量获取 N 条执行时间点</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // Cron 触发器：每天中午 12 点执行
 * Trigger trigger = new CronTrigger("0 0 12 * * ?");
 * LocalDateTime next = trigger.nextExecutionTime();
 *
 * // 固定间隔触发器：每 5 秒执行一次
 * Trigger trigger = new FixedTrigger(5, TimeUnit.SECONDS);
 * List<LocalDateTime> times = trigger.getFireTimes(10);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public interface Trigger {

    /**
     * 计算下一次执行时间点
     *
     * <p>基于当前时间计算任务的下一次触发时间。
     * 对于 Cron 触发器，将基于当前时间查找下一个匹配的 Cron 时间点；
     * 对于固定间隔触发器，将基于开始时间计算下一个触发点。
     *
     * @return 下一次执行时间点，如果无法计算则返回 {@code null}
     */
    LocalDateTime nextExecutionTime();

    /**
     * 从指定时间开始计算下一次执行时间点
     *
     * <p>以给定的时间点为基准，计算在此之后的下一次触发时间。
     * 如果指定时间本身是一个触发点，返回的时间点必须严格大于指定时间。
     *
     * @param from 基准时间点（不为 {@code null}）
     * @return 下一次执行时间点，如果无法计算则返回 {@code null}
     */
    LocalDateTime nextExecutionTime(LocalDateTime from);

    /**
     * 获取后续 N 条执行时间点
     *
     * <p>基于当前时间，批量计算接下来的 N 次触发时间。
     * 返回列表包含 N 个严格递增的时间点。
     *
     * @param count 需要获取的执行时间点数量
     * @return 按时间排序的执行时间点列表，不会为 {@code null}
     */
    List<LocalDateTime> getFireTimes(int count);

    /**
     * 从指定时间开始获取 N 条执行时间点
     *
     * <p>以给定的时间点为基准，批量计算之后的 N 次触发时间。
     * 返回列表包含 N 个严格递增的时间点，且所有时间点均大于基准时间。
     *
     * @param count 需要获取的执行时间点数量
     * @param from  基准时间点（不为 {@code null}）
     * @return 按时间排序的执行时间点列表，不会为 {@code null}
     */
    List<LocalDateTime> getFireTimes(int count, LocalDateTime from);
}
