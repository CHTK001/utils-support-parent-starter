package com.chua.common.support.task.scheduler;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
* 时间调度管理器
*
* <p>调度框架的门面类，提供简洁易用的任务调度 API。内部封装了 {@link SchedulerProvider} 的
* 调度能力，对外提供 Cron 调度、固定频率调度等便捷方法。
*
* <p>核心能力：
* <ul>
*   <li><strong>Cron 调度</strong>：通过 {@link #scheduleCron(Runnable, String)} 注册 Cron 任务</li>
*   <li><strong>固定频率调度</strong>：通过 {@link #scheduleFixedRate(Runnable, long, TimeUnit)} 注册固定间隔任务</li>
*   <li><strong>任务管理</strong>：支持取消任务、查询运行状态、获取所有调度任务</li>
*   <li><strong>生命周期</strong>：通过 {@link #shutdown()} 优雅关闭所有调度资源</li>
* </ul>
*
* <p>使用示例：
* <pre>{@code
* TimeScheduler scheduler = TimeScheduler.of();
* scheduler.scheduleCron("daily-task", () -> log("执行"), "0 0 12 * * ?");
* scheduler.scheduleFixedRate("heartbeat", () -> log("心跳"), 5, TimeUnit.SECONDS);
* scheduler.shutdown();
* }</pre>"心跳"), 5, TimeUnit.SECONDS);
* scheduler.shutdown();
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public class TimeScheduler {

    /**
    * 底层调度器提供者
     */
    private final SchedulerProvider provider;

    /**
    * 创建默认的时间调度管理器。
    *
    * <p>使用 {@link JdkSchedulerProvider} 作为默认调度实现。</p>
    *
    * @return TimeScheduler 实例
     */
    public static TimeScheduler of() {
        return new TimeScheduler();
    }

    /**
    * 创建默认的时间调度管理器。
     */
    public TimeScheduler() {
        this(new JdkSchedulerProvider());
    }

    /**
    * 创建指定调度器提供者的时间调度管理器
    *
    * <p>允许注入自定义的 {@link SchedulerProvider} 实现，用于扩展或替换调度策略。
    *
    * @param provider 调度器提供者
     */
    public TimeScheduler(SchedulerProvider provider) {
        this.provider = provider;
    }

    /**
    * 调度一个任务（指定 标识 和触发器）
    *
    * @param id      任务唯一标识
    * @param task    待执行的任务逻辑
    * @param trigger 触发策略
    * @return 已调度的任务实例
     */
    public ScheduledTask schedule(String id, Runnable task, Trigger trigger) {
        return provider.schedule(id, task, trigger);
    }

    /**
    * 调度一个任务（自动生成 标识 和指定触发器）
    *
    * @param task    待执行的任务逻辑
    * @param trigger 触发策略
    * @return 已调度的任务实例
     */
    public ScheduledTask schedule(Runnable task, Trigger trigger) {
        return provider.schedule(task, trigger);
    }

    /**
    * 调度一个 Cron 任务（指定 标识）
    *
    * <p>基于标准 6 字段 Cron 表达式调度任务。
    *
    * @param id   任务唯一标识
    * @param task 待执行的任务逻辑
    * @param cron 标准 6 字段 Cron 表达式
    * @return 已调度的任务实例
     */
    public ScheduledTask scheduleCron(String id, Runnable task, String cron) {
        return provider.schedule(id, task, new CronTrigger(cron));
    }

    /**
    * 调度一个 Cron 任务（自动生成 标识）
    *
    * @param task 待执行的任务逻辑
    * @param cron 标准 6 字段 Cron 表达式
    * @return 已调度的任务实例
     */
    public ScheduledTask scheduleCron(Runnable task, String cron) {
        return provider.schedule(task, new CronTrigger(cron));
    }

    /**
    * 调度一个固定频率任务（指定 标识，无初始延迟）
    *
    * @param id       任务唯一标识
    * @param task     待执行的任务逻辑
    * @param interval 执行间隔
    * @param timeUnit 时间单位
    * @return 已调度的任务实例
     */
    public ScheduledTask scheduleFixedRate(String id, Runnable task, long interval, TimeUnit timeUnit) {
        return scheduleFixedRate(id, task, 0, interval, timeUnit);
    }

    /**
    * 调度一个固定频率任务（指定 标识 和初始延迟）
    *
    * @param id           任务唯一标识
    * @param task         待执行的任务逻辑
    * @param initialDelay 首次执行前的初始延迟
    * @param interval     执行间隔
    * @param timeUnit     时间单位
    * @return 已调度的任务实例
     */
    public ScheduledTask scheduleFixedRate(String id, Runnable task, long initialDelay, long interval, TimeUnit timeUnit) {
        return provider.schedule(id, task, new FixedTrigger(initialDelay, interval, timeUnit));
    }

    /**
    * 调度一个固定频率任务（自动生成 标识，无初始延迟）
    *
    * @param task     待执行的任务逻辑
    * @param interval 执行间隔
    * @param timeUnit 时间单位
    * @return 已调度的任务实例
     */
    public ScheduledTask scheduleFixedRate(Runnable task, long interval, TimeUnit timeUnit) {
        return provider.schedule(task, new FixedTrigger(interval, timeUnit));
    }

    /**
    * 重新调度任务（实时变更触发策略）
    *
    * <p>动态修改任务的触发策略，取消当前未执行的 Future，
    * 使用新的触发策略重新计算下一次执行时间并开始调度。
    *
    * @param id      任务唯一标识
    * @param trigger 新的触发策略
    * @return 重新调度后的任务实例，不存在返回 {@code null}
     */
    public ScheduledTask reschedule(String id, Trigger trigger) {
        return provider.reschedule(id, trigger);
    }

    /**
    * 重新调度 Cron 任务（实时变更 Cron 表达式）
    *
    * @param id   任务唯一标识
    * @param cron 新的 Cron 表达式
    * @return 重新调度后的任务实例，不存在返回 {@code null}
     */
    public ScheduledTask rescheduleCron(String id, String cron) {
        return provider.reschedule(id, new CronTrigger(cron));
    }

    /**
    * 重新调度固定频率任务（实时变更时间间隔）
    *
    * @param id       任务唯一标识
    * @param interval 新的执行间隔
    * @param timeUnit 时间单位
    * @return 重新调度后的任务实例，不存在返回 {@code null}
     */
    public ScheduledTask rescheduleFixedRate(String id, long interval, TimeUnit timeUnit) {
        return provider.reschedule(id, new FixedTrigger(interval, timeUnit));
    }

    /**
    * 取消指定 标识 的调度任务
    *
    * @param id 任务唯一标识
    * @return 如果存在该任务并成功取消返回 {@code true}，否则返回 {@code false}
     */
    public boolean cancel(String id) {
        return provider.cancel(id);
    }

    /**
    * 检查指定 标识 的调度任务是否正在运行
    *
    * @param id 任务唯一标识
    * @return 如果任务存在且未被取消返回 {@code true}，否则返回 {@code false}
     */
    public boolean isRunning(String id) {
        return provider.isRunning(id);
    }

    /**
    * 获取所有已调度的任务
    *
    * @return 已调度任务列表
     */
    public List<ScheduledTask> getScheduledTasks() {
        return provider.getScheduledTasks();
    }

    /**
    * 关闭调度管理器
    *
    * <p>委托给底层的 {@link SchedulerProvider#shutdown()} 执行优雅关闭。
     */
    public void shutdown() {
        provider.shutdown();
    }

    /**
    * 获取底层调度器提供者
    *
    * @return 调度器提供者实例
     */
    public SchedulerProvider getProvider() {
        return provider;
    }
}
