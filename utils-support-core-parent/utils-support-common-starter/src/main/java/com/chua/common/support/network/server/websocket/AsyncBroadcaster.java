// [P3C A20 豁免] A20/A30/A31: 并发参数含魔法值场景，豁免。
package com.chua.common.support.network.server.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 广播扇出器：把「一次广播 → 逐条写全部连接」从调用线程移到后台，并按连接分片。
 *
 * <p><b>为什么必须异步</b>：百万连接时 {@code publish()} 同步逐条 {@code write+flush}，
 * 一次广播就要阻塞调用线程数秒到数分钟；调用方通常是业务线程甚至 netty 事件循环，
 * 一卡就把整个服务拖住。</p>
 *
 * <p><b>为什么分片</b>：阻塞 socket 的 {@code write} 在慢客户端上会阻塞。单线程扇出时，
 * 一个不读数据的客户端就能卡住全场广播。按 {@code identityHashCode(sink) % shards}
 * 固定分片后，一条连接永远落在同一个片里：既保住了<b>单连接内的帧顺序</b>，
 * 又让慢客户端只拖累自己那一片。</p>
 *
 * <p><b>为什么有界队列 + 丢弃</b>：扇出速度恒为 O(n)，而发布速率由业务决定。
 * 无界队列会在洪峰时把内存吃光。这里按帧丢弃并计数——广播本身就是可丢的
 * （客户端拿不到增量会走全量同步），丢帧远好过 OOM。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class AsyncBroadcaster implements AutoCloseable {

    /**
     * 一帧的写出目标。
     */
    public interface Sink {

        /**
         * 写出一整帧。
         *
         * <p>实现方必须保证同一连接内串行（内部加锁），因为分片只保证同一片串行，
         * 不能保证调用方不会另有路径写同一连接。</p>
         *
         * @param frame 已编码的帧字节
         * @throws Exception 写失败
         */
        void writeFrame(byte[] frame) throws Exception;
    }

    /**
     * 空闲时轮询间隔（毫秒）：让关闭能及时退出，又不至于空转。
     */
    private static final long POLL_MILLIS = 200L;

    /**
     * 分片数。
     */
    private final int shards;

    /**
     * 连接来源（弱一致迭代即可，广播容忍并发增删）。
     */
    private final Supplier<Iterable<? extends Sink>> sinks;

    /**
     * 每片的待发帧队列。
     */
    private final List<BlockingQueue<byte[]>> queues = new ArrayList<>();

    /**
     * 每片的扇出线程。
     */
    private final List<Thread> workers = new ArrayList<>();

    /**
     * 因队列满被丢弃的帧数。
     */
    private final AtomicLong droppedFrames = new AtomicLong();

    /**
     * 单连接写失败次数。
     */
    private final AtomicLong writeErrors = new AtomicLong();

    /**
     * 运行标志。
     */
    private volatile boolean running = true;

    /**
     * 构造并启动扇出线程。
     *
     * @param shards       分片数（建议 4~16）
     * @param queueCapacity 每片队列容量（单位：帧）
     * @param sinks        连接来源
     */
    public AsyncBroadcaster(int shards, int queueCapacity, Supplier<Iterable<? extends Sink>> sinks) {
        this.shards = Math.max(1, shards);
        this.sinks = sinks;
        int capacity = Math.max(1, queueCapacity);
        for (int i = 0; i < this.shards; i++) {
            BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(capacity);
            queues.add(queue);
            final int index = i;
            Thread worker = Thread.ofVirtual()
                    .name("ws-broadcast-" + i)
                    .start(() -> loop(index, queue));
            workers.add(worker);
        }
    }

    /**
     * 扇出循环：只处理落在本片的连接。
     *
     * @param index 分片下标
     * @param queue 本片队列
     */
    private void loop(int index, BlockingQueue<byte[]> queue) {
        while (running) {
            byte[] frame;
            try {
                frame = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == null) {
                continue;
            }
            Iterable<? extends Sink> current = sinks.get();
            if (current == null) {
                continue;
            }
            for (Sink sink : current) {
                if (sink == null || Math.floorMod(System.identityHashCode(sink), shards) != index) {
                    continue;
                }
                try {
                    sink.writeFrame(frame);
                } catch (Exception e) {
                    // 单条连接写失败不能影响其余连接
                    writeErrors.incrementAndGet();
                }
            }
        }
    }

    /**
     * 投递一帧到所有分片（非阻塞）。
     *
     * @param frame 已编码的帧；{@code null} 忽略
     * @return true 表示至少投递成功（队列未满）
     */
    public boolean publish(byte[] frame) {
        if (!running || frame == null) {
            return false;
        }
        boolean accepted = false;
        for (BlockingQueue<byte[]> queue : queues) {
            if (queue.offer(frame)) {
                accepted = true;
            } else {
                droppedFrames.incrementAndGet();
            }
        }
        return accepted;
    }

    /**
     * 因队列满被丢弃的帧数。
     *
     * @return 帧数
     */
    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    /**
     * 单连接写失败次数。
     *
     * @return 次数
     */
    public long getWriteErrors() {
        return writeErrors.get();
    }

    /**
     * 停止扇出线程并丢弃未发送的帧。
     */
    @Override
    public void close() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (BlockingQueue<byte[]> queue : queues) {
            queue.clear();
        }
        workers.clear();
        queues.clear();
    }
}
