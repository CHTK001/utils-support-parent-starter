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

import java.net.URI;
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
 *
 *   client &lt;host&gt; &lt;port&gt; &lt;workers&gt; &lt;seconds&gt; &lt;bodyBytes&gt; &lt;label&gt;
 *       先冒烟校验 /echo 回显一致性，再 3s 预热 + seconds 测量，输出
 *       mode=client label=.. conns=.. body=..B qps=.. tputMBps=.. p50us=.. p99us=.. errs=.. reqs=..
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
            System.out.println("usage: server <impl> <port> | client <host> <port> <workers> <seconds> <bodyBytes> <label>");
            System.exit(2);
        }
        if ("server".equals(args[0])) {
            AbstractServer server = startServer(args[1], Integer.parseInt(args[2]));
            System.out.println("SERVER_READY impl=" + args[1] + " port=" + server.getPort());
            System.out.flush();
            new CountDownLatch(1).await();
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
        server.start();
        return server;
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
                        long c = errors.increment();
                        if (c <= VERBOSE_ERRS) {
                            System.out.println("label probe err#" + c + " ex=" + e);
                            System.out.flush();
                        }
                    }
                }
            } finally {
                latch.countDown();
            }
        }
    }
}
