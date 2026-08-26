package com.chua.common.support.task.timer;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 哈希时间轮实现（Hashed Wheel Timer）。
 *
 * <p>基于环形槽位数组 + 双向链表，每个槽处理到期任务。tick 线程每 {@code tickDuration} 扫一个槽，
 * 到期任务在主线程异步回调。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class HashedWheelTimer implements Timer {

    /**
     * 槽位数
     */
    private final int slots;

    /**
     * 每 tick 持续时间（毫秒）
     */
    private final long tickMillis;

    /**
     * 环形槽位（每个槽：持有 Task 双向链表）
     */
    private final Slot[] wheel;

    /**
     * 当前 tick 指针
     */
    private volatile long currentTick;

    /**
     * 运行标志
     */
    private volatile boolean running;

    /**
     * tick 工作线程引用（用于 shutdown 时中断睡眠立即退出）
     */
    private volatile Thread tickThread;

    /**
     * 槽位锁（粗粒度，工程阶段简单实现）
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 构造。
     *
     * @param slots      槽位数
     * @param tickDuration tick 间隔
     * @param unit       时间单位
     */
    public HashedWheelTimer(int slots, long tickDuration, TimeUnit unit) {
        if (slots <= 0) {
            throw new IllegalArgumentException("slots 必须 > 0");
        }
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration 必须 > 0");
        }
        this.slots = slots;
        this.tickMillis = unit.toMillis(tickDuration);
        this.wheel = new Slot[slots];
        for (int i = 0; i < slots; i++) {
            wheel[i] = new Slot();
        }
        this.running = true;
        startTickThread();
    }

    @Override
    public boolean schedule(TimerTask task) {
        if (!running || task == null || task.isCancelled()) {
            return false;
        }
        long delay = task.getDeadline() - System.currentTimeMillis();
        int index = (int) ((currentTick + computeSlotOffset(delay)) % slots);
        lock.lock();
        try {
            wheel[index].add(task);
            task.slotIndex = index;
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * 计算目标槽位偏移。
     *
     * <p>规则：delay &le; 0（已到期）取 1 —— 挂到最近的下一槽尽快补触发，
     * 而非等完整一圈；否则按 ceil(delay / tickMillis) 向上取整精确落位。</p>
     */
    private int computeSlotOffset(long delayMillis) {
        if (delayMillis <= 0) {
            return 1;
        }
        long offset = (delayMillis + tickMillis - 1) / tickMillis;
        return (int) Math.min(offset, Integer.MAX_VALUE);
    }

    @Override
    public TimerTask schedule(Runnable task, long delay, TimeUnit timeUnit) {
        long deadline = System.currentTimeMillis() + timeUnit.toMillis(delay);
        TimerTask wrapped = new TimerTask(UUID.randomUUID().toString(), "delay-" + delay + timeUnit,
                task, deadline);
        // 时间轮已关闭时拒绝调度，返回 null 由调用方感知
        return schedule(wrapped) ? wrapped : null;
    }

    @Override
    public TimerTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit timeUnit) {
        long delay = timeUnit.toMillis(initialDelay);
        long periodMs = timeUnit.toMillis(period);
        long deadline = System.currentTimeMillis() + delay;
        TimerTask wrapped = new TimerTask(UUID.randomUUID().toString(), "periodic-" + period + timeUnit,
                task, deadline, periodMs);
        return schedule(wrapped) ? wrapped : null;
    }

    @Override
    public boolean cancel(TimerTask task) {
        if (task == null) {
            return false;
        }
        task.cancel();
        int slot = task.slotIndex;
        if (slot < 0 || slot >= slots) {
            return false;
        }
        lock.lock();
        try {
            wheel[slot].remove(task);
        } finally {
            lock.unlock();
        }
        return true;
    }

    @Override
    public long getTickCount() {
        return currentTick;
    }

    @Override
    public int getTaskCount() {
        int sum = 0;
        for (Slot s : wheel) {
            sum += s.size();
        }
        return sum;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void shutdown() {
        running = false;
        // 中断 tick 线程的睡眠，使关闭立即生效而非等待下一个 tick
        var worker = tickThread;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * 启动 tick 循环线程。
     *
     * <p>单个任务抛出的任何 Throwable（含 Error）都在循环内兜底记录，
     * 保证任务故障不会终止整个时间轮。</p>
     */
    private void startTickThread() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(tickMillis);
                    int idx = (int) (currentTick % slots);
                    List<TimerTask> due = new ArrayList<>();
                    lock.lock();
                    try {
                        due.addAll(wheel[idx].drain());
                    } finally {
                        lock.unlock();
                    }
                    for (TimerTask tt : due) {
                        try {
                            tt.run();
                            // 周期任务复用同一实例推进 deadline 后重排：
                            // cancel 标记随实例传播，取消立即对后续轮次生效
                            if (!tt.isCancelled() && tt.getPeriod() > 0) {
                                tt.advanceDeadline();
                                schedule(tt);
                            }
                        } catch (Throwable taskFailure) {
                            // 单任务故障（含 Error）不得杀死时间轮
                            log.error("[HashedWheelTimer] 任务执行失败: {}", tt.getName(), taskFailure);
                        }
                    }
                    currentTick++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "hashed-wheel-tick");
        t.setDaemon(true);
        tickThread = t;
        t.start();
    }

    /**
     * 槽位：持有 Task 的双向链表。
     */
    static class Slot {

        /**
         * 槽首指针
         */
        private TaskNode head;

        /**
         * 槽内任务计数
         */
        private int size;

        /**
         * 尾指针
         */
        private TaskNode tail;

        /**
         * 添加任务到槽尾。
         *
         * @param task 任务
         */
        void add(TimerTask task) {
            TaskNode node = new TaskNode(task);
            withLock(() -> {
                node.prev = tail;
                if (tail != null) {
                    tail.next = node;
                } else {
                    head = node;
                }
                tail = node;
                task.node = node;
                size++;
            });
        }

        /**
         * 从槽中移除指定任务。
         *
         * @param task 任务
         */
        void remove(TimerTask task) {
            withLock(() -> {
                TaskNode n = (TaskNode) task.node;
                if (n == null) {
                    return;
                }
                if (n.prev != null) {
                    n.prev.next = n.next;
                } else {
                    head = n.next;
                }
                if (n.next != null) {
                    n.next.prev = n.prev;
                } else {
                    tail = n.prev;
                }
                task.node = null;
                size--;
            });
        }

        /**
         * 弹出并清空当前槽所有任务。
         *
         * @return 到期列表
         */
        List<TimerTask> drain() {
            List<TimerTask> out = new ArrayList<>();
            withLock(() -> {
                TaskNode cur = head;
                while (cur != null) {
                    out.add(cur.taskFromWheel);
                    cur = cur.next;
                }
                head = null;
                tail = null;
                size = 0;
            });
            return out;
        }

        /**
         * 槽内任务数。
         *
         * @return 数量
         */
        int size() {
            return size;
        }

        /**
         * 带锁执行（当前简易实现：直接执行，外部已由 lock 保护）。
         *
         * @param r 逻辑
         */
        private void withLock(Runnable r) {
            r.run();
        }
    }

    /**
     * 双向链表节点。
     */
    static class TaskNode {

        /**
         * 承载的任务
         */
        final TimerTask taskFromWheel;

        /**
         * 上一个节点
         */
        TaskNode prev;

        /**
         * 下一个节点
         */
        TaskNode next;

        TaskNode(TimerTask task) {
            this.taskFromWheel = task;
        }
    }
}
