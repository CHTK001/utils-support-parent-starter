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
import java.net.Socket;
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
        System.out.println("[bench] type=" + type + " connections=" + connections + " duration=" + duration + "s");
        System.out.println("[bench] setting: ioThreads=" + setting.getIoThreads()
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
        System.out.println(report);
        System.out.println("[bench] elapsed=" + elapsed + "ms");
        System.out.println("[bench] server metrics: total=" + abs.getMetrics().getTotalRequests()
                + " active=" + abs.getMetrics().getActiveRequests()
                + " err=" + abs.getMetrics().getErrorCount()
                + " avgms=" + abs.getMetrics().getAverageLatencyMillis()
                + " maxms=" + abs.getMetrics().getMaxLatencyMillis());
        System.out.println("[bench] server metrics: " + abs.getMetrics().getLatencyHistogram());
        System.out.println("[bench] result: " + report);
    }

    /** 客户端工作线程, 模拟 keep-alive 连接 */
    private static class Runner {

        private final int port;
        private final int connections;
        private final int duration;

        private final List<Client> clients = new ArrayList<>();
        private final List<Thread> threads = new ArrayList<>();
        private final LongAdder totalRequests = new LongAdder();
        private final LongAdder totalLatency = new LongAdder();
        private final LongAdder errors = new LongAdder();
        /** 延迟桶 (us 0..5000) */
        private final AtomicLongArray hist = new AtomicLongArray(5001);

        private volatile boolean running = true;

        public Runner(int port, int connections, int duration) {
            this.port = port;
            this.connections = connections;
            this.duration = duration;
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
            double avg = totalRequests.sum() == 0 ? 0 : (double) totalLatency.sum() / total / 1_000_000;
            StringBuilder sb = new StringBuilder();
            sb.append("requests=").append(total).append(" err=").append(err)
                    .append(" qps=").append(total / (long) duration)
                    .append(" p50=").append(q50).append("us")
                    .append(" p95=").append(q95).append("us")
                    .append(" p99=").append(q99).append("us")
                    .append(" avg=").append(String.format("%.3f", avg)).append("us");
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
            private final byte[] buf = new byte[MAX_RES];

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
                        totalRequests.increment();
                        int read = readFullResponse(in);
                        if (read > 0) {
                            totalLatency.increment();
                            addHist((int) ((System.nanoTime() - start) / 1_000));
                        } else {
                            errors.increment();
                        }
                    }
                } catch (IOException e) {
                    errors.increment();
                } finally {
                    close();
                }
            }

            /** 读完整响应: header(到 \r\n\r\n), 解析 Content-Length, 再抽 body */
            private int readFullResponse(InputStream in) throws IOException {
                int len = 0;
                int contentLen = 0;
                boolean parsed = false;
                while (true) {
                    int n = in.read(buf, len, MAX_RES - len);
                    if (n <= 0) {
                        return -1;
                    }
                    len += n;
                    if (!parsed && len >= 4) {
                        boolean end = true;
                        for (int i = len - 4; i < len; i++) {
                            if (buf[i] != (i == len - 4 || i == len - 2 ? 13 : 10)) {
                                end = false;
                                break;
                            }
                        }
                        if (end) {
                            // 解析 Content-Length: N
                            for (int i = 0; i < len - 4; i++) {
                                if (buf[i] == 'C' && buf[i + 1] == 'o' && buf[i + 2] == 'n'
                                        && buf[i + 3] == 't' && buf[i + 4] == 'e' && buf[i + 5] == 'n'
                                        && buf[i + 6] == 't' && buf[i + 7] == '-' && buf[i + 8] == 'L'
                                        && buf[i + 9] == 'e' && buf[i + 10] == 'n' && buf[i + 11] == 'g'
                                        && buf[i + 12] == 't' && buf[i + 13] == ':') {
                                    int j = i + 14;
                                    int v = 0;
                                    boolean has = false;
                                    while (j < len - 4 && buf[j] >= '0' && buf[j] <= '9') {
                                        v = v * 10 + (buf[j] - '0');
                                        j++;
                                        has = true;
                                    }
                                    if (has) {
                                        contentLen = v;
                                    }
                                    break;
                                }
                            }
                            parsed = true;
                            break;
                        }
                    }
                    if (len >= MAX_RES - BODY_LEN) {
                        return len;
                    }
                }
                if (contentLen <= 0) {
                    return len;
                }
                int total = len;
                int remaining = contentLen;
                while (remaining > 0) {
                    int n = in.read(buf, total, Math.min(remaining, MAX_RES - total));
                    if (n <= 0) {
                        return -1;
                    }
                    total += n;
                    remaining -= n;
                }
                return total;
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
