package com.chua.common.support.task.scheduler;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 调度器提供者接口
 *
 * <p>定义任务调度服务的核心 API，采用 SPI 机制实现，允许不同的调度实现进行扩展和替换。
 * 提供者负责管理任务的注册、触发、取消和生命周期管理。
 *
 * <p>核心职责：
 * <ul>
 *   <li>将任务与触发器绑定，按照触发策略执行任务</li>
 *   <li>管理多个调度任务的生命周期</li>
 *   <li>提供任务执行状态的查询和取消能力</li>
 *   <li>统一管理线程资源和优雅关闭</li>
 * </ul>
 *
 * <p>默认 JDK 实现：{@link JdkSchedulerProvider}
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public interface SchedulerProvider {

    /**
     * 调度一个任务（自动生成任务 ID）
     *
     * <p>为任务自动生成唯一的标识符，并按照触发器的策略执行。
     *
     * @param task    待执行的任务逻辑
     * @param trigger 触发策略
     * @return 已调度的任务实例
     */
    ScheduledTask schedule(Runnable task, Trigger trigger);

    /**
     * 调度一个任务（指定任务 ID）
     *
     * <p>使用指定的标识符注册任务。如果该 ID 已存在对应的调度任务，
     * 会先取消旧任务再注册新任务。
     *
     * @param id      任务唯一标识
     * @param task    待执行的任务逻辑
     * @param trigger 触发策略
     * @return 已调度的任务实例
     */
    ScheduledTask schedule(String id, Runnable task, Trigger trigger);

    /**
     * 重新调度任务（实时变更触发策略）
     *
     * <p>在任务运行过程中动态修改触发策略。调度器会取消当前未执行的 Future，
     * 使用新的触发策略重新计算下一次执行时间并开始调度。
     *
     * <p>如果指定 ID 的任务不存在，则返回 {@code null}。
     *
     * @param id      任务唯一标识
     * @param trigger 新的触发策略
     * @return 重新调度后的任务实例，不存在返回 {@code null}
     */
    ScheduledTask reschedule(String id, Trigger trigger);

    /**
     * 取消指定 ID 的调度任务
     *
     * <p>取消后任务将不再触发后续执行。如果任务正在执行中，
     * 会尝试中断执行线程。
     *
     * @param id 任务唯一标识
     * @return 如果存在该任务并成功取消返回 {@code true}，否则返回 {@code false}
     */
    boolean cancel(String id);

    /**
     * 检查指定 ID 的调度任务是否正在运行
     *
     * @param id 任务唯一标识
     * @return 如果任务存在且未被取消返回 {@code true}，否则返回 {@code false}
     */
    boolean isRunning(String id);

    /**
     * 关闭调度器
     *
     * <p>执行优雅关闭，依次执行以下操作：
     * <ol>
     *   <li>取消所有已调度的任务</li>
     *   <li>清空任务注册表</li>
     *   <li>关闭虚拟线程执行器</li>
     *   <li>关闭定时任务调度器</li>
     * </ol>
     *
     * <p>关闭后，调度器不再接受新的调度请求。
     */
    void shutdown();

    /**
     * 检查调度器是否正在运行
     *
     * @return 如果调度器尚未关闭返回 {@code true}，否则返回 {@code false}
     */
    boolean isRunning();

    /**
     * 获取所有已调度的任务
     *
     * @return 已调度任务列表，不会为 {@code null}
     */
    List<ScheduledTask> getScheduledTasks();
}
