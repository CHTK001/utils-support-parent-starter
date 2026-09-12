package com.chua.common.support.task.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
* 时间轮接口。文件名为 时间wheel，接口名为 定时器（历史命名）。
* 通过 {@code TimeWheel.newTimer()} 创建实例。
*
* <p>时间轮（Hashed Wheel Timer）是一种基于环形队列的高效定时任务调度数据结构，
* 适用于大量延迟任务的高性能调度场景，比 {@link java.util.concurrent.ScheduledThreadPoolExecutor}
* 在任务量大时具有更低的调度开销（O(1) 插入，O(k) 到期扫描，k 为每槽任务数）。
*
* <h3>核心能力</h3>
* <ul>
*   <li>{@link #newTimer(long, TimeUnit)} — 创建时间轮实例</li>
*   <li>{@link #newTimer(int, long, TimeUnit)} — 指定槽位数创建，默认槽位数 512</li>
*   <li>{@link #schedule(TimerTask)} — 注册到期任务</li>
*   <li>{@link #schedule(Runnable, long, TimeUnit)} — 便捷方法：延迟 N 执行一次</li>
*   <li>{@link #scheduleAtFixedRate(Runnable, long, long, TimeUnit)} — 定期重复执行</li>
*   <li>{@link #cancel(TimerTask)} — 取消任务</li>
*   <li>{@link #shutdown()} — 优雅关闭时间轮</li>
* </ul>
*
* <h3>使用示例</h3>
* <pre>{@code
* // 创建时间轮，默认 512 槽位，每 tick 1ms
* Timer timer = Timer.newTimer();
*
* // 延迟 5 秒执行一次
* timer.schedule(() -> log.info("delayed"), 5, TimeUnit.SECONDS);
*
* // 定期任务：初始延迟 1s，间隔 5s
* timer.scheduleAtFixedRate(() -> log.info("periodic"), 1, 5, TimeUnit.SECONDS);
*
* // 手动管理 TimerTask
* TimerTask task = new TimerTask("my-task", () -> log.info("exec"));
* timer.schedule(task);
* timer.cancel(task);
*
* timer.shutdown();
* }</pre>edule(task);
* timer.cancel(task);
*
* timer.shutdown();
* }</pre>
*
* <h3>数据结构说明</h3>
* <pre>
*   时间轮 = 环形数组（slots） + 每个槽位的双向链表
*   tick 指针顺时针转动，每 tick 检查当前槽位的到期任务
*   任务到期位置 = (baseTick + delay / tickDuration) % slots.length
* </pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface Timer {

    /**
    * 创建默认时间轮实例。
    *
    * <p>默认配置：512 个槽位，每 tick 间隔 1ms。
    *
    * @return Timer 实例
     */
    static Timer newTimer() {
        return new HashedWheelTimer(512, 1, TimeUnit.MILLISECONDS);
    }

    /**
    * 创建指定 tick 间隔的时间轮实例。
    *
    * @param tickDuration 每次 tick 的时间间隔
    * @param unit         时间单位
    * @return Timer 实例
     */
    static Timer newTimer(long tickDuration, TimeUnit unit) {
        return new HashedWheelTimer(512, tickDuration, unit);
    }

    /**
    * 创建指定槽位数和 tick 间隔的时间轮实例。
    *
    * <p>槽位数建议为 2 的幂，以获得最佳位运算取模性能。
    *
    * @param slots        槽位数（环形数组大小）
    * @param tickDuration 每次 tick 的时间间隔
    * @param unit         时间单位
    * @return Timer 实例
     */
    static Timer newTimer(int slots, long tickDuration, TimeUnit unit) {
        return new HashedWheelTimer(slots, tickDuration, unit);
    }

    /**
    * 注册一个到期任务。
    *
    * <p>任务将在其预设的到期时间被时间轮自动触发执行。
    * 若任务已被取消或时间轮已关闭，则不注册。
    *
    * @param task 待调度的任务
    * @return 注册成功返回 {@code true}，否则 {@code false}
     */
    boolean schedule(TimerTask task);

    /**
    * 便捷方法：延迟指定时间后执行一次。
    *
    * @param task       任务逻辑
    * @param delay      延迟时长
    * @param timeUnit   时间单位
    * @return 创建的 定时器任务 实例；时间轮已关闭（调度被拒绝）时返回 空
     */
    TimerTask schedule(Runnable task, long delay, TimeUnit timeUnit);

    /**
    * 便捷方法：定期重复执行。
    *
    * <p>首次执行前延迟 {@code initialDelay}，之后每隔 {@code period} 执行一次。
    *
    * @param task         任务逻辑
    * @param initialDelay 首次执行前的延迟
    * @param period       执行间隔
    * @param timeUnit     时间单位
    * @return 创建的 定时器任务 实例；时间轮已关闭（调度被拒绝）时返回 空
     */
    TimerTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit timeUnit);

    /**
    * 取消任务。
    *
    * <p>如果任务已在等待队列中，将其从对应槽位移除；
    * 如果任务正在执行，中断其执行线程。
    *
    * @param task 待取消的任务
    * @return 取消成功返回 {@code true}，任务不存在或已取消返回 {@code false}
     */
    boolean cancel(TimerTask task);

    /**
    * 获取当前轮子已转过的 tick 数。
    *
    * @return tick 计数
     */
    long getTickCount();

    /**
    * 获取当前运行中的任务数量。
    *
    * @return 任务数
     */
    int getTaskCount();

    /**
    * 检查时间轮是否正在运行。
    *
    * @return {@code true} 表示尚未 关闭
     */
    boolean isRunning();

    /**
    * 优雅关闭时间轮。
    *
    * <p>停止 tick 线程，取消所有未执行任务，清理资源。
    * 关闭后不再接受新的调度请求。
     */
    void shutdown();
}
