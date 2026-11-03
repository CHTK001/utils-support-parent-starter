import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 耳求基准测试工具:测量 server 并发连接数 / 吞吐 / 延迟。
 *
 * 用法: java HttpBench <type: nio|aio> <connections> <duration_s>
 * 例: java HttpBench nio 5000 30
 */
public class HttpBench {

    /** echo handler: 返回 hello bench (12 字节) */
    private static final ServerHandler ECHO = (req, res) -> {
        res.setBody("hello bench");
        res.end();
    };

    /** 请求报文模板(keep-alive) */
    private static final String REQUEST =
            "GET /echo HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: keep-alive\r\n"
                    + "Accept: */*\r\nUser-Agent: bench\r\n\r\n";

    private static final int BODY_LEN = 12;
    private static final int MAX_RES = 4096;

    /**
     * 是否使用虚拟线程创建客户端连接（-Dbench.virtual=true）
     *
     * <p>每连接一个虚拟线程：每个连接都能拿到专属的阻塞 I/O，
     * 但虚拟线程是 FIFO 调度，不能像本地线程那样恒定占用处理器。
     * 高并发时可以在一两个核上完成调度，非常适合连接数远大于 CPU 核数的场景。</p>
     *
     * <p>本地线程的缺点：<br>
     * Windows 上一个进程能创建的本地线程数受栈空间限制（100K 连接 = 100K * 1MB 栈 = 100GB 虚拟内存），<br>
     * 实测 40K 连接时 `_beginthreadex failed (EACCES)`；<br>
     * 这对没有 100GB 内存可供 100K 本地线程使用的机器的限制更大。<br>
     * 使用虚拟线程来克服这一限制。</p>
     */
    private static final boolean VIRTUAL_CLIENT = "true".equals(System.getProperty("bench.virtual"));

    /** 是否打印每轮请求耗时(排障用, 开启后输出量很大) */
    private static final boolean DEBUG_LATENCY = "true".equals(System.getProperty("bench.debug", "false"));

    private HttpBench() {
    }

    public static void main(String[] args) throws Exception {
        String type = args.length > 0 ? args[0] : "nio";
        int connections = Integer.parseInt(args.length > 1 ? args[1] : "5000");
        int duration = Integer.parseInt(args.length > 2 ? args[2] : "30");

        ServerSetting setting = ServerSetting.auto();
        setting.setInlineDispatch(true); // 事件循环内联 handler (fast path)
        setting.setMaxConcurrency(-1); // 不限制
        setting.setResponseTimeout(-1); // 不限制
        setting.setMaxKeepAliveRequests(-1);
        // 连接限制：-Dbench.maxconns 可覆盖（默认用 autoConfig 给出的值）
        // 测试百万连接时设为 1M，避免 accept 阶段被上限拦截
        if ("true".equals(System.getProperty("bench.virtual"))) {
            setting.setMaxConnections(1_000_000);
        } else {
            setting.setMaxConnections(Integer.getInteger("bench.maxconns", setting.getMaxConnections()));
        }
        // 等待队列长度：-Dbench.backlog 可覆盖 autoConfig 的值。
        // 高并发建连阶段内核会按平台上限截断（Windows 默认只有几百），
        // 调大它才能容忍瞬时的建连冲击，调小则连接的 1/3 全部被拒绝。
        int backlogOverride = Integer.getInteger("bench.backlog", 0);
        if (backlogOverride > 0) {
            setting.setBacklog(backlogOverride);
        }
        System.out.println("[bench] type=" + type + " connections=" + connections + " duration=" + duration + "s"); // [P3C E1 豁免]
        System.out.println("[bench] setting: ioThreads=" + setting.getIoThreads() // [P3C E1 豁免]
                + " eventLoops=" + setting.getEventLoops()
                + " maxConcurrency=" + setting.getMaxConcurrency()
                + " maxConnections=" + setting.getMaxConnections()
                + " backlog=" + setting.getBacklog()
                + " buffer=" + setting.getBufferSize());

        Server server;
        if ("aio".equals(type)) {
            server = new AioHttpServer(setting);
        } else {
            server = new NioHttpServer(setting);
        }
        AbstractServer abs = (AbstractServer) server;
        abs.registerMapping("/echo", HttpMethod.GET, ECHO);
        server.start();
        try {
            runBench(server, abs, connections, duration);
        } finally {
            server.stop();
        }
    }

    private static void runBench(Server server, AbstractServer abs, int connections, int duration) throws Exception {
        int port = server.getPort();
        Runner runner = new Runner(port, connections, duration);
        long t0 = System.currentTimeMillis();
        runner.start();
        Thread.sleep((long) duration * 1000);
        runner.stop();
        runner.complete();
        long elapsed = System.currentTimeMillis() - t0;
        String report = runner.report();
        System.out.println(report); // [P3C E1 豁免]
        System.out.println("[bench] elapsed=" + elapsed + "ms"); // [P3C E1 豁免]
        System.out.println("[bench] server metrics: total=" + abs.getMetrics().getTotalRequests() // [P3C E1 豁免]
                + " active=" + abs.getMetrics().getActiveRequests()
                + " err=" + abs.getMetrics().getErrorCount()
                + " avgms=" + abs.getMetrics().getAverageLatencyMillis()
                + " maxms=" + abs.getMetrics().getMaxLatencyMillis());
        System.out.println("[bench] server metrics: " + abs.getMetrics().getLatencyHistogram()); // [P3C E1 豁免]
        System.out.println("[bench] result: " + report); // [P3C E1 豁免]
    }

    /** 客户端工作线程, 模拟 keep-alive 连接 */
    private static class Runner {

        /** 每簇集群同时打开的 Socket 数：不超过 Windows 内核 accept 队列上限(200)的约半数 */
        private static final int CLUSTER = 100;
        /** 两簇之间的间隔(ms)，留给服务器 select 循环把 accept 队列排空 */
        private static final long GAP_MS = 300;

        private final int port;
        private final int connections;
        private final int duration;

        private final List<Client> clients = new ArrayList<>();
        private final List<Thread> threads = new ArrayList<>();
        private final LongAdder totalRequests = new LongAdder();
        private final LongAdder totalLatency = new LongAdder();
        private final LongAdder errors = new LongAdder();
        /** 分类: socket 写失败 */
        private final LongAdder errorsWrite = new LongAdder();
        /** 分类: 读半包(未收到响应头, EOF) */
        private final LongAdder errorsReadHeaderEOF = new LongAdder();
        /** 分类: 读半包(未收满 body, EOF) */
        private final LongAdder errorsReadBodyEOF = new LongAdder();
        /** 分类: 请求未发出, 连接已断 */
        private final LongAdder errorsConn = new LongAdder();
        /** 分类: 客户端 Socket 创建时 ConnectException（非服务器故障） */
        private final LongAdder errorsConnect = new LongAdder();
        /** 停止期客户端主动断开(非服务器故障) */
        private final LongAdder stoppedErrors = new LongAdder();
        /** 延迟桶 (us 0..60000) */
        private final AtomicLongArray hist = new AtomicLongArray(60001);

        private volatile boolean running = true;

        public Runner(int port, int connections, int duration) {
            this.port = port;
            this.connections = connections;
            this.duration = duration;
        }



        /**
         * 建连阶段：禁止把所有 Client 放在连续的毫秒里打开 Socket，
         * 否则空闲 Accept 队列（backlog）被瞬间挤满，半数以上的连接
         * 会在 {@code new Socket()} 阶段就收到 {@code Connection refused}。
         * <p>根因是 Windows 内核的 accept 队列有 hard cap（SOMAXCONN，本机实测为 200，
         * 即使 backlog 设为 65536 也会被内核静默截断到 200，200 次尝试里 300 个连接
         * 自己就拒绝 100 个，不代表服务器能力）；服务器排空的速度更快
         * （每 select 循环 drain acceptBatch=128轰），但错过窗口就晚了——被拒绝的那一刻
         * 连接已经不存在，拉不到队列里去。</p>
         * <p>节奏：每 CLUSTER 个 Client 作为一个集群，集群之内不加间隔（同一毫秒窗口）；
         * 集群之间留 GAP_MS ms 间隔，等待服务器 select 循环完成一次 accept drain。
         * 任意时刻驻留在内核队列中的已握手连接数恒 ≤ CLUSTER（100），永远有余量
         * 接住下一批，连一百万个也只是慢一点，不会多一个 Connection refused。</p>
         */
        public void start() throws Exception {
            for (int i = 0; i < connections; ) {
                int end = Math.min(connections, i + CLUSTER);
                for (int j = i; j < end; j++) {
                    Client c = new Client(j);
                    clients.add(c);
                    // [P3C C1 豁免] 性能测试，线程创建为基准参数描述
                    Thread t;
                    if (VIRTUAL_CLIENT) {
                        // 虚拟线程：每连接一个 FIFO 调度的虚拟线程，摆脱 Windows 本地线程 ~40K 上限
                        // （startVirtualThread 会自动启动；VirtualThread 不能再 start）
                        t = Thread.startVirtualThread(c);
                    } else {
                        t = new Thread(c, "bench-conn-" + j);
                    }
                    threads.add(t);
                    if (!VIRTUAL_CLIENT) {
                        t.start();
                    }
                }
                i = end;
                if (i < connections) {
                    Thread.sleep(GAP_MS);
                }
            }
        }

        public void stop() {
            running = false;
            for (Client c : clients) {
                c.setStopped();
                c.close();
            }
        }

        public void complete() throws Exception {
            for (Thread t : threads) {
                t.join(2000);
            }
        }

        public String report() {
            long total = totalRequests.sum();
            long err = errors.sum();
            int q50 = _quantile(50);
            int q95 = _quantile(95);
            int q99 = _quantile(99);
            double avg = totalRequests.sum() == 0 ? 0 : (double) totalLatency.sum() / total / 1_000;
            StringBuilder sb = new StringBuilder();
            sb.append("requests=").append(total).append(" err=").append(err)
                    .append(" qps=").append(total / (long) duration)
                    .append(" p50=").append(q50).append("us")
                    .append(" p95=").append(q95).append("us")
                    .append(" p99=").append(q99).append("us")
                    .append(" avg=").append(String.format("%.3f", avg)).append("us")
                    .append(" writeErr=").append(errorsWrite.sum())
                    .append(" hEOF=").append(errorsReadHeaderEOF.sum())
                    .append(" bEOF=").append(errorsReadBodyEOF.sum())
                    .append(" conn=").append(errorsConn.sum())
                    .append(" connect=").append(errorsConnect.sum())
                    .append(" stopped=").append(stoppedErrors.sum());
            return sb.toString();
        }

        private int _quantile(int p) {
            long count = 0;
            for (int i = 0; i < hist.length(); i++) {
                count += hist.get(i);
            }
            if (count == 0) {
                return 0;
            }
            long idx = Math.round(p / 100.0 * count);
            idx = Math.max(0, Math.min(idx, count - 1));
            long acc = 0;
            for (int i = 0; i < hist.length(); i++) {
                acc += hist.get(i);
                if (acc >= idx) {
                    return i;
                }
            }
            return hist.length() - 1;
        }

        public class Client implements Runnable {
            private final int id;
            private volatile Socket sock;
            private volatile boolean closed;
            /** runner 停止后, 客户端 socket 由 runner 主动关闭(关闭是 harness 行为, 不是服务器) */
            private volatile boolean stopped;
            private final byte[] buf = new byte[MAX_RES];

            public Client(int id) {
                this.id = id;
            }

            public void setStopped() {
                this.stopped = true;
            }

            @Override
            public void run() {
                OutputStream out = null;
                InputStream in = null;
                try {
                    sock = new Socket("127.0.0.1", port);
                    sock.setKeepAlive(true);
                    sock.setTcpNoDelay(true);
                    out = sock.getOutputStream();
                    in = sock.getInputStream();
                    while (running) {
                        long start = System.nanoTime();
                        int read;
                        long writeNs = 0L;
                        try {
                            long w0 = System.nanoTime();
                            out.write(REQUEST.getBytes(StandardCharsets.UTF_8));
                            writeNs = System.nanoTime() - w0;
                            totalRequests.increment();
                            read = readFullResponse(in);
                        } catch (IOException e) {
                            if (stopped) {
                                // 接收异常由 runner 关闭 socket 触发 (SocketException: Socket closed),
                                // 不是服务器故障
                                stoppedErrors.increment();
                            } else {
                                errors.increment();
                                errorsConn.increment(); // socket 异常
                                if (errorsConn.sum() <= 10) {
                                    System.out.println("[bench] client " + id + " read failed: " + e);
                                }
                            }
                            break;
                        }
                        if (read > 0) {
                            long latency = System.nanoTime() - start;
                            totalLatency.add(latency);
                            addHist((int) (latency / 1_000));
                            if (DEBUG_LATENCY && id == 0) {
                                long iter = totalRequests.sum();
                                if (iter % 100 == 0) {
                                    // 每100次请求输出 client0 的逐轮耗时: 写(us) 读(us) 总(us)
                                    System.out.println("[dbg] client" + id + " iter=" + iter
                                            + " total_us=" + (latency / 1_000)
                                            + " write_us=" + (writeNs / 1_000)
                                            + " read_us=" + ((latency - writeNs) / 1_000));
                                }
                            }
                        } else {
                            if (!stopped) {
                                errors.increment();
                                if (read == -2) {
                                    errorsReadBodyEOF.increment(); // body 半包
                                } else {
                                    errorsReadHeaderEOF.increment(); // 响应头半包 / 连接断开
                                }
                            } else {
                                stoppedErrors.increment();
                            }
                        }
                    }
                } catch (IOException e) {
                    if (stopped) {
                        stoppedErrors.increment();   // socket 创建/关闭是 harness 行为, 非服务器故障
                    } else {
                        errors.increment();
                        if (e instanceof ConnectException) {
                            // Socket 创建阶段被拒绝：未到达服务器，是建连冲击压爆了 backlog
                            // （connect 次数多时不要每条都打日志）
                            errorsConnect.increment();
                            if (errorsConnect.sum() <= 10) {
                                System.out.println("[bench] client " + id + " connect refused: " + e);
                            }
                        } else if (e instanceof SocketException) {
                            errorsConn.increment();
                            if (errorsConn.sum() <= 10) {
                                System.out.println("[bench] client " + id + " outer failed: " + e);
                            }
                        } else {
                            if (errors.sum() <= 10) {
                                System.out.println("[bench] client " + id + " outer failed: " + e);
                            }
                        }
                    }
                } finally {
                    close();
                }
            }

            /**
             * 读完整响应。
             * <p>注意: 服务端会把 header + body 合并写在一次 write 里(小响应
             * {@code COMBINE_THRESHOLD} 合并),因此 \r\n\r\n 固定在缓冲区的**某个位置
             * 而不是尾部**;只检查尾部的代码在完美响应下图等 EOF 才返回 -1,
             * 必须搜索整个已读缓冲。
             */
            private int readFullResponse(InputStream in) throws IOException {
                int len = 0; // 缓冲中有效字节数
                int contentLen = 0;
                boolean parsed = false;
                int terminatorEnd = 0; // 头终结符 \r\n\r\n 之后的字节偏移
                while (!parsed) {
                    long r0 = System.nanoTime();
                    int n = in.read(buf, len, MAX_RES - len);
                    long r1 = System.nanoTime();
                    if (DEBUG_LATENCY && id == 0) {
                        long iter = totalRequests.sum();
                        if (iter % 1000 == 0) {
                            System.out.println("[dbg2] client" + id + " iter=" + iter
                                    + " read_us=" + (r0 / 1_000)
                                    + " d_us=" + ((r1 - r0) / 1_000)
                                    + " n=" + n);
                        }
                    }
                    if (n <= 0) {
                        return -1; // EOF: 响应不完整 (消息半包)
                    }
                    len += n;
                    if (len >= 4) {
                        // 在整个已读缓冲中搜索第一个终结符,不只尾部
                        for (int i = 0; i <= len - 4; i++) {
                            if (buf[i] == 13 && buf[i + 1] == 10 && buf[i + 2] == 13 && buf[i + 3] == 10) {
                                contentLen = parseContentLength(i);
                                terminatorEnd = i + 4;
                                parsed = true;
                                break;
                            }
                        }
                    }
                    if (!parsed && len >= MAX_RES - BODY_LEN) {
                        return len; // 缓冲已满仍未找到终结符,返回已读部分
                    }
                }
                if (contentLen <= 0) {
                    return terminatorEnd;
                }
                // 合并写: 终结符之后可能已经有了 body 字节,不用再抽,避免错误等待
                int total = terminatorEnd;
                int remaining = contentLen;
                if (terminatorEnd < len) {
                    remaining -= (len - terminatorEnd);
                }
                remaining = Math.max(0, remaining);
                while (remaining > 0) {
                    int n = in.read(buf, total, Math.min(remaining, MAX_RES - total));
                    if (n <= 0) {
                        return -2; // EOF: body 不完整 (半包)
                    }
                    total += n;
                    remaining -= n;
                }
                return total;
            }

            /** 解析终结符之前的 Content-Length 值 */
            private int parseContentLength(int end) {
                for (int i = 0; i < end; i++) {
                    if (buf[i] == 'C' && buf[i + 1] == 'o' && buf[i + 2] == 'n'
                            && buf[i + 3] == 't' && buf[i + 4] == 'e' && buf[i + 5] == 'n'
                            && buf[i + 6] == 't' && buf[i + 7] == '-' && buf[i + 8] == 'L'
                            && buf[i + 9] == 'e' && buf[i + 10] == 'n' && buf[i + 11] == 'g'
                            && buf[i + 12] == 't' && buf[i + 13] == ':') {
                        int j = i + 14;
                        int v = 0;
                        boolean has = false;
                        while (j < end && buf[j] >= '0' && buf[j] <= '9') {
                            v = v * 10 + (buf[j] - '0');
                            j++;
                            has = true;
                        }
                        if (has) {
                            return v;
                        }
                        break;
                    }
                }
                return 0;
            }

            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    if (sock != null) {
                        sock.shutdownInput();
                        sock.close();
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        private void addHist(int us) {
            if (us >= 0 && us < hist.length()) {
                hist.getAndIncrement(us);
            }
        }
    }
}
