import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能基准测试工具:测量 server 并发连接数 / 吞吐 / 延迟。
 *
 * <p>用法: java HttpBench &lt;type: nio|aio|jdk&gt; &lt;connections&gt; &lt;duration_s&gt;
 * 例: java HttpBench nio 5000 30</p>
 *
 * 客户度用原始 Socket 模拟 keep-alive, 每连接持续发请求到 duration 秒结束。
 */
public class HttpBench {

    /** echo handler: 返回 "hello bench" */
    private static final ServerHandler ECHO = (req, res) -> {
        res.setBody("hello bench");
        res.end();
    };

    /** 请求报文模板(keep-alive) */
    private static final String REQUEST =
            "GET /echo HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: keep-alive\r\n"
                    + "Accept: */*\r\nUser-Agent: bench\r\n\r\n";

    /** 响应头里 Content-Length 的 Target */
    private static final int RESPONSE_BODY_LEN = "hello bench".length();

    /** 单个请求完整报文(头 + 体) */
    private static final int RESPONSE_SIZE = 500;

    private HttpBench() {
    }

    public static void main(String[] args) throws Exception {
        String type = args.length > 0 ? args[0] : "nio";
        int connections = Integer.parseInt(args.length > 1 ? args[1] : "5000");
        int duration = Integer.parseInt(args.length > 2 ? args[2] : "30");

        ServerSetting setting = ServerSetting.auto();
        setting.setInlineDispatch(true); // 关闭虚拟线程,事件循环全速内联
        setting.setMaxConcurrency(-1); // 不限制
        setting.setResponseTimeout(-1); // 不限制
        setting.setMaxKeepAliveRequests(-1);
        System.out.println("[bench] type=" + type + " connections=" + connections + " duration=" + duration + "s");
        System.out.println("[bench] setting: ioThreads=" + setting.getIoThreads()
                + " eventLoops=" + setting.getEventLoops() + " maxConcurrency=" + setting.getMaxConcurrency()
                + " maxConnections=" + setting.getMaxConnections() + " backlog=" + setting.getBacklog());

        Server server;
        if ("aio".equals(type)) {
            server = new AioHttpServer(setting);
        } else if ("jdk".equals(type)) {
            System.out.println("[bench] warning: jdk server is not an NIO implementation, using nio");
            server = new NioHttpServer(setting);
        } else {
            server = new NioHttpServer(setting);
        }
        server.registerMapping("/echo", HttpMethod.GET, ECHO);
        server.start();
        try {
            runBench(server, connections, duration);
        } finally {
            server.stop();
        }
    }

    private static void runBench(Server server, int connections, int duration) throws Exception {
        int port = server.getPort();
        Runner runner = new Runner(port, connections, duration);
        long t0 = System.currentTimeMillis();
        runner.start();
        Thread.sleep((long) duration * 1000);
        runner.stop();
        runner.complete();
        long elapsed = System.currentTimeMillis() - t0;
        String report = runner.report();
        System.out.println(report);
        System.out.println("[bench] elapsed=" + elapsed + "ms");
        System.out.println("[bench] metrics: total=" + server.getMetrics().getTotalRequests()
                + " active=" + server.getMetrics().getActiveRequests()
                + " err=" + server.getMetrics().getErrorCount()
                + " avgms=" + server.getMetrics().getAverageLatencyMillis()
                + " maxms=" + server.getMetrics().getMaxLatencyMillis());
        System.out.println("[bench] metrics: " + server.getMetrics().getLatencyHistogram());
        System.out.println("[bench] metrics: " + report);
    }

    /**
     * 客户端工作线程,模拟 keep-alive 连接。
     */
    private static class Runner {

        private final int port;
        private final int connections;
        private final int duration;

        private final List<Client> clients = new ArrayList<>();
        private final List<Thread> threads = new ArrayList<>();
        private final AtomicLong totalRequests = new AtomicLong();
        private final AtomicLong totalLatency = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong[] latencies;

        private volatile boolean running = true;
        private volatile boolean done = false;

        public Runner(int port, int connections, int duration) {
            this.port = port;
            this.connections = connections;
            this.duration = duration;
            // 万级并发下建小数组加速
            this.latencies = new AtomicLong[1024];
            for (int i = 0; i < 1024; i++) {
                latencies[i] = new AtomicLong();
            }
        }

        public void start() throws Exception {
            for (int i = 0; i < connections; i++) {
                Client c = new Client(i);
                clients.add(c);
                Thread t = new Thread(c, "bench-conn-" + i);
                t.setDaemon(true);
                t.start();
                threads.add(t);
            }
        }

        public void stop() {
            running = false;
            for (Client c : clients) {
                c.close();
            }
        }

        /** 等待所有客户端线程结束 */
        public void complete() throws Exception {
            Thread.sleep(500);
            for (Thread t : threads) {
                t.join(2000);
            }
            Thread.sleep(1000);
        }

        public String report() {
            long total = totalRequests.get();
            int q50 = _quantile(50);
            int q95 = _quantile(95);
            int q99 = _quantile(99);
            double avg = totalRequests.get() == 0 ? 0 : (double) totalLatency.get() / total / 1_000_000;
            return "requests=" + total + " err=" + errors.get()
                    + " qps=" + (total / Math.max(duration, 1))
                    + " p50=" + q50 + "ms p95=" + q95 + "ms p99=" + q99 + "ms"
                    + " avg=" + new StringBuilder().append(avg).append("ms");
        }

        private int _quantile(int p) {
            // 用所有 latencies 值做简单分位数
            long list[] = new long[latencies.length];
            int count = 0;
            for (AtomicLong al : latencies) {
                long v = al.get();
                if (v > 0) {
                    list[count++] = v;
                }
            }
            if (count == 0) {
                return 0;
            }
            if (count == 1) {
                return (int) list[0];
            }
            // 快速排序前一部分
            java.util.Arrays.parallelSort(list, 0, count);
            long idx = Math.round(p / 100.0 * count);
            if (idx <= 0) {
                return (int) list[0];
            }
            if (idx >= count) {
                return (int) list[count - 1];
            }
            return (int) list[(int) idx];
        }

        public class Client implements Runnable {
            private final int id;
            private volatile Socket sock;
            private volatile boolean closed;

            public Client(int id) {
                this.id = id;
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
                        out.write(REQUEST.getBytes(StandardCharsets.UTF_8));
                        totalRequests.incrementAndGet();
                        // 读回: 固定长度
                        int read = 0;
                        byte[] buf = new byte[RESPONSE_SIZE];
                        try {
                            read = in.read(buf);
                        } catch (IOException e) {
                            break;
                        }
                        if (read > 0) {
                            totalLatency.addAndGet(System.nanoTime() - start);
                            addLatency((int) ((System.nanoTime() - start) / 1_000_000));
                        } else {
                            errors.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    errors.incrementAndGet();
                } finally {
                    close();
                }
            }

            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    if (sock != null) {
                        sock.close();
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        private void addLatency(int ms) {
            for (AtomicLong al : latencies) {
                al.getAndIncrement();
                return;
            }
        }
    }
}
