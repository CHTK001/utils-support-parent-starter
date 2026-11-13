package com.chua.common.support.task.cooldown;

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
 * <p><b>顺序是怎么保证的</b>：派发队列按 {@code (到期时刻, 登记序号)} 排序，
 * 而<b>入队发生在 {@link #schedule} 里、也就是登记线程上</b>。这一点是关键：
 * 早先的实现让时间轮到点后再把任务塞进队列，而时间轮的到期执行是每条任务
 * 一个虚拟线程（见 {@code HashedWheelTimer} 的 {@code Thread.ofVirtual()} 工厂），
 * 同一批任务谁先 offer 完全不保证；派发线程又阻塞在 {@code take()} 上，
 * 谁先入队就被立刻取走 —— 排序根本来不及约束一个还没进队列的元素，
 * 于是「先冷却的账号先恢复」会在高负载下随机失效（实测出现过
 * {@code [k|2, k|1, k|3]}）。</p>
 *
 * <p>改为登记时入队后，队列内容只由登记顺序决定，{@code DelayQueue.take()}
 * 自己会阻塞到队头到期才返回，并且在新元素优先级更高时主动唤醒等待者，
 * 因此出队顺序严格等于 {@code (到期时刻, 序号)}，与线程调度无关。</p>
 *
 * <p>同一个 {@code scope + key} 重新登记时，旧的队列项会被取消并移除，
 * 避免「连续失败改到期时间」出现提前恢复。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
@Slf4j
public final class TimeWheelExpiryScheduler implements ExpiryScheduler {

    /**
     * 已登记任务：{@code scope|key} → 队列项
     */
    // [P3C A20 豁免]
    private final Map<String, Ready> pending = new ConcurrentHashMap<>(16);

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
        // [P3C C1 豁免]
        this.dispatcher = Executors.newSingleThreadExecutor(runnable -> {
            // [P3C C1 豁免]
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
        long delay = Math.max(0L, delayMillis);
        Ready entry = new Ready(id, System.currentTimeMillis() + delay, sequence.incrementAndGet(), onExpire);
        // 必须在登记线程上同步入队 —— 这是出队顺序唯一确定的来源。
        // 若交给时间轮回调去 offer，回调跑在「每任务一个虚拟线程」上，
        // 同批任务谁先入队不可控，而 take() 会立刻取走第一个到达的元素，
        // 排序约束不到那个还没进队列的兄弟，先进先出就地失效。
        //
        // 取消旧项与登记新项也必须原子：并发登记同一个键时，两边各自的
        // 「先取消再登记」都看不到对方还没 put 的项，最终 put 互相覆盖，
        // 被覆盖掉的那个队列项成为孤儿 —— 它照样到期，同一个键就会恢复两次。
        // ConcurrentHashMap.put 返回旧值，正好给出这个原子性。
        // 取消旧项与登记新项合并为一次原子 put：CooldownGate 已按 key 串行调用，
        // 但调度器是公开类，直接调 schedule() 的调用方未必串行；
        // 用 ConcurrentHashMap.put 的返回旧值把「取消 + 登记」做成一步，
        // 避免两个线程各自的「先取消再登记」互相覆盖而留下孤儿队列项。
        Ready previous = pending.put(id, entry);
        if (previous != null) {
            previous.cancelled = true;
            ready.remove(previous);
        }
        ready.offer(entry);
        // 若在这之前已有并发线程取消过本项（它从 pending 里拿到的是 entry、
        // 但此刻队列里还没有它，remove 会落空），这里补一次摘除。
        // 即便漏掉也无害：派发线程会跳过 cancelled 项。
        if (entry.cancelled) {
            ready.remove(entry);
        }
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
        ready.clear();
        // 中断唯一能让阻塞在 take() 上的派发线程立刻退出
        dispatcher.shutdownNow();
    }

    /**
     * 取消一个已登记任务（队列项 + 登记表）。
     *
     * @param id 作用域|键
     */
    private void cancelPending(String id) {
        Ready old = pending.remove(id);
        if (old == null) {
            return;
        }
        old.cancelled = true;
        ready.remove(old);
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
                // 必须带值删除：并发重新登记同一个键时，pending 里已经是新项，
                // 无值 remove 会把这条还没到期的新登记一起抹掉，导致它永不恢复。
                pending.remove(entry.id, entry);
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
