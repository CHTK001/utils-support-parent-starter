package com.chua.common.support.task.timer;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
* 时间轮任务封装。
*
* <p>封装待执行的 {@link Runnable} 及其调度元数据，支持取消和到期回调。
* 每个 定时器任务 在注册时由时间轮分配唯一 标识。
*
* <h3>生命周期</h3>
* <pre>
*   CREATED → SCHEDULED → FIRED → DONE
*                 ↓
*               CANCELLED
* </pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class TimerTask {


    /** 任务唯一标识 */
    private final String id;

    /** 待执行的任务逻辑 */
    private final Runnable task;

    /** 任务名称（用于日志） */
    private final String name;

    /** 到期时间戳（毫秒）；周期任务重排时由时间轮推进 */
    private volatile long deadline;

    /** 重复周期（毫秒），-1 表示单次任务 */
    private final long period;

    /** 状态标记 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 执行次数统计 */
    private final AtomicInteger executeCount = new AtomicInteger(0);

    /** 槽位索引（注册后写入） */
    volatile int slotIndex = -1;

    /** 任务节点（双向链表，对象 持有 哈希wheel定时器.任务节点 以避免循环依赖） */
    volatile Object node;

    /** 在途执行的 期货（提交到任务执行器后写入，cancel 时用于中断执行线程） */
    private volatile Future<?> runningFuture;

    /**
    * 创建单次到期任务。
    *
    * @param id      任务唯一标识
    * @param name    任务名称
    * @param task    任务逻辑
    * @param deadline 到期时间戳（毫秒）
     */
    public TimerTask(String id, String name, Runnable task, long deadline) {
        this(id, name, task, deadline, -1L);
    }

    /**
    * 创建重复周期任务。
    *
    * @param id       任务唯一标识
    * @param name     任务名称
    * @param task     任务逻辑
    * @param deadline 首次到期时间戳（毫秒）
    * @param period   重复周期（毫秒），-1 表示单次
     */
    public TimerTask(String id, String name, Runnable task, long deadline, long period) {
        this.id = id;
        this.name = name != null ? name : id;
        this.task = task;
        this.deadline = deadline;
        this.period = period;
    }

    /**
    * 执行任务逻辑（同步调用，由 tick 线程调用）。
    *
    * @return 执行是否成功（未取消且任务不为 空）
     */
    public boolean run() {
        if (isCancelled()) {
            return false;
        }
        if (task == null) {
            return true;
        }
        executeCount.incrementAndGet();
        try {
            task.run();
            return true;
        } catch (Exception e) {
            log.warn("[TimerTask] {} 执行异常: {}", name, e.getMessage());
            return false;
        }
    }

    /**
    * 取消任务：标记取消并中断在途执行（业务体需响应中断方可真正停止）。
    *
    * @return 之前是否已取消
     */
    public boolean cancel() {
        var previous = cancelled.compareAndSet(false, true);
        var inFlight = runningFuture;
        if (inFlight != null) {
            inFlight.cancel(true);
        }
        return previous;
    }

    /**
    * 绑定在途执行的 期货（仅供时间轮提交任务时调用）。
    *
    * @param future 执行器返回的 期货
     */
    void setRunningFuture(Future<?> future) {
        this.runningFuture = future;
    }

    /**
    * 判断任务是否已取消。
    *
    * @return {@code true} 表示已取消
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
    * 判断任务是否到期。
    *
    * @param now 当前时间戳（毫秒）
    * @return {@code true} 表示已到期
     */
    public boolean isDeadline(long now) {
        return now >= deadline;
    }

    /**
    * 获取下次到期时间（重复任务）。
    *
    * @return 下次 deadline，单次任务返回 -1
     */
    public long nextDeadline() {
        if (period <= 0) {
            return -1L;
        }
        return deadline + period;
    }

    /**
    * 获取任务唯一标识。
    *
    * @return ID
     */
    public String getId() {
        return id;
    }

    /**
    * 获取任务名称。
    *
    * @return 名称
     */
    public String getName() {
        return name;
    }

    /**
    * 获取到期时间戳。
    *
    * @return 毫秒时间戳
     */
    public long getDeadline() {
        return deadline;
    }

    /**
    * 获取执行次数。
    *
    * @return 执行次数
     */
    public int getExecuteCount() {
        return executeCount.get();
    }

    /**
    * 获取重复周期。
    *
    * @return 周期（毫秒），-1 表示单次
     */
    public long getPeriod() {
        return period;
    }

    /**
    * 推进到期时间到下一周期（仅供时间轮重排使用，业务代码勿调）。
     */
    void advanceDeadline() {
        deadline += period;
    }

    /**
    * 获取任务逻辑。
    *
    * @return Runnable
     */
    public Runnable getTask() {
        return task;
    }

    @Override
    public String toString() {
        return "TimerTask{"
                + "id='" + id + '\''
                + ", name='" + name + '\''
                + ", deadline=" + deadline
                + ", period=" + period
                + ", cancelled=" + cancelled
                + ", executeCount=" + executeCount
                + '}';
    }
}
