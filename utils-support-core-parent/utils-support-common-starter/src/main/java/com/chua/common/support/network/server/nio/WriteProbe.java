package com.chua.common.support.network.server.nio;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 写路径探针（仅基准测试 -Dbench.debug=true 时使能）。
 *
 * <p>记录每笔响应的关键时间:
 * <ul>
 *   <li>[E] 响应完成: handler 级的 end() / complete() 到达, 响应头体已构造完毕</li>
 *   <li>[Q] 写入待写队列: 字节追加到 socket 的 pendingWriteQueue</li>
 *   <li>[W] 实际写出: SocketChannel.write() 被调用 (分片写, 可能多次)</li>
 *   <li>[R2] 请求到达: 事件循环 read() 返回, 数据安全落用户态 SocketChannel 缓冲</li>
 *   <li>[R] 请求解析: 事件循环开始调用 NioServerRequest.feed() 从未解析到解析</li>
 * </ul>
 *
 * <p>配合 HttpBench client0 的 [dbg2] (逐轮 client 侧读开始+耗时) 做时间对齐,
 * 从而拆分 "server 写完" 与 "client 读到" 之间的间隙。</p>
 * @author CH
 * @since 4.0.0.50
 */
public final class WriteProbe {

    /** 是否使能探针: 由 -Dbench.debug=true 控制 */
    private static final boolean ENABLED = "true".equals(System.getProperty("bench.debug"));

    /** 探针输出: System.err (与 bench 系统输出分开, 复用同一日志文件) */
    // [P3C E1 豁免]
    private static final PrintWriter OUT = new PrintWriter(System.err, true);

    private static final AtomicLong COUNT_E = new AtomicLong();
    private static final AtomicLong COUNT_Q = new AtomicLong();
    private static final AtomicLong COUNT_W = new AtomicLong();
    private static final AtomicLong COUNT_R = new AtomicLong();
    private static final AtomicLong COUNT_R2 = new AtomicLong();
    private static final AtomicLong MAX_E2Q = new AtomicLong();
    private static final AtomicLong MAX_Q2W = new AtomicLong();
    private static final AtomicLong MAX_R2E = new AtomicLong();
    private static final AtomicLong MAX_R2R = new AtomicLong();

    /** 事件循环进入 selector.select() 前的时间戳 (同线程), 配合 selExit 算循环周期 */
    private static final ThreadLocal<Long> LAST_SEL = new ThreadLocal<>();
    private static final AtomicLong COUNT_S1 = new AtomicLong();
    private static final AtomicLong COUNT_S2 = new AtomicLong();
    private static final AtomicLong MAX_S1S2 = new AtomicLong();
    private static final AtomicLong MAX_CPU = new AtomicLong();
    private static final AtomicLong COUNT_KS = new AtomicLong();

    /** 同一条出栈连接的时间戳, 配合 maxUpdate 算最大排队时延 */
    private static final ThreadLocal<Long> LAST_E = new ThreadLocal<>();
    private static final ThreadLocal<Long> LAST_Q = new ThreadLocal<>();
    private static final ThreadLocal<Long> LAST_W = new ThreadLocal<>();
    private static final ThreadLocal<Long> LAST_R2 = new ThreadLocal<>();
    private static final ThreadLocal<Long> LAST_R = new ThreadLocal<>();

    private WriteProbe() {
    }

    /**
     * 响应完成: handler 的 end() 已执行, header+body 已构造
     * @param len 响应总字节数 (头+体, 合并后)
     */
    public static void completed(int len) {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        OUT.println("[E] t=" + t + " len=" + len + " thr=" + Thread.currentThread().getName());
        COUNT_E.incrementAndGet();
        LAST_E.set(t);
        MAX_R2E.updateAndGet(v -> Math.max(v, t - value0(LAST_R2.get())));
    }

    /**
     * 响应已入队到 socket 待写队列 (可由事件循环的 OP_WRITE 续写)
     * @param len 入队的字节数
     */
    public static void enqueued(int len) {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        OUT.println("[Q] t=" + t + " len=" + len + " thr=" + Thread.currentThread().getName());
        COUNT_Q.incrementAndGet();
        MAX_E2Q.updateAndGet(v -> Math.max(v, t - value0(LAST_E.get())));
        LAST_Q.set(t);
    }

    /**
     * socket 实际写出
     * @param len 本次 write 写出的字节数
     */
    public static void written(int len) {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        OUT.println("[W] t=" + t + " len=" + len + " thr=" + Thread.currentThread().getName());
        COUNT_W.incrementAndGet();
        MAX_Q2W.updateAndGet(v -> Math.max(v, t - value0(LAST_Q.get())));
        LAST_W.set(t);
    }

    /**
     * 通道 read() 返回: 请求数据已进入用户态 SocketChannel 缓冲.
     * 这个标记给出 "数据到达服务端" 的精确时刻.
     * @param len 本次 read 读到的字节数
     */
    public static void received(int len) {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        OUT.println("[R2] t=" + t + " len=" + len + " thr=" + Thread.currentThread().getName());
        COUNT_R2.incrementAndGet();
        LAST_R2.set(t);
    }

    /**
     * 请求开始解析: 调用 {@code NioServerRequest.feed()} 之前.
     * 与 [R2] 的差距是内核读到用户态缓冲的延迟 + 事件循环调度.
     * @param len 本次 read 读到的字节数
     */
    public static void entered(int len) {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        OUT.println("[R] t=" + t + " len=" + len + " thr=" + Thread.currentThread().getName());
        COUNT_R.incrementAndGet();
        LAST_R.set(t);
        MAX_R2R.updateAndGet(v -> Math.max(v, t - value0(LAST_R2.get())));
    }

    /**
     * 事件循环进入 {@code select()} 前 (S1): 记录时间戳与线程,不做其他事,不创建对象.
     * @see #selExit()
     */
    public static void selEnter() {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        OUT.println("[S1] t=" + t + " len=0 thr=" + Thread.currentThread().getName());
        COUNT_S1.incrementAndGet();
        LAST_SEL.set(t);
    }

    /**
     * 事件循环离开 {@code select()} 返回 (S2): 打印本轮事件循环周期,并追踪
     * 事件循环活跃时间 (MAX_CPU): 任一线程的 MAX_CPU 停止增长 = 线程卡死.
     * @see #selEnter()
     */
    public static void selExit() {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        long cycle = t - value0(LAST_SEL.get());
        OUT.println("[S2] t=" + t + " len=0 thr=" + Thread.currentThread().getName() + " cycle=" + cycle);
        COUNT_S2.incrementAndGet();
        LAST_SEL.set(t);
        MAX_S1S2.updateAndGet(v -> Math.max(v, cycle));
        MAX_CPU.updateAndGet(v -> Math.max(v, t));
    }

    /**
     * 事件循环从 {@code select()} 返回后选中的 key 数量 (KS): 每轮事件循环发现的可
     * 读写连接数（包含 OP_ACCEPT）, 与 [S2] 配对可算出平均每秒事件循环被唤起的次数.
     * @param ks 选中的 SelectionKey 数量
     */
    public static void keysSelected(int ks) {
        if (!ENABLED) {
            return;
        }
        // [P3C A20 豁免]
        long t = System.nanoTime() / 1000;
        OUT.println("[KS] t=" + t + " len=0 thr=" + Thread.currentThread().getName() + " count=" + ks);
        COUNT_KS.incrementAndGet();
    }

    /**
     * 端到端统计摘要: 打印入队次数/写出次数/最大排队时延
     */
    public static void report() {
        if (!ENABLED) {
            return;
        }
        OUT.println("[probe] completed=" + COUNT_E.get()
                + " enqueued=" + COUNT_Q.get()
                + " written=" + COUNT_W.get()
                + " requestReceived=" + COUNT_R2.get()
                + " requestEntered=" + COUNT_R.get()
                + " s1=" + COUNT_S1.get()
                + " s2=" + COUNT_S2.get()
                + " ks=" + COUNT_KS.get()
                + " maxS1S2_us=" + MAX_S1S2.get()
                + " maxCpu_us=" + MAX_CPU.get()
                + " maxE2Q_us=" + MAX_E2Q.get()
                + " maxQ2W_us=" + MAX_Q2W.get()
                + " maxR2E_us=" + MAX_R2E.get()
                + " maxR2R_us=" + MAX_R2R.get());
    }

    private static long value0(Long v) {
        return v == null ? 0L : v;
    }
}
