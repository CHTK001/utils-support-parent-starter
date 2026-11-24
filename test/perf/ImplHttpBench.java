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
import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP 服务端实现对比压测探针（Java 11 兼容，纯 JDK 客户端）。
 *
 * <p>用法：</p>
 * <pre>
 *   server &lt;impl&gt; &lt;port&gt;
 *       启动指定实现（jdk|nio|aio|vertx|quarkus|armeria），注册
 *       POST /echo（原样回显请求体）与 GET /ping（冒烟），打印 SERVER_READY 后常驻。
 *
 *   client &lt;host&gt; &lt;port&gt; &lt;workers&gt; &lt;seconds&gt; &lt;bodyBytes&gt; &lt;label&gt;
 *       先冒烟校验 /echo 回显一致性，再 3s 预热 + seconds 测量，输出
 *       mode=client label=.. conns=.. body=..B qps=.. tputMBps=.. p50us=.. p99us=.. errs=.. reqs=..
 * </pre>
 *
 * <p>回环口径：server 与 client 同机不同 JVM（127.0.0.1）；
 * 远程口径：server 在目标机容器（端口发布到宿主），client 在另一台机器走真实网卡。</p>
 */
public final class ImplHttpBench {

    /** 预热时长（毫秒），不计入统计 */
    private static final long WARMUP_MILLIS = 3000L;

    /** 每工作线程延迟采样上限（long 数组，无装箱） */
    private static final int MAX_SAMPLES_PER_WORKER = 256 * 1024;

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
        for (int i = 0; i < workers; i++) {
            Worker w = new Worker(client, request, total, errors, running, latch);
            ws.add(w);
            Thread t = new Thread(w, "probe-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(WARMUP_MILLIS);
        long baseline = total.sum();
        for (Worker w : ws) {
            w.reset();
        }
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        running.set(false);
        latch.await();
        double elapsed = (System.nanoTime() - start) / 1e9;

        long count = total.sum() - baseline;
        long err = errors.sum();
        long[] all = new long[(int) Math.min(count, Integer.MAX_VALUE - 8)];
        int n = 0;
        for (Worker w : ws) {
            for (int j = 0; j < w.size; j++) {
                if (n < all.length) {
                    all[n++] = w.latencies[j];
                }
            }
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
        private final long[] latencies = new long[MAX_SAMPLES_PER_WORKER];
        private volatile int size;

        Worker(HttpClient client, HttpRequest request, LongAdder total, LongAdder errors,
               AtomicBoolean running, CountDownLatch latch) {
            this.client = client;
            this.request = request;
            this.total = total;
            this.errors = errors;
            this.running = running;
            this.latch = latch;
        }

        void reset() {
            this.size = 0;
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    long t0 = System.nanoTime();
                    try {
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        if (size < latencies.length) {
                            latencies[size] = System.nanoTime() - t0;
                            size++;
                        }
                        total.increment();
                    } catch (Exception e) {
                        errors.increment();
                    }
                }
            } finally {
                latch.countDown();
            }
        }
    }
}
