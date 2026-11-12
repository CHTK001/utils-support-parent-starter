// [P3C A20 豁免] A20/A30/A31: 并发参数含魔法值场景，豁免。
package com.chua.common.support.network.server.websocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 广播扇出器：把「一次广播 → 逐条写全部连接」从调用线程移到后台，并按连接分片。
 *
 * <p><b>为什么必须异步</b>：百万连接时 {@code publish()} 同步逐条 {@code write+flush}，
 * 一次广播就要阻塞调用线程数秒到数分钟；调用方通常是业务线程甚至事件循环，
 * 一卡就把整个服务拖住。</p>
 *
 * <p><b>为什么分片</b>：阻塞 socket 的 {@code write} 在慢客户端上会阻塞。单线程扇出时，
 * 一个不读数据的客户端就能卡住全场广播。按 {@code identityHashCode(sink) % shards}
 * 固定分片后，一条连接永远落在同一个片里：既保住了<b>单连接内的帧顺序</b>，
 * 又让慢客户端只拖累自己那一片。</p>
 *
 * <p><b>为什么在注册时归档，而不是每次遍历全部再过滤</b>：上一版每片 worker 都遍历
 * <i>全量</i>连接、再用哈希丢弃不属于本片的，总代价是 {@code n × shards}。
 * 百万连接 8 分片时每帧要白跑 800 万次迭代。改为 {@link #register} 时按片归档后，
 * 每帧总代价回到 {@code n}。</p>
 *
 * <p><b>为什么有界队列 + 丢弃</b>：扇出速度恒为 O(n)，而发布速率由业务决定。
 * 无界队列会在洪峰时把内存吃光。这里按帧丢弃并计数——广播本身就是可丢的
 * （客户端拿不到增量会走全量同步），丢帧远好过 OOM。</p>
 *
 * @param <T> 连接类型
 * @author CH
 * @since 4.0.0.52
 */
public final class AsyncBroadcaster<T extends WsSink> implements AutoCloseable {

    /**
     * 空闲时轮询间隔（毫秒）：让关闭能及时退出，又不至于空转。
     */
    private static final long POLL_MILLIS = 200L;

    /**
     * 扇出任务：{@code target == null} 表示广播到本片全部连接，否则只投给这一条。
     *
     * <p>定向与广播走同一个队列，是为了让同一条连接的帧顺序由队列 FIFO 天然保证：
     * 若定向消息另开通道，它与广播就可能交错，客户端收到的字节序与业务顺序不一致。</p>
     *
     * @param <E> 连接类型
     */
    private static final class Task<E> {

        /**
         * 已编码的帧。
         */
        private final byte[] frame;

        /**
         * 定向目标；null 表示广播。
         */
        private final E target;

        /**
         * 构造任务。
         *
         * @param frame  帧
         * @param target 定向目标，null 为广播
         */
        private Task(byte[] frame, E target) {
            this.frame = frame;
            this.target = target;
        }
    }

    /**
     * 分片数。
     */
    private final int shards;

    /**
     * 每片的连接集合：注册时按哈希归档，扇出时只遍历本片。
     */
    private final List<Set<T>> shardSinks = new ArrayList<>();

    /**
     * 每片的待发帧队列。
     */
    private final List<BlockingQueue<Task<T>>> queues = new ArrayList<>();

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
     * 扇出时实际访问过的连接次数（诊断用）：一次广播的理想值等于注册连接数，
     * 若等于「连接数 × 分片数」说明退化回了全量遍历再过滤。
     */
    private final AtomicLong visitedLinks = new AtomicLong();

    /**
     * 运行标志。
     */
    private volatile boolean running = true;

    /**
     * 构造并启动扇出线程。
     *
     * @param shards        分片数（建议 4~16）
     * @param queueCapacity 每片队列容量（单位：帧）
     */
    public AsyncBroadcaster(int shards, int queueCapacity) {
        this.shards = Math.max(1, shards);
        int capacity = Math.max(1, queueCapacity);
        for (int i = 0; i < this.shards; i++) {
            BlockingQueue<Task<T>> queue = new ArrayBlockingQueue<>(capacity);
            queues.add(queue);
            shardSinks.add(ConcurrentHashMap.newKeySet());
            final int index = i;
            Thread worker = Thread.ofVirtual()
                    .name("ws-broadcast-" + i)
                    .start(() -> loop(index, queue));
            workers.add(worker);
        }
    }

    /**
     * 计算连接落在哪一片。
     *
     * <p>用 {@code identityHashCode} 而不是 {@code hashCode}：连接对象不保证重写
     * {@code hashCode}，而且注册与注销必须落在同一片，身份哈希在对象生命周期内恒定。</p>
     *
     * @param sink 连接
     * @return 分片下标
     */
    public int shardOf(T sink) {
        return Math.floorMod(System.identityHashCode(sink), shards);
    }

    /**
     * 注册连接：只加进它所属那一片，扇出时无需再过滤。
     *
     * @param sink 连接；null 忽略
     */
    public void register(T sink) {
        if (sink != null) {
            shardSinks.get(shardOf(sink)).add(sink);
        }
    }

    /**
     * 注销连接。
     *
     * @param sink 连接；null 忽略
     */
    public void unregister(T sink) {
        if (sink != null) {
            shardSinks.get(shardOf(sink)).remove(sink);
        }
    }

    /**
     * 取当前全部连接的快照（停止时逐个关闭用）。
     *
     * @return 连接列表
     */
    public List<T> snapshot() {
        List<T> all = new ArrayList<>();
        for (Set<T> set : shardSinks) {
            all.addAll(set);
        }
        return all;
    }

    /**
     * 扇出循环：只处理落在本片的连接。
     *
     * @param index 分片下标
     * @param queue 本片队列
     */
    private void loop(int index, BlockingQueue<Task<T>> queue) {
        while (running) {
            Task<T> task;
            try {
                task = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                continue;
            }
            if (task.target != null) {
                writeOne(task.target, task.frame);
                continue;
            }
            // 只遍历本片：注册时已归档，这里不再做哈希过滤
            for (T sink : shardSinks.get(index)) {
                visitedLinks.incrementAndGet();
                writeOne(sink, task.frame);
            }
        }
    }

    /**
     * 写一条连接，失败只计数不影响其余连接。
     *
     * @param sink  连接
     * @param frame 帧
     */
    private void writeOne(T sink, byte[] frame) {
        try {
            sink.writeFrame(frame);
        } catch (Exception e) {
            writeErrors.incrementAndGet();
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
        Task<T> task = new Task<>(frame, null);
        for (BlockingQueue<Task<T>> queue : queues) {
            if (queue.offer(task)) {
                accepted = true;
            } else {
                droppedFrames.incrementAndGet();
            }
        }
        return accepted;
    }

    /**
     * 定向投递一帧给单条连接（非阻塞）。
     *
     * <p>调用方不再需要自己 {@code write+flush}：百万连接下同步写一条慢连接，
     * 会把业务线程卡在内核写缓冲上。投进它所属分片的队列后立即返回。</p>
     *
     * @param sink  目标连接
     * @param frame 已编码的帧
     * @return true 表示已入队；false 表示连接为空、已停止或队列满被丢弃
     */
    public boolean sendTo(T sink, byte[] frame) {
        if (!running || sink == null || frame == null) {
            return false;
        }
        if (queues.get(shardOf(sink)).offer(new Task<>(frame, sink))) {
            return true;
        }
        droppedFrames.incrementAndGet();
        return false;
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
     * 扇出访问连接次数（诊断用）。
     *
     * @return 次数
     */
    public long getVisitedLinks() {
        return visitedLinks.get();
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
        for (BlockingQueue<Task<T>> queue : queues) {
            queue.clear();
        }
        for (Set<T> set : shardSinks) {
            set.clear();
        }
        workers.clear();
        queues.clear();
        shardSinks.clear();
    }
}
