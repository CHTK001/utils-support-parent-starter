package perf;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.nio.NioHttpServer;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Allocation-focused A/B probe: starts an in-process utils HTTP server (NIO or AIO),
 * drives a fixed keep-alive workload, and reports the total heap bytes allocated by
 * all live threads during the measured window divided by the number of requests.
 *
 * <p>Rationale: on a shared, CPU-saturated host, wall-clock QPS is not comparable
 * across runs, but allocation volume is deterministic for a given code path. The
 * client-side allocation is identical across variants, so deltas reflect server-side
 * changes (header-name interning, frame scratch reuse, combine-buffer pooling).</p>
 *
 * <p>args: [0]=port(0=random) [1]=workers [2]=seconds [3]=path [4]=aio(optional "aio")</p>
 */
public final class HttpAllocProbe {

    private HttpAllocProbe() {
    }

    /**
     * Entry point.
     *
     * @param args command line args
     * @throws Exception on server or workload failure
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        String path = args.length > 3 ? args[3] : "/ping";
        boolean aio = args.length > 4 && "aio".equalsIgnoreCase(args[4]);

        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.getManagement().setMetricsEnabled(true);
        setting.getManagement().setHealthEnabled(true);

        AbstractServer server = aio ? new AioHttpServer(setting) : new NioHttpServer(setting);
        server.registerMapping("/ping", HttpMethod.GET,
                (req, resp) -> { resp.setResult("OK"); });
        server.registerMapping("/hello", HttpMethod.GET,
                (req, resp) -> { resp.setResult("{\"code\":0,\"data\":\"ok\"}"); });
        server.start();
        int actualPort = server.getPort();
        System.out.println("alloc-probe started: http://127.0.0.1:" + actualPort
                + "  path=" + path + "  workers=" + workers + "  duration=" + seconds + "s  aio=" + aio);

        com.sun.management.ThreadMXBean tmxb =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!tmxb.isThreadAllocatedMemoryEnabled()) {
            tmxb.setThreadAllocatedMemoryEnabled(true);
        }

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
            Thread.ofPlatform().name("alloc-probe-worker-" + i).start(w);
        }

        // warmup, then freeze the allocation baseline together with the request baseline
        Thread.sleep(3000);
        long requestBaseline = total.sum();
        long allocA = snapshot(tmxb);
        long windowStart = System.nanoTime();

        Thread.sleep(seconds * 1000L);
        running.set(false);
        latch.await();
        double elapsedSec = (System.nanoTime() - windowStart) / 1e9;
        long allocB = snapshot(tmxb);

        long count = total.sum() - requestBaseline;
        long err = errors.sum();
        long allocTotal = Math.max(0, allocB - allocA);
        double perReq = count > 0 ? (double) allocTotal / count : Double.NaN;
        System.out.printf("ALLOC count=%d errors=%d elapsed=%.2fs allocBytes=%d allocPerReq=%.1f QPS=%.0f%n",
                count, err, elapsedSec, allocTotal, perReq, count / elapsedSec);

        server.stop();
        // deterministic exit: benchmark probes must not linger on non-daemon threads
        System.exit(0);
    }

    /**
     * Sums allocated bytes over all currently live threads; dead threads report -1 and are skipped.
     */
    private static long snapshot(com.sun.management.ThreadMXBean tmxb) {
        long sum = 0;
        for (long id : tmxb.getAllThreadIds()) {
            long b = tmxb.getThreadAllocatedBytes(id);
            if (b >= 0) {
                sum += b;
            }
        }
        return sum;
    }

    /**
     * Keep-alive GET worker.
     */
    private static final class Worker implements Runnable {
        private final HttpClient client;
        private final HttpRequest request;
        private final LongAdder total;
        private final LongAdder errors;
        private final AtomicBoolean running;
        private final CountDownLatch latch;

        Worker(HttpClient client, HttpRequest request, LongAdder total, LongAdder errors,
               AtomicBoolean running, CountDownLatch latch) {
            this.client = client;
            this.request = request;
            this.total = total;
            this.errors = errors;
            this.running = running;
            this.latch = latch;
        }

        @Override
        public void run() {
            while (running.get()) {
                try {
                    HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() == 200) {
                        total.increment();
                    } else {
                        errors.increment();
                    }
                } catch (Exception e) {
                    errors.increment();
                }
            }
            latch.countDown();
        }
    }
}
