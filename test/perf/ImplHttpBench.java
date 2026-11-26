package perf;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkHttpServer;
import com.chua.common.support.network.server.nio.NioHttpServer;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.vertx.support.server.VertxHttpServer;
import com.chua.quarkus.support.server.QuarkusHttpServer;
import com.chua.armeria.support.server.ArmeriaHttpServer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP 服务端实现对比压测探针（Java 21+，纯 JDK 客户端）。
 *
 * <p>用法：</p>
 * <pre>
 *   server &lt;impl&gt; &lt;port&gt;
 *       启动指定实现（jdk|nio|aio|vertx|quarkus|armeria），注册
 *       POST /echo（原样回显请求体）与 GET /ping（冒烟），打印 SERVER_READY 后常驻。
 *       可用系统属性：-Dbench.maxConns=N 覆盖最大连接数与并发处理上限；
 *       -Dbench.backlog=N 覆盖监听 backlog。
 *       另注册 GET /block：handler 内 sleep -Dbench.blockMs（默认 0）毫秒后返回 "OK"（2 字节），
 *       用于阻塞 handler 对比实验。
 *
 *   client &lt;host&gt; &lt;port&gt; &lt;workers&gt; &lt;seconds&gt; &lt;bodyBytes&gt; &lt;label&gt;
 *       先冒烟校验 /echo 回显一致性，再 3s 预热 + seconds 测量，输出
 *       mode=client label=.. conns=.. body=..B qps=.. tputMBps=.. p50us=.. p99us=.. errs=.. reqs=..
 *
 *   client-raw &lt;host&gt; &lt;port&gt; &lt;workers&gt; &lt;seconds&gt; &lt;bodyBytes&gt; &lt;label&gt;
 *       原生 Socket 客户端：-Dbench.conn=ka|close（close=每请求一条真实 TCP 连接，
 *       SO_LINGER=0 RST 关闭避免 TIME_WAIT 堆积；ka=每 worker 一条长连接复用）；
 *       -Dbench.headers=base|slim|pad|many（slim=去 Content-Type+短 UA；pad 追加 -Dbench.padBytes=N 字节 X-Pad；
 *       many 追加 -Dbench.headerCount=N 个 X-Hi: v 小头，测头解析每行固定成本）。
 *       -Dbench.get=/path：GET 模式，读取该路径响应体（期望长度 -Dbench.respBytes=N，用于吞吐计算与冒烟校验）。
 *       -Dbench.missPct=N：N% 的请求改打 /miss（服务端 404 错误路径），测错误响应成本与连接复用。
 *       -Dbench.pipeline=N：同一连接连续写 N 个请求再读 N 个响应（HTTP/1.1 pipeline 批处理效率）。
 *       -Dbench.slowChunk=N -Dbench.slowSleepMs=M：GET 模式响应头读完后按 N 字节块慢速消费 body、
 *       块间 sleep M 毫秒（F1 慢客户端模拟；slowChunk=0 走全速读）。
 *       -Dbench.progressSecs=N：每 N 秒打印一行 PROGRESS t=..s total=.. qps=..（长跑吞吐漂移观察）。
 *       输出额外带 hdrB=请求头线上字节数（精确计量）、pipe=N、miss=N。
 *
 *   stall &lt;host&gt; &lt;port&gt; &lt;conns&gt; &lt;seconds&gt; &lt;path&gt;
 *       写阻塞背压源（F2）：开 conns 条连接各发一个 GET 后永不读取响应，持住 seconds 秒后统一关闭；
 *       服务端发送缓冲被填满后写路径阻塞/用户态堆积。输出
 *       mode=stall path=.. conns=.. opened=.. errs=.. held=..s。
 * </pre>
 *
 * <p>回环口径：server 与 client 同机不同 JVM（127.0.0.1）；
 * 远程口径：server 在目标机容器（端口发布到宿主），client 在另一台机器走真实网卡。</p>
 *
 * <p>高并发口径：workers &gt; {@link #VIRTUAL_THREAD_THRESHOLD} 时改用虚拟线程发起请求；
 * 延迟采样改为全局封顶（最多 {@link #MAX_SAMPLES_TOTAL} 条，按测量窗口内请求顺序，
 * 超出只计数不采样），避免万级 worker 的 per-worker 采样缓冲内存膨胀。
 * 低档位（≤600 worker）仍为平台线程 + 同步阻塞 send，与历史基线同口径。</p>
 */
public final class ImplHttpBench {

    /** 预热时长（毫秒），不计入统计 */
    private static final long WARMUP_MILLIS = 3000L;

    /** 全局延迟采样上限（long 数组，无装箱；高并发下封顶，超出只计数不采样） */
    private static final int MAX_SAMPLES_TOTAL = 1_000_000;

    /** 超过该 worker 数改用虚拟线程（万级平台线程会耗尽内存与内核调度） */
    private static final int VIRTUAL_THREAD_THRESHOLD = 600;

    /** 每个标签最多打印的异常明细条数 */
    private static final int VERBOSE_ERRS = 3;

    /** 全局延迟采样缓冲：测量窗口内按请求完成顺序封顶采样 */
    private static final long[] SHARED_LAT = new long[MAX_SAMPLES_TOTAL];

    /** 全局采样计数器（含预热期；测量期样本 = 计数 - sampleBase） */
    private static final java.util.concurrent.atomic.AtomicInteger SHARED_N =
            new java.util.concurrent.atomic.AtomicInteger();

    private ImplHttpBench() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: server <impl> <port> | client <host> <port> <workers> <seconds> <bodyBytes> <label>"
                    + " | client-raw <host> <port> <workers> <seconds> <bodyBytes> <label>"
                    + " | stall <host> <port> <conns> <seconds> <path>");
            System.exit(2);
        }
        if ("server".equals(args[0])) {
            AbstractServer server = startServer(args[1], Integer.parseInt(args[2]));
            System.out.println("SERVER_READY impl=" + args[1] + " port=" + server.getPort());
            System.out.flush();
            new CountDownLatch(1).await();
        } else if ("client-raw".equals(args[0])) {
            runRawClient(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]), args.length > 6 ? args[6] : "bench");
        } else if ("stall".equals(args[0])) {
            runStall(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), args.length > 5 ? args[5] : "/d1m");
        } else {
            runClient(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]), args.length > 6 ? args[6] : "bench");
        }
    }

    static AbstractServer create(String impl, ServerSetting setting) {
        switch (impl) {
            case "jdk":     return new JdkHttpServer(setting);
            case "nio":     return new NioHttpServer(setting);
            case "aio":     return new AioHttpServer(setting);
            case "vertx":   return new VertxHttpServer(setting);
            case "quarkus": return new QuarkusHttpServer(setting);
            case "armeria": return new ArmeriaHttpServer(setting);
            default: throw new IllegalArgumentException("unknown impl: " + impl);
        }
    }

    static AbstractServer startServer(String impl, int port) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("0.0.0.0");
        setting.setPort(port);
        String mc = System.getProperty("bench.maxConns");
        if (mc != null) {
            setting.setMaxConnections(Integer.parseInt(mc));
            setting.setMaxConcurrency(Integer.parseInt(mc));
        }
        String bl = System.getProperty("bench.backlog");
        if (bl != null) {
            setting.setBacklog(Integer.parseInt(bl));
        }
        AbstractServer server = create(impl, setting);
        server.registerMapping("/ping", HttpMethod.GET,
                (req, resp) -> resp.end("OK".getBytes()));
        server.registerMapping("/echo", HttpMethod.POST, (req, resp) -> {
            byte[] body = req.getBody();
            resp.end(body == null || body.length == 0 ? "EMPTY".getBytes() : body);
        });
        final int blockMs = Integer.parseInt(System.getProperty("bench.blockMs", "0"));
        server.registerMapping("/block", HttpMethod.GET, (req, resp) -> {
            if (blockMs > 0) {
                try {
                    Thread.sleep(blockMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            resp.end("OK".getBytes());
        });
        registerPayload(server, "/d1k", 1024);
        registerPayload(server, "/d8k", 8192);
        registerPayload(server, "/d64k", 65536);
        registerPayload(server, "/d256k", 262144);
        registerPayload(server, "/d1m", 1048576);
        server.start();
        return server;
    }

    /** 注册静态 payload GET 端点（响应体大小梯度实验用；payload 预生成，handler 零拷贝引用）。 */
    private static void registerPayload(AbstractServer server, String path, int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i & 0x7f);
        }
        server.registerMapping(path, HttpMethod.GET, (req, resp) -> resp.end(payload));
    }

    static void runClient(String host, int port, int workers, int seconds, int bodyBytes, String label) throws Exception {
        byte[] body = new byte[bodyBytes];
        for (int i = 0; i < bodyBytes; i++) {
            body[i] = (byte) (i & 0x7f);
        }
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/echo"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<byte[]> smoke = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (smoke.statusCode() != 200 || !Arrays.equals(body, smoke.body())) {
                System.out.println("label=" + label + " SMOKE_FAIL status=" + smoke.statusCode()
                        + " respLen=" + smoke.body().length);
                System.out.flush();
                return;
            }
        } catch (Exception e) {
            System.out.println("label=" + label + " SMOKE_FAIL ex=" + e);
            System.out.flush();
            return;
        }

        int progressSecs = Integer.parseInt(System.getProperty("bench.progressSecs", "0"));
        LongAdder total = new LongAdder();
        LongAdder errors = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);
        List<Worker> ws = new ArrayList<>(workers);
        CountDownLatch latch = new CountDownLatch(workers);
        boolean virtual = workers > VIRTUAL_THREAD_THRESHOLD;
        for (int i = 0; i < workers; i++) {
            Worker w = new Worker(client, request, total, errors, running, latch);
            ws.add(w);
            Thread t = virtual
                    ? Thread.ofVirtual().name("probe-" + i).unstarted(w)
                    : new Thread(w, "probe-" + i);
            t.setDaemon(true);
            t.start();
        }
        startProgressThread(running, total, progressSecs);
        Thread.sleep(WARMUP_MILLIS);
        long baseline = total.sum();
        int sampleBase = SHARED_N.get();
        for (Worker w : ws) {
            w.reset(sampleBase);
        }
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        running.set(false);
        latch.await();
        double elapsed = (System.nanoTime() - start) / 1e9;

        long count = total.sum() - baseline;
        long err = errors.sum();
        int n = Math.max(0, Math.min(SHARED_N.get() - sampleBase, MAX_SAMPLES_TOTAL));
        long[] all = new long[n];
        for (int j = 0; j < n; j++) {
            all[j] = SHARED_LAT[j];
        }
        Arrays.sort(all, 0, n);
        double tputMBps = count * 2.0 * bodyBytes / elapsed / (1024.0 * 1024.0);
        System.out.printf("mode=client label=%s conns=%d body=%dB qps=%.0f tputMBps=%.1f p50us=%d p99us=%d errs=%d reqs=%d%n",
                label, workers, bodyBytes, count / elapsed, tputMBps,
                pct(all, n, 0.50), pct(all, n, 0.99), err, count);
        System.out.flush();
    }

    static long pct(long[] a, int n, double p) {
        if (n == 0) {
            return 0;
        }
        int idx = (int) Math.min(n - 1, Math.max(0, (int) Math.round(p * n) - 1));
        return a[idx] / 1000;
    }

    private static final class Worker implements Runnable {
        private final HttpClient client;
        private final HttpRequest request;
        private final LongAdder total;
        private final LongAdder errors;
        private final AtomicBoolean running;
        private final CountDownLatch latch;
        /** 测量期采样基线：SHARED_N 计数超过该值的部分属于测量窗口 */
        private volatile int base;

        Worker(HttpClient client, HttpRequest request, LongAdder total, LongAdder errors,
               AtomicBoolean running, CountDownLatch latch) {
            this.client = client;
            this.request = request;
            this.total = total;
            this.errors = errors;
            this.running = running;
            this.latch = latch;
        }

        void reset(int sampleBase) {
            this.base = sampleBase;
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    long t0 = System.nanoTime();
                    try {
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        int idx = SHARED_N.incrementAndGet() - 1 - base;
                        if (idx >= 0 && idx < MAX_SAMPLES_TOTAL) {
                            SHARED_LAT[idx] = System.nanoTime() - t0;
                        }
                        total.increment();
                    } catch (Exception e) {
                        errors.increment();
                        if (errors.sum() <= VERBOSE_ERRS) {
                            System.out.println("label probe err#" + errors.sum() + " ex=" + e);
                            System.out.flush();
                        }
                    }
                }
            } finally {
                latch.countDown();
            }
        }
    }

    // ==================== client-raw：原生 Socket 模式 ====================
    // 短连接实验（bench.conn=close）：每请求一条真实 TCP 连接，完整握手+请求+响应后
    // 用 SO_LINGER(0) 以 RST 关闭——避免 TIME_WAIT 堆积耗尽临时端口/conntrack，
    // 连接建立与销毁开销仍全额计入。头部梯度实验（bench.headers=base|slim|pad）：
    // 请求头完全可控，hdrB 为线上头部字节数的精确计量值。

    /** 原生 Socket 连接超时（毫秒） */
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** missPct 实验的全局请求计数器（决定当次请求是否改打 /miss） */
    private static final java.util.concurrent.atomic.AtomicLong MISS_COUNTER =
            new java.util.concurrent.atomic.AtomicLong();

    /** 构造 POST /echo 请求头模板（US_ASCII）；返回字节数即线上头部真实大小（不含 body）。 */
    static byte[] buildHeader(String host, int port, int bodyBytes, String headersMode, int padBytes, boolean close,
                              int headerCount) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("POST /echo HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        if ("slim".equals(headersMode)) {
            sb.append("User-Agent: b\r\n");
        } else {
            sb.append("Content-Type: application/octet-stream\r\n");
            sb.append("User-Agent: Java-http-client/25\r\n");
        }
        sb.append("Content-Length: ").append(bodyBytes).append("\r\n");
        if ("pad".equals(headersMode) && padBytes > 0) {
            sb.append("X-Pad: ");
            for (int i = 0; i < padBytes; i++) {
                sb.append('x');
            }
            sb.append("\r\n");
        }
        for (int i = 0; i < headerCount; i++) {
            sb.append("X-H").append(i).append(": v\r\n");
        }
        if (close) {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** 构造 GET 请求头模板（US_ASCII）；响应体梯度实验与 miss（/miss 404）实验共用。 */
    static byte[] buildGetHeader(String path, String host, int port, boolean close) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        sb.append("User-Agent: b\r\n");
        if (close) {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** 一条 TCP 连接 + 接收缓冲。非线程安全：每 worker 独占一条。 */
    static final class RawConn implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final byte[] rbuf = new byte[32 * 1024];
        int rp;
        int rl;

        RawConn(String host, int port, boolean rstClose) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15_000);
            if (rstClose) {
                // close 时发 RST：短连接压测不进 TIME_WAIT
                socket.setSoLinger(true, 0);
            }
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** 读取响应头直到 \r\n\r\n 结束；返回 headerEnd 偏移（rbuf 内 body 起点下标）。 */
    static int readResponseHeader(RawConn c) throws IOException {
        int headerEnd = -1;
        search:
        while (true) {
            for (int i = c.rp + 3; i < c.rl; i++) {
                if (c.rbuf[i] == '\n' && c.rbuf[i - 1] == '\r' && c.rbuf[i - 2] == '\n' && c.rbuf[i - 3] == '\r') {
                    headerEnd = i + 1;
                    break search;
                }
            }
            if (c.rp > 0) {
                System.arraycopy(c.rbuf, c.rp, c.rbuf, 0, c.rl - c.rp);
                c.rl -= c.rp;
                c.rp = 0;
            }
            if (c.rl == c.rbuf.length) {
                throw new IOException("resp header too large");
            }
            int n = c.in.read(c.rbuf, c.rl, c.rbuf.length - c.rl);
            if (n < 0) {
                throw new IOException("EOF while reading header");
            }
            c.rl += n;
        }
        return headerEnd;
    }

    /** 读取一条完整 /echo 响应；sink 非 null 时把 body 拷入 sink（长度必须一致）。返回 Content-Length。 */
    static int readEchoResponse(RawConn c, byte[] sink) throws IOException {
        int headerEnd = readResponseHeader(c);
        int cl = parseContentLength(c.rbuf, c.rp, headerEnd);
        int avail = c.rl - headerEnd;
        if (avail < cl) {
            if (sink != null && cl != sink.length) {
                throw new IOException("body len mismatch " + cl + "!=" + sink.length);
            }
            int off = avail;
            if (sink != null) {
                System.arraycopy(c.rbuf, headerEnd, sink, 0, avail);
            }
            int need = cl - avail;
            c.rp = 0;
            c.rl = 0;
            while (need > 0) {
                int n = c.in.read(c.rbuf, 0, Math.min(need, c.rbuf.length));
                if (n < 0) {
                    throw new IOException("EOF while reading body");
                }
                if (sink != null) {
                    System.arraycopy(c.rbuf, 0, sink, off, n);
                }
                off += n;
                need -= n;
            }
        } else {
            if (sink != null) {
                System.arraycopy(c.rbuf, headerEnd, sink, 0, cl);
            }
            c.rp = headerEnd + cl;
        }
        return cl;
    }

    /** 慢速读取一条完整响应（F1 慢客户端）：头部照常解析，body 按 chunk 字节逐块读取、
     *  块间 sleep sleepMs 毫秒，模拟慢消费者对服务端发送缓冲与写路径的拖拽。返回 Content-Length。 */
    static int readEchoResponseSlow(RawConn c, int chunk, long sleepMs) throws IOException, InterruptedException {
        int headerEnd = readResponseHeader(c);
        int cl = parseContentLength(c.rbuf, c.rp, headerEnd);
        int need = cl - (c.rl - headerEnd);
        c.rp = 0;
        c.rl = 0;
        int sz = Math.max(1, Math.min(chunk, c.rbuf.length));
        while (need > 0) {
            int n = c.in.read(c.rbuf, 0, Math.min(need, sz));
            if (n < 0) {
                throw new IOException("EOF while slow-reading body");
            }
            need -= n;
            if (need > 0 && sleepMs > 0) {
                Thread.sleep(sleepMs);
            }
        }
        return cl;
    }

    /** 在响应头区 [from,to) 内找 Content-Length（不区分大小写），找不到抛异常。 */
    static int parseContentLength(byte[] b, int from, int to) throws IOException {
        int i = from;
        while (i < to) {
            int eol = i;
            while (eol < to && b[eol] != '\n') {
                eol++;
            }
            if (eol - i >= 16
                    && (b[i] | 0x20) == 'c' && (b[i + 1] | 0x20) == 'o' && (b[i + 2] | 0x20) == 'n'
                    && (b[i + 3] | 0x20) == 't' && (b[i + 4] | 0x20) == 'e' && (b[i + 5] | 0x20) == 'n'
                    && (b[i + 6] | 0x20) == 't' && b[i + 7] == '-'
                    && (b[i + 8] | 0x20) == 'l' && (b[i + 9] | 0x20) == 'e' && (b[i + 10] | 0x20) == 'n'
                    && (b[i + 11] | 0x20) == 'g' && (b[i + 12] | 0x20) == 't' && (b[i + 13] | 0x20) == 'h') {
                int j = i + 14;
                while (j < eol && (b[j] == ':' || b[j] == ' ' || b[j] == '\t')) {
                    j++;
                }
                int v = 0;
                while (j < eol && b[j] >= '0' && b[j] <= '9') {
                    v = v * 10 + (b[j] - '0');
                    j++;
                }
                return v;
            }
            i = eol + 1;
        }
        throw new IOException("no content-length header");
    }

    static void runRawClient(String host, int port, int workers, int seconds, int bodyBytes, String label) throws Exception {
        String connMode = System.getProperty("bench.conn", "ka");
        boolean closeConn = "close".equalsIgnoreCase(connMode);
        String headersMode = System.getProperty("bench.headers", "base");
        int padBytes = Integer.parseInt(System.getProperty("bench.padBytes", "0"));
        int headerCount = Integer.parseInt(System.getProperty("bench.headerCount", "0"));
        String getPath = System.getProperty("bench.get");
        int respBytes = Integer.parseInt(System.getProperty("bench.respBytes", "0"));
        int missPct = Integer.parseInt(System.getProperty("bench.missPct", "0"));
        int pipeline = Math.max(1, Integer.parseInt(System.getProperty("bench.pipeline", "1")));
        int slowChunk = Integer.parseInt(System.getProperty("bench.slowChunk", "0"));
        long slowSleepMs = Long.parseLong(System.getProperty("bench.slowSleepMs", "0"));
        int progressSecs = Integer.parseInt(System.getProperty("bench.progressSecs", "0"));
        boolean isGet = getPath != null && !getPath.isEmpty();
        byte[] body = new byte[bodyBytes];
        for (int i = 0; i < bodyBytes; i++) {
            body[i] = (byte) (i & 0x7f);
        }
        byte[] header = isGet
                ? buildGetHeader(getPath, host, port, closeConn)
                : buildHeader(host, port, bodyBytes, headersMode, padBytes, closeConn, headerCount);
        byte[] missHeader = missPct > 0 ? buildGetHeader("/miss", host, port, closeConn) : null;
        int hdrB = header.length;
        int respLen = respBytes > 0 ? respBytes : bodyBytes;

        // 冒烟：真实回显/响应一致性校验（该档头/连接/路径模式不被服务端接受时在此失败）
        try (RawConn c = new RawConn(host, port, closeConn)) {
            if (isGet) {
                c.out.write(header);
                c.out.flush();
                int cl = readEchoResponse(c, null);
                if (cl != respLen) {
                    throw new IOException("GET " + getPath + " body len " + cl + " != " + respLen);
                }
            } else {
                c.out.write(header);
                c.out.write(body);
                c.out.flush();
                byte[] echo = new byte[bodyBytes];
                readEchoResponse(c, echo);
                if (!Arrays.equals(body, echo)) {
                    throw new IOException("raw echo mismatch");
                }
            }
            if (pipeline > 1) {
                for (int i = 0; i < pipeline; i++) {
                    c.out.write(header);
                    if (!isGet) {
                        c.out.write(body);
                    }
                }
                c.out.flush();
                for (int i = 0; i < pipeline; i++) {
                    int cl = readEchoResponse(c, null);
                    if (cl != respLen) {
                        throw new IOException("pipeline resp len " + cl);
                    }
                }
            }
            if (missHeader != null) {
                c.out.write(missHeader);
                c.out.flush();
                readEchoResponse(c, null); // 404 响应必须带 Content-Length 才能通过冒烟
            }
        } catch (Exception e) {
            System.out.println("label=" + label + " SMOKE_FAIL raw ex=" + e + " hdrB=" + hdrB);
            System.out.flush();
            return;
        }

        LongAdder total = new LongAdder();
        LongAdder errors = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);
        List<RawWorker> ws = new ArrayList<>(workers);
        CountDownLatch latch = new CountDownLatch(workers);
        boolean virtual = workers > VIRTUAL_THREAD_THRESHOLD;
        for (int i = 0; i < workers; i++) {
            RawWorker w = new RawWorker(host, port, header, missHeader, body, closeConn, pipeline, missPct,
                    respLen, isGet, slowChunk, slowSleepMs, total, errors, running, latch);
            ws.add(w);
            Thread t = virtual
                    ? Thread.ofVirtual().name("raw-" + i).unstarted(w)
                    : new Thread(w, "raw-" + i);
            t.setDaemon(true);
            t.start();
        }
        startProgressThread(running, total, progressSecs);
        Thread.sleep(WARMUP_MILLIS);
        long baseline = total.sum();
        int sampleBase = SHARED_N.get();
        for (RawWorker w : ws) {
            w.reset(sampleBase);
        }
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        running.set(false);
        latch.await();
        double elapsed = (System.nanoTime() - start) / 1e9;

        long count = total.sum() - baseline;
        long err = errors.sum();
        int n = Math.max(0, Math.min(SHARED_N.get() - sampleBase, MAX_SAMPLES_TOTAL));
        long[] all = new long[n];
        for (int j = 0; j < n; j++) {
            all[j] = SHARED_LAT[j];
        }
        Arrays.sort(all, 0, n);
        // 吞吐口径：GET 只计响应体下行；POST /echo 计请求体上行+响应体下行（miss 混入时为名义值）
        double bytesPerReq = isGet ? respLen : 2.0 * bodyBytes;
        double tputMBps = count * bytesPerReq / elapsed / (1024.0 * 1024.0);
        System.out.printf("mode=raw-%s label=%s hdrB=%d pipe=%d miss=%d conns=%d body=%dB qps=%.0f tputMBps=%.1f p50us=%d p99us=%d errs=%d reqs=%d%n",
                connMode, label, hdrB, pipeline, missPct, workers, bodyBytes, count / elapsed, tputMBps,
                pct(all, n, 0.50), pct(all, n, 0.99), err, count);
        System.out.flush();
    }

    /** 周期打印累计吞吐（-Dbench.progressSecs=N；N<=0 不启动）。长跑实验观察吞吐漂移用。 */
    private static void startProgressThread(AtomicBoolean running, LongAdder total, int progressSecs) {
        if (progressSecs <= 0) {
            return;
        }
        Thread pt = new Thread(() -> {
            long t0 = System.nanoTime();
            while (running.get()) {
                try {
                    Thread.sleep(progressSecs * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                double t = (System.nanoTime() - t0) / 1e9;
                System.out.printf("PROGRESS t=%.0fs total=%d qps=%.0f%n", t, total.sum(), total.sum() / Math.max(t, 1e-9));
                System.out.flush();
            }
        }, "progress");
        pt.setDaemon(true);
        pt.start();
    }

    /** 写阻塞背压源（F2）：开 conns 条连接，各发一个 GET 后永不读取响应，持住到 seconds 秒。
     *  服务端响应写满内核/用户态发送缓冲后写路径阻塞或堆积，用于观察各实现背压行为与服务端内存。
     *  输出 mode=stall path=.. conns=.. opened=.. errs=.. held=..s */
    static void runStall(String host, int port, int conns, int seconds, String path) throws Exception {
        byte[] header = buildGetHeader(path, host, port, false);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger errs = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(conns);
        long endAt = System.nanoTime() + seconds * 1_000_000_000L;
        for (int i = 0; i < conns; i++) {
            Thread t = new Thread(() -> {
                try (RawConn c = new RawConn(host, port, false)) {
                    c.socket.setSoTimeout(0);
                    c.out.write(header);
                    c.out.flush();
                    opened.incrementAndGet();
                    long remainMs = Math.max(0, (endAt - System.nanoTime()) / 1_000_000L);
                    Thread.sleep(remainMs);
                } catch (Exception e) {
                    errs.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "stall-" + i);
            t.setDaemon(true);
            t.start();
        }
        done.await();
        System.out.printf("mode=stall path=%s conns=%d opened=%d errs=%d held=%ds%n",
                path, conns, opened.get(), errs.get(), seconds);
        System.out.flush();
    }

    private static final class RawWorker implements Runnable {
        private final String host;
        private final int port;
        private final byte[] header;
        private final byte[] missHeader;
        private final byte[] body;
        private final boolean closeConn;
        private final int pipeline;
        private final int missPct;
        private final int respLen;
        private final boolean isGet;
        private final int slowChunk;
        private final long slowSleepMs;
        private final LongAdder total;
        private final LongAdder errors;
        private final AtomicBoolean running;
        private final CountDownLatch latch;
        /** 测量期采样基线：SHARED_N 计数超过该值的部分属于测量窗口 */
        private volatile int base;

        RawWorker(String host, int port, byte[] header, byte[] missHeader, byte[] body, boolean closeConn,
                  int pipeline, int missPct, int respLen, boolean isGet, int slowChunk, long slowSleepMs,
                  LongAdder total, LongAdder errors, AtomicBoolean running, CountDownLatch latch) {
            this.host = host;
            this.port = port;
            this.header = header;
            this.missHeader = missHeader;
            this.body = body;
            this.closeConn = closeConn;
            this.pipeline = pipeline;
            this.missPct = missPct;
            this.respLen = respLen;
            this.isGet = isGet;
            this.slowChunk = slowChunk;
            this.slowSleepMs = slowSleepMs;
            this.total = total;
            this.errors = errors;
            this.running = running;
            this.latch = latch;
        }

        void reset(int sampleBase) {
            this.base = sampleBase;
        }

        @Override
        public void run() {
            RawConn conn = null;
            try {
                if (!closeConn) {
                    conn = newConn();
                }
                while (running.get()) {
                    long t0 = System.nanoTime();
                    try {
                        RawConn c = closeConn ? newConn() : conn;
                        try {
                            // missPct>0 时按全局计数器决定本批是否改打 /miss（404 错误路径）
                            boolean miss = missHeader != null
                                    && (int) (MISS_COUNTER.incrementAndGet() % 100) < missPct;
                            byte[] h = miss ? missHeader : header;
                            for (int i = 0; i < pipeline; i++) {
                                c.out.write(h);
                                if (!isGet && !miss) {
                                    c.out.write(body);
                                }
                            }
                            c.out.flush();
                            for (int i = 0; i < pipeline; i++) {
                                int cl = isGet && slowChunk > 0
                                        ? readEchoResponseSlow(c, slowChunk, slowSleepMs)
                                        : readEchoResponse(c, null);
                                if (!miss && cl != respLen) {
                                    throw new IOException("bad body len " + cl);
                                }
                                int idx = SHARED_N.incrementAndGet() - 1 - base;
                                if (idx >= 0 && idx < MAX_SAMPLES_TOTAL) {
                                    SHARED_LAT[idx] = System.nanoTime() - t0;
                                }
                            }
                        } finally {
                            if (closeConn) {
                                c.close();
                            }
                        }
                        total.add(pipeline);
                    } catch (Exception e) {
                        if (!closeConn && conn != null) {
                            try { conn.close(); } catch (IOException ignore) { }
                            conn = newConn();
                        }
                        errors.increment();
                        if (errors.sum() <= VERBOSE_ERRS) {
                            System.out.println("label probe err#" + errors.sum() + " ex=" + e);
                            System.out.flush();
                        }
                    }
                }
            } catch (Exception fatal) {
                errors.increment();
                System.out.println("raw worker fatal ex=" + fatal);
                System.out.flush();
            } finally {
                if (conn != null) {
                    try { conn.close(); } catch (IOException ignore) { }
                }
                latch.countDown();
            }
        }

        private RawConn newConn() throws IOException {
            return new RawConn(host, port, closeConn);
        }
    }
}
