package com.chua.common.support.task.timer;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
* 哈希时间轮实现（哈希 Wheel 定时器）。
*
* <p>基于环形槽位数组 + 双向链表，tick 线程按<strong>绝对时间轴</strong>推进
* （任务耗时不会造成周期漂移，落后时连续补扫），到期任务提交到独立虚拟线程执行器并发运行。</p>
*
* <p>并发语义：</p>
* <ul>
*   <li><strong>分槽锁</strong> — 每槽独立 {@link ReentrantLock}，注册/取消/扫描互不阻塞</li>
*   <li><strong>任务并发执行</strong> — 不同任务可能同时运行，无顺序保证；
*       慢任务不阻塞轮子推进</li>
*   <li><strong>cancel 可中断在途执行</strong> — 通过 Future.cancel(true) 向业务线程发送中断，
*       业务体需响应中断方可真正停止</li>
*   <li><strong>单任务故障隔离</strong> — 任何 Throwable 都在提交侧兜底记录，不影响时间轮存活</li>
* </ul>
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
    * 环形槽位（每个槽：持有 任务 双向链表）
     */
    private final Slot[] wheel;

    /**
    * 当前 tick 指针（绝对时间轴推进）
     */
    private volatile long currentTick;

    /**
    * 运行标志
     */
    private volatile boolean running;

    /**
    * tick 工作线程引用（用于 关闭 时中断睡眠立即退出）
     */
    private volatile Thread tickThread;

    /**
    * 到期任务执行器（虚拟线程 per 任务，慢任务互不阻塞）
     */
    private final ExecutorService taskExecutor;

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
        this.taskExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("wheel-task-", 0).factory());
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
        wheel[index].add(task);
        task.slotIndex = index;
        return true;
    }

    /**
    * 计算目标槽位偏移。
    *
    * <p>规则：delay &le; 0（已到期）取 1 —— 挂到最近的下一槽尽快补触发，
    * 而非等完整一圈；否则按 ceil(延迟 / tickmillis) 向上取整精确落位。</p>
    * @param delayMillis 延迟millis
    * @return computeslot偏移量的结果
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
 // 时间轮已关闭时拒绝调度，返回 空 由调用方感知
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
        wheel[slot].remove(task);
        return true;
    }

    @Override
    public long getTickCount() {
        return currentTick;
    }

    /**
    * 获取当前在轮任务总数（各槽原子计数的即时加和，弱一致快照）。
     */
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
        // 中断全部在途任务并停止接收新任务
        taskExecutor.shutdownNow();
    }

    /**
    * 启动 tick 循环线程。
    *
    * <p>tick 按<strong>绝对时间轴</strong>推进：以启动时刻为基准计算每个 tick 的
    * 理论唤醒点，任务耗时不会造成漂移；若落后则连续补扫追赶。
    * 到期任务先做周期重排（时间轴优先，不丢拍），再提交执行器并发运行。</p>
     */
    private void startTickThread() {
        Thread t = new Thread(() -> {
            long startNanos = System.nanoTime();
            while (running) {
                long targetNanos = startNanos + (currentTick + 1) * tickMillis * 1_000_000L;
                long waitMillis = (targetNanos - System.nanoTime()) / 1_000_000L;
                try {
                    if (waitMillis > 0) {
                        ThreadUtils.sleep(waitMillis);
                    }
                    int idx = (int) (currentTick % slots);
                    List<TimerTask> due = wheel[idx].drain();
                    for (TimerTask tt : due) {
                        // 周期重排在提交执行前完成：fixed-rate 语义，不因执行慢而丢拍
                        if (!tt.isCancelled() && tt.getPeriod() > 0) {
                            tt.advanceDeadline();
                            schedule(tt);
                        }
                        submitTask(tt);
                    }
                    currentTick++;
                } catch (Throwable tickFailure) {
                    // tick 循环最终防线：任何非中断异常仅记录，循环继续
                    safeLogError("tick-loop", tickFailure);
                }
            }
        }, "hashed-wheel-tick");
        t.setDaemon(true);
        tickThread = t;
        t.start();
    }

    /**
    * 提交到期任务到执行器：任何 Throwable 都在包装层兜底记录，
    * 保证单任务故障不影响时间轮与其他任务。
    *
    * <p>使用手工构造的 {@link FutureTask}：<strong>先绑定 future 再入队</strong>，
    * 消除"任务已启动但 cancel 读不到 期货"的竞态窗口。</p>
    * @param task 任务
     */
    private void submitTask(TimerTask task) {
        var futureTask = new FutureTask<Void>(() -> {
            if (task.isCancelled()) {
                return;
            }
            try {
                task.run();
            } catch (Throwable failure) {
                safeLogError(task.getName(), failure);
            }
        }, null);
        task.setRunningFuture(futureTask);
        try {
            taskExecutor.execute(futureTask);
        } catch (Throwable rejection) {
            // 关闭瞬间提交被拒绝：降级记录，不影响时间轮
            safeLogError(task.getName(), rejection);
        }
    }

    /**
    * 安全错误记录：SLF4J 输出失败时降级 stderr，
    * 确保日志系统自身的故障不会反噬时间轮线程。
    * @param taskName 任务名称
    * @param failure 失败
     */
    private static void safeLogError(String taskName, Throwable failure) {
        try {
            log.error("[HashedWheelTimer] 任务执行失败: {}", taskName, failure);
        } catch (Throwable loggingFailure) {
            System.err.println("[HashedWheelTimer] 任务执行失败(日志系统不可用): "
                    + taskName + " -> " + failure);
        }
    }

    /**
    * 槽位：持有 任务 的双向链表，独立槽锁保护（分槽细粒度并发）。
    * @author CH
    * @since 4.0.0
     */
    static class Slot {

        /**
        * 槽首指针
         */
        private TaskNode head;

        /**
        * 槽内任务计数（原子读，支持无锁统计）
         */
        private final AtomicInteger size = new AtomicInteger();

        /**
        * 尾指针
         */
        private TaskNode tail;

        /**
        * 槽内互斥锁
         */
        private final ReentrantLock lock = new ReentrantLock();

        /**
        * 添加任务到槽尾。
        *
        * @param task 任务
         */
        void add(TimerTask task) {
            lock.lock();
            try {
                TaskNode node = new TaskNode(task);
                node.prev = tail;
                if (tail != null) {
                    tail.next = node;
                } else {
                    head = node;
                }
                tail = node;
                task.node = node;
                size.incrementAndGet();
            } finally {
                lock.unlock();
            }
        }

        /**
        * 从槽中移除指定任务。
        *
        * @param task 任务
         */
        void remove(TimerTask task) {
            lock.lock();
            try {
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
                size.decrementAndGet();
            } finally {
                lock.unlock();
            }
        }

        /**
        * 弹出并清空当前槽所有任务。
        *
        * @return 到期列表
         */
        List<TimerTask> drain() {
            List<TimerTask> out = new ArrayList<>();
            lock.lock();
            try {
                TaskNode cur = head;
                while (cur != null) {
                    out.add(cur.taskFromWheel);
                    cur = cur.next;
                }
                head = null;
                tail = null;
                size.set(0);
            } finally {
                lock.unlock();
            }
            return out;
        }

        /**
        * 槽内任务数（原子快照）。
        *
        * @return 数量
         */
        int size() {
            return size.get();
        }
    }

    /**
    * 双向链表节点。
    * @author CH
    * @since 4.0.0
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
