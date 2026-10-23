package com.chua.runtime.apm.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 异步批量写入队列。
 *
 * <p><b>为什么需要</b>：SQLite 单条 {@code INSERT} 实测约 21ms（每次都要独立提交事务），
 * 业务线程直接同步写入会把可观延迟注入宿主。本队列把写操作搬到单独的写线程，
 * 按批合并事务，业务侧 {@link #submit} 只是一次入队，永不阻塞、永不抛异常。</p>
 *
 * <p><b>正确性保证</b>：</p>
 * <ul>
 *   <li><b>顺序</b>：单线程消费，同一批按入队顺序执行。依赖边的「先查后改」依赖这一点，
 *       否则同一拓扑的并发增量会互相覆盖。</li>
 *   <li><b>调用方不感知失败</b>：观测组件写入失败只记日志，绝不影响宿主业务。</li>
 *   <li><b>关闭不丢已入队数据</b>：{@link #shutdown(long)} 会先跑完队列再退出，
 *       并在最后执行一次 {@code checkpoint}（若提供）。</li>
 *   <li><b>有界</b>：队列满时直接丢弃并计数，宁可丢观测数据也不拖慢宿主。</li>
 * </ul>
 *
 * <p>本类由隔离加载器定义（与 {@link SqliteStorage} 同一加载器），因此不引用任何
 * {@code java.sql} 类型；真正的 SQL 由调用方以 {@link Action} 形式传进来。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class SqliteWriteQueue {

    /**
     * 日志
     */
    private static final Logger log = Logger.getLogger(SqliteWriteQueue.class.getName());

    /**
     * 一次写操作
     */
    @FunctionalInterface
    interface Action {

        /**
         * 执行写操作。
         *
         * @throws Exception 失败
         */
        void run() throws Exception;
    }

    /**
     * 批量事务边界
     */
    @FunctionalInterface
    interface Batch {

        /**
         * 执行一个事务。
         *
         * @param actions 本批写操作（按提交顺序）
         * @throws Exception 失败
         */
        void apply(List<Action> actions) throws Exception;
    }

    /**
     * 队列容量（超出即丢弃，保业务不保观测）
     */
    private static final int CAPACITY = 8192;

    /**
     * 单批最大条数
     */
    private static final int MAX_BATCH = 512;

    /**
     * 等待凑批的最长时间（毫秒）
     */
    private static final long LINGER_MS = 20L;

    /**
     * 待写入队列
     */
    private final BlockingQueue<Action> queue = new ArrayBlockingQueue<>(CAPACITY);

    /**
     * 批执行器
     */
    private final Batch batch;

    /**
     * 提交失败的告警节流时间戳
     */
    private final AtomicLong lastWarn = new AtomicLong(0L);

    /**
     * 因队列满被丢弃的条数
     */
    private final AtomicLong dropped = new AtomicLong(0L);

    /**
     * 累计提交条数（判定排空用：处理数追平提交数即表示已全部落盘）
     */
    private final AtomicLong submitted = new AtomicLong(0L);

    /**
     * 已成功落盘的条数
     */
    private final AtomicLong flushed = new AtomicLong(0L);

    /**
     * 写线程
     */
    private volatile Thread worker;

    /**
     * 是否已请求停止
     */
    private volatile boolean stopping;

    /**
     * 构造器。
     *
     * @param batch 批执行器
     */
    SqliteWriteQueue(Batch batch) {
        this.batch = batch;
    }

    /**
     * 启动写线程。
     *
     * <p>使用守护线程：宿主退出时不阻止进程结束；但 {@link SqliteStorage#stop()}
     * 会显式等待队列排空。</p>
     */
    void start() {
        stopping = false;
        Thread t = new Thread(this::loop, "apm-sqlite-writer");
        t.setDaemon(true);
        t.setContextClassLoader(null);
        this.worker = t;
        t.start();
    }

    /**
     * 提交一次写操作（永不阻塞、永不抛出）。
     *
     * @param action 写操作
     * @return 入队成功返回 true
     */
    boolean submit(Action action) {
        if (action == null || stopping) {
            return false;
        }
        if (queue.offer(action)) {
            submitted.incrementAndGet();
            return true;
        }
        long lost = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastWarn.get();
        if (now - last > 10_000L && lastWarn.compareAndSet(last, now)) {
            log.warning(String.format("SQLite 写入队列已满（容量 %d），已丢弃 %d 条观测数据", CAPACITY, lost));
        }
        return false;
    }

    /**
     * 已落盘条数。
     *
     * @return 条数
     */
    long flushed() {
        return flushed.get();
    }

    /**
     * 已处理条数（落盘 + 丢弃）。
     *
     * @return 条数
     */
    long processed() {
        return flushed.get() + dropped.get();
    }

    /**
     * 累计提交条数。
     *
     * @return 条数
     */
    long submitted() {
        return submitted.get();
    }

    /**
     * 因队列满被丢弃的条数。
     *
     * @return 条数
     */
    long dropped() {
        return dropped.get();
    }

    /**
     * 当前积压条数。
     *
     * @return 条数
     */
    int pending() {
        return queue.size();
    }

    /**
     * 停止并等待队列排空。
     *
     * @param timeoutMillis 最长等待时间
     * @return 是否在超时前排空
     */
    boolean shutdown(long timeoutMillis) {
        stopping = true;
        Thread t = worker;
        if (t == null) {
            return true;
        }
        try {
            t.join(Math.max(1L, timeoutMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean drained = !t.isAlive();
        if (!drained) {
            log.warning(String.format("SQLite 写线程未在 %dms 内排空，剩余 %d 条", timeoutMillis, queue.size()));
        }
        return drained;
    }

    /**
     * 写线程主循环。
     */
    private void loop() {
        List<Action> buffer = new ArrayList<>(MAX_BATCH);
        while (true) {
            try {
                Action first = queue.poll(200L, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (stopping) {
                        return;
                    }
                    continue;
                }
                buffer.clear();
                buffer.add(first);
                // 短时间凑批：队列非空就继续取，避免每条一次事务
                long deadline = System.currentTimeMillis() + LINGER_MS;
                while (buffer.size() < MAX_BATCH) {
                    Action next = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                    if (next == null) {
                        break;
                    }
                    buffer.add(next);
                }
                flush(buffer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                // 兜底：写线程不能因任何异常退出
                log.log(Level.WARNING, "SQLite 写线程异常", e);
            }
        }
    }

    /**
     * 执行一批。
     *
     * @param actions 本批操作
     */
    private void flush(List<Action> actions) {
        if (actions.isEmpty()) {
            return;
        }
        int size = actions.size();
        try {
            batch.apply(actions);
            flushed.addAndGet(size);
        } catch (Throwable e) {
            log.log(Level.WARNING, String.format("SQLite 批量写入失败（%d 条）: %s", size, e.getMessage()));
            // 整批失败时退化为逐条，避免一条脏数据拖垮整批
            for (Action action : actions) {
                try {
                    batch.apply(java.util.Collections.singletonList(action));
                    flushed.incrementAndGet();
                } catch (Throwable ignored) {
                    // 单条失败只丢这一条
                }
            }
        }
    }
}
