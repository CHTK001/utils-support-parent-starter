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
        long slotDistance = delay <= 0 ? 0 : (delay / tickMillis + 1);
        int index = (int) ((currentTick + slotDistance) % slots);
        lock.lock();
        try {
            wheel[index].add(task);
            task.slotIndex = index;
        } finally {
            lock.unlock();
        }
        return true;
    }

    @Override
    public TimerTask schedule(Runnable task, long delay, TimeUnit timeUnit) {
        long deadline = System.currentTimeMillis() + timeUnit.toMillis(delay);
        TimerTask wrapped = new TimerTask(UUID.randomUUID().toString(), "delay-" + delay + timeUnit,
                task, deadline);
        schedule(wrapped);
        return wrapped;
    }

    @Override
    public TimerTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit timeUnit) {
        long delay = timeUnit.toMillis(initialDelay);
        long periodMs = timeUnit.toMillis(period);
        long deadline = System.currentTimeMillis() + delay;
        TimerTask wrapped = new TimerTask(UUID.randomUUID().toString(), "periodic-" + period + timeUnit,
                task, deadline, periodMs);
        schedule(wrapped);
        return wrapped;
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
    }

    /**
     * 启动 tick 循环线程。
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
                        tt.run();
                        if (tt.getPeriod() > 0 && !tt.isCancelled()) {
                            long next = tt.getDeadline() + tt.getPeriod();
                            TimerTask nextTask = new TimerTask(
                                    tt.getId(), tt.getName(), tt.getTask(), next, tt.getPeriod());
                            schedule(nextTask);
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
