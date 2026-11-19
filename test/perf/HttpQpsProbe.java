package perf;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.nio.NioHttpServer;

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
 * HTTP 回环压测探针：进程内自起一个 utils 网络 Server（NIO HTTP），再用 JDK HttpClient
 * 多线程压测，输出 QPS 与延迟分位数，用于量化吞吐优化（OkHttp 连接池复用、JDK HttpClient
 * 缓存等）前后的差异。
 *
 * <p><b>运行约束：</b>本文件仅落盘备用，<b>运行前必须经人工批准</b>。探针为单进程回环
 * （server 与 client 同进程），不依赖、也不启动项目的任何前端/后端服务。</p>
 *
 * <p>编译与运行（在 utils-support-parent-starter 目录下，PowerShell）：</p>
 * <pre>
 *   # 1) 生成 common-starter 运行期 classpath
 *   cd utils-support-core-parent/utils-support-common-starter
 *   mvn -o -q dependency:build-classpath `
 *       -Dmdep.outputFile=D:/ch/project/utils-support-parent-starter/test/perf/cp.txt
 *   cd ../../..
 *
 *   # 2) 编译探针
 *   $cp = Get-Content test/perf/cp.txt -Raw
 *   javac -encoding UTF-8 -cp $cp -d test/perf/classes test/perf/HttpQpsProbe.java
 *
 *   # 3) 运行（示例：随机端口 / 16 并发 / 10 秒 / 压 /ping）
 *   java -cp "$cp;test/perf/classes" perf.HttpQpsProbe 0 16 10 /ping
 * </pre>
 *
 * <p>对照组建议：同一参数分别压 {@code /ping}（内建探针短路路径，衡量裸传输层开销）与
 * {@code /hello}（经过路由映射的业务路径）；改动前后各跑一轮取均值。</p>
 *
 * @author CH
 * @since 4.0.0.54
 */
public final class HttpQpsProbe {

    /**
     * 预热时长（毫秒）：不计入统计
     */
    private static final long WARMUP_MILLIS = 2_000L;

    /**
     * 每线程延迟采样上限（long 数组，无装箱；溢出后仅计数不再采样）
     */
    private static final int MAX_SAMPLES_PER_WORKER = 256 * 1024;

    /**
     * 工具类不允许实例化
     */
    private HttpQpsProbe() {
    }

    /**
     * 入参：args[0]=端口(0=随机) args[1]=并发线程数 args[2]=测量时长(秒) args[3]=压测路径
     *
     * @param args 命令行参数
     * @throws Exception 服务启动或压测失败
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        String path = args.length > 3 ? args[3] : "/ping";

        // 1) 进程内自起 Server：同时开启管理端点，对 /healthz、/metrics 顺带冒烟
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.getManagement().setMetricsEnabled(true);
        setting.getManagement().setHealthEnabled(true);

        AbstractServer server = new NioHttpServer(setting);
        server.registerMapping("/ping", HttpMethod.GET,
                (req, resp) -> { resp.setResult("OK"); });
        server.registerMapping("/hello", HttpMethod.GET,
                (req, resp) -> { resp.setResult("{\"code\":0,\"data\":\"ok\"}"); });
        server.start();
        int actualPort = server.getPort();
        System.out.println("server started: http://127.0.0.1:" + actualPort
                + "  path=" + path + "  workers=" + workers + "  duration=" + seconds + "s");

        smoke(actualPort);

        // 2) 压测客户端：HTTP/1.1 避免 h2c 升级握手干扰计时
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + actualPort + path))
                .GET()
                .build();

        LongAdder total = new LongAdder();
        LongAdder errors = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);
        List<Worker> workerList = new ArrayList<>(workers);
        CountDownLatch latch = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) {
            Worker w = new Worker(client, request, total, errors, running, latch);
            workerList.add(w);
            Thread.ofPlatform().name("probe-worker-" + i).start(w);
        }

        // 3) 预热后记录基线并清空延迟采样（基线差值法统计请求数，无竞态）
        Thread.sleep(WARMUP_MILLIS);
        long baseline = total.sum();
        workerList.forEach(Worker::reset);
        long windowStart = System.nanoTime();

        // 4) 测量窗口
        Thread.sleep(seconds * 1000L);
        running.set(false);
        latch.await();
        double elapsedSec = (System.nanoTime() - windowStart) / 1e9;

        // 5) 汇总延迟分位数
        long count = total.sum() - baseline;
        long err = errors.sum();
        long[] all = new long[(int) Math.min(count, Integer.MAX_VALUE - 8)];
        int n = 0;
        for (Worker w : workerList) {
            for (int j = 0; j < w.size; j++) {
                if (n < all.length) {
                    all[n++] = w.latencies[j];
                }
            }
        }
        Arrays.sort(all, 0, n);
        System.out.printf("requests=%d errors=%d elapsed=%.2fs QPS=%.0f avg=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms%n",
                count, err, elapsedSec, count / elapsedSec,
                avg(all, n), pct(all, n, 0.50), pct(all, n, 0.95), pct(all, n, 0.99), max(all, n));

        server.stop();
        System.exit(err == 0 ? 0 : 1);
    }

    /**
     * 管理端点冒烟：/healthz 与 /metrics 各请求一次并打印结果摘要（非压测）。
     *
     * @param port 服务端口
     */
    private static void smoke(int port) {
        HttpClient client = HttpClient.newHttpClient();
        for (String p : new String[]{"/healthz", "/metrics"}) {
            try {
                HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + p)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                String head = resp.body();
                if (head != null && head.length() > 120) {
                    head = head.substring(0, 120) + "...";
                }
                System.out.println("smoke " + p + " -> " + resp.statusCode() + " " + head);
            } catch (Exception e) {
                System.out.println("smoke " + p + " -> FAILED: " + e.getMessage());
            }
        }
    }

    /**
     * 平均延迟（毫秒）。
     */
    private static double avg(long[] a, int n) {
        if (n == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += a[i];
        }
        return sum / (double) n / 1_000_000.0;
    }

    /**
     * 分位数延迟（毫秒）。
     */
    private static double pct(long[] a, int n, double p) {
        if (n == 0) {
            return 0;
        }
        int idx = (int) Math.min(n - 1, Math.max(0, (int) Math.round(p * n) - 1));
        return a[idx] / 1_000_000.0;
    }

    /**
     * 最大延迟（毫秒）。
     */
    private static double max(long[] a, int n) {
        return n == 0 ? 0 : a[n - 1] / 1_000_000.0;
    }

    /**
     * 单个压测线程：本地延迟数组（long，无装箱），溢出后仅计数不再采样。
     */
    private static final class Worker implements Runnable {
        private final HttpClient client;
        private final HttpRequest request;
        private final LongAdder total;
        private final LongAdder errors;
        private final AtomicBoolean running;
        private final CountDownLatch latch;
        private final long[] latencies = new long[MAX_SAMPLES_PER_WORKER];
        private int size;

        Worker(HttpClient client, HttpRequest request, LongAdder total, LongAdder errors,
               AtomicBoolean running, CountDownLatch latch) {
            this.client = client;
            this.request = request;
            this.total = total;
            this.errors = errors;
            this.running = running;
            this.latch = latch;
        }

        /**
         * 测量窗口开始：丢弃预热期延迟采样。
         */
        void reset() {
            this.size = 0;
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    long t0 = System.nanoTime();
                    try {
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                        if (size < latencies.length) {
                            latencies[size++] = System.nanoTime() - t0;
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
