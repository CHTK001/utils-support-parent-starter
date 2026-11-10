package com.chua.common.support.task.cooldown;

import com.chua.common.support.task.timer.Timer;
import com.chua.common.support.task.timer.TimerTask;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于时间轮的冷却到期调度器：到期主动回调，同到期时刻按登记顺序先进先出。
 *
 * <p><b>为什么是「时间轮 + 延迟队列」两段</b>：时间轮负责大规模登记的 O(1) 落位与
 * 到期唤醒；但时间轮到点后由虚拟线程并发执行，<b>同一批任务谁先跑不保证</b>。
 * 冷却恢复要求「先冷却的账号先恢复」，于是到期回调只做一件小事 —— 把任务放进
 * {@link DelayQueue}，由单独一条守护线程按 {@code (到期时刻, 登记序号)} 严格出队执行。
 * 这样既拿到时间轮的调度效率，又拿到确定的先进先出顺序。</p>
 *
 * <p>同一个 {@code scope + key} 重新登记时，旧的时间轮任务与队列项都会被取消，
 * 避免「连续失败改到期时间」出现提前恢复。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
@Slf4j
public final class TimeWheelExpiryScheduler implements ExpiryScheduler {

    /**
     * 时间轮槽位数（2 的幂）
     */
    private static final int SLOTS = 512;

    /**
     * 时间轮 tick 间隔（毫秒）：冷却以秒计，10ms 精度足够
     */
    private static final long TICK_MILLIS = 10L;

    /**
     * 时间轮
     */
    private final Timer timer = Timer.newTimer(SLOTS, TICK_MILLIS, TimeUnit.MILLISECONDS);

    /**
     * 已登记任务：{@code scope|key} → 任务
     */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>(16);

    /**
     * 到期派发队列：按 (到期时刻, 序号) 排序，保证先进先出
     */
    private final DelayQueue<Ready> ready = new DelayQueue<>();

    /**
     * 登记序号
     */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 派发线程
     */
    private final ExecutorService dispatcher;

    /**
     * 运行标志
     */
    private volatile boolean running = true;

    /**
     * 创建调度器并启动派发线程。
     */
    public TimeWheelExpiryScheduler() {
        this.dispatcher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cooldown-recovery");
            thread.setDaemon(true);
            return thread;
        });
        this.dispatcher.execute(this::dispatchLoop);
    }

    @Override
    public void schedule(String scope, String key, long delayMillis, Runnable onExpire) {
        if (!running || onExpire == null) {
            return;
        }
        String id = idOf(scope, key);
        cancelPending(id);
        long delay = Math.max(0L, delayMillis);
        Ready entry = new Ready(id, System.currentTimeMillis() + delay, sequence.incrementAndGet(), onExpire);
        TimerTask task = timer.schedule(() -> ready.offer(entry), delay, TimeUnit.MILLISECONDS);
        pending.put(id, new Pending(task, entry));
    }

    @Override
    public void cancel(String scope, String key) {
        cancelPending(idOf(scope, key));
    }

    @Override
    public void clearScope(String scope) {
        String prefix = (scope == null ? "default" : scope) + "|";
        for (String id : pending.keySet()) {
            if (id.startsWith(prefix)) {
                cancelPending(id);
            }
        }
    }

    /**
     * 当前已登记且未到期的任务数，供监控 / 测试断言。
     *
     * @return 任务数
     */
    public int size() {
        return pending.size();
    }

    @Override
    public void shutdown() {
        running = false;
        pending.keySet().forEach(this::cancelPending);
        pending.clear();
        timer.shutdown();
        dispatcher.shutdownNow();
    }

    /**
     * 取消一个已登记任务（时间轮任务 + 派发队列项）。
     *
     * @param id 作用域|键
     */
    private void cancelPending(String id) {
        Pending old = pending.remove(id);
        if (old == null) {
            return;
        }
        old.entry.cancelled = true;
        if (old.task != null) {
            timer.cancel(old.task);
        }
        ready.remove(old.entry);
    }

    /**
     * 派发循环：延迟队列按 (到期时刻, 序号) 出队，严格先进先出。
     */
    private void dispatchLoop() {
        while (running) {
            try {
                Ready entry = ready.take();
                if (entry.cancelled) {
                    continue;
                }
                pending.remove(entry.id);
                try {
                    entry.action.run();
                } catch (Exception e) {
                    log.warn("[Cooldown] 到期回调执行失败 {}: {}", entry.id, e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 拼任务标识。
     *
     * @param scope 作用域
     * @param key   冷却键
     * @return 标识
     */
    private static String idOf(String scope, String key) {
        return (scope == null ? "default" : scope) + "|" + key;
    }

    /**
     * 已登记任务句柄。
     *
     * @param task  时间轮任务
     * @param entry 派发项
     */
    private record Pending(TimerTask task, Ready entry) {
    }

    /**
     * 派发项：按到期时刻、再按登记序号排序。
     */
    private static final class Ready implements Delayed {

        /**
         * 任务标识
         */
        private final String id;

        /**
         * 到期时刻（毫秒）
         */
        private final long deadline;

        /**
         * 登记序号
         */
        private final long sequence;

        /**
         * 到期动作
         */
        private final Runnable action;

        /**
         * 是否已取消
         */
        private volatile boolean cancelled;

        /**
         * 构造。
         *
         * @param id       标识
         * @param deadline 到期时刻
         * @param sequence 序号
         * @param action   动作
         */
        private Ready(String id, long deadline, long sequence, Runnable action) {
            this.id = id;
            this.deadline = deadline;
            this.sequence = sequence;
            this.action = action;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            Ready that = (Ready) other;
            int byDeadline = Long.compare(deadline, that.deadline);
            return byDeadline != 0 ? byDeadline : Long.compare(sequence, that.sequence);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Ready that && sequence == that.sequence;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(sequence);
        }

        @Override
        public String toString() {
            return "Ready{" + id + ", deadline=" + deadline + ", seq=" + sequence + '}';
        }
    }

    /**
     * 便于日志排查：当前登记任务数。
     *
     * @return 摘要
     */
    @Override
    public String toString() {
        return "TimeWheelExpiryScheduler{pending=" + pending.size() + '}';
    }
}
