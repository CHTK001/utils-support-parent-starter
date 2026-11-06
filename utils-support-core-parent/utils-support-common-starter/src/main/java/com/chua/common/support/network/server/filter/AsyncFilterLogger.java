package com.chua.common.support.network.server.filter;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 过滤器日志异步写出器。
 *
 * <p>{@link AccessLogServerFilter} / {@link TracingServerFilter} 会在每个请求上产出
 * 一条日志。若直接在请求线程里格式化并写盘，日志 IO 会串进请求路径：实测 echo 12 字节、
 * 20 连接时，每请求一行 INFO 会把 handler 平均耗时从约 2µs 拉到 110~150µs，吞吐掉一半以上。</p>
 *
 * <p>因此这里把「格式化 + 写盘」整体搬到独立守护线程：请求线程只负责把任务放进有界队列，
 * 队列满时丢弃该条日志并计数（宁可少记日志，也不阻塞请求）。</p>
 *
 * <p>队列容量可用 {@code -Dserver.filterlog.capacity=32768} 调整，默认 16384。</p>
 *
 * @author CH
 * @since 2026/11/06
 */
final class AsyncFilterLogger {

    /**
     * 日志对象。仅用于上报丢弃情况，不参与请求路径。
     */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AsyncFilterLogger.class);

    /**
     * 队列容量。
     */
    private static final int CAPACITY = Integer.getInteger("server.filterlog.capacity", 16384);

    /**
     * 每丢弃这么多条上报一次 WARN，避免静默丢日志无人察觉。
     */
    private static final long DROP_REPORT_STEP = 10_000;

    /**
     * 待写日志任务队列。
     */
    private static final BlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<>(CAPACITY);

    /**
     * 因队列满被丢弃的日志条数。
     */
    private static final AtomicLong DROPPED = new AtomicLong();

    /**
     * 已上报到的丢弃计数水位。
     */
    private static final AtomicLong REPORTED = new AtomicLong();

    static {
        Thread writer = new Thread(AsyncFilterLogger::drain, "server-filter-log");
        writer.setDaemon(true);
        writer.start();
    }

    private AsyncFilterLogger() {
    }

    /**
     * 提交一条日志任务。
     *
     * <p>非阻塞：队列满时直接丢弃，调用线程永远不会被日志拖慢。</p>
     *
     * @param task 日志任务，不允许为 null
     */
    static void submit(Runnable task) {
        if (task == null) {
            return;
        }
        if (!QUEUE.offer(task)) {
            DROPPED.incrementAndGet();
        }
    }

    /**
     * 获取被丢弃的日志条数，用于观测队列是否长期打满。
     *
     * @return 丢弃条数
     */
    static long getDroppedCount() {
        return DROPPED.get();
    }

    /**
     * 消费队列，单条任务异常不影响后续。
     */
    private static void drain() {
        for (; ; ) {
            Runnable task;
            try {
                task = QUEUE.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                task.run();
            } catch (Throwable ignored) {
                // 单条日志写出失败(格式化异常/仓库异常)与请求无关，忽略并继续。
            }
            reportDropped();
        }
    }

    /**
     * 丢弃量每跨过 {@link #DROP_REPORT_STEP} 条上报一次。
     *
     * <p>在写出线程执行，不影响请求；日志本身写不出去时的异常同样吞掉，
     * 避免上报变成新的故障点。</p>
     */
    private static void reportDropped() {
        long dropped = DROPPED.get();
        long reported = REPORTED.get();
        if (dropped - reported < DROP_REPORT_STEP) {
            return;
        }
        if (REPORTED.compareAndSet(reported, dropped)) {
            try {
                LOG.warn("过滤器日志队列已满，累计丢弃 {} 条(容量 {}，可用 -Dserver.filterlog.capacity 调整)",
                        dropped, CAPACITY);
            } catch (Throwable ignored) {
                // 上报失败不影响写出线程
            }
        }
    }
}
