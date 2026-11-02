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
 * 独立进程的压测客户端（配合 {@link HttpServerMain}）。
 * <p>用法: java HttpClientMain &lt;port&gt; &lt;connections&gt; &lt;duration_s&gt;</p>
 * <p>值回显，输出格式与 HttpBench 一致便于对比。</p>
 */
public class HttpClientMain {

    private static final String REQUEST =
            "GET /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n"
                    + "Accept: */*\r\nUser-Agent: bench\r\n\r\n";

    private static final int MAX_RES = 4096;

    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder ERRORS = new LongAdder();
    private static final LongAdder LATENCY = new LongAdder();
    private static final AtomicLongArray HIST = new AtomicLongArray(5001);

    private HttpClientMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int connections = Integer.parseInt(args[1]);
        int duration = Integer.parseInt(args[2]);

        List<Thread> threads = new ArrayList<>();
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            Client c = new Client(port, duration);
            clients.add(c);
            Thread t = new Thread(c, "client-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        long t0 = System.currentTimeMillis();
        for (Thread t : threads) {
            t.join(duration * 1000L + 3000);
        }
        long elapsed = System.currentTimeMillis() - t0;
        for (Client c : clients) {
            c.close();
        }

        long total = TOTAL.sum();
        long err = ERRORS.sum();
        int q50 = quantile(50);
        int q95 = quantile(95);
        int q99 = quantile(99);
        double avg = total == 0 ? 0 : (double) LATENCY.sum() / total / 1000.0;
        System.out.println("[client] result: requests=" + total + " err=" + err
                + " qps=" + total / Math.max(1L, duration)
                + " p50=" + q50 + "us p95=" + q95 + "us p99=" + q99 + "us"
                + " avg=" + String.format("%.3f", avg) + "us"
                + " elapsed=" + elapsed + "ms");
    }

    private static int quantile(int p) {
        long count = 0;
        for (int i = 0; i < HIST.length(); i++) {
            count += HIST.get(i);
        }
        if (count == 0) {
            return 0;
        }
        long idx = Math.round(p / 100.0 * count);
        idx = Math.max(0, Math.min(idx, count - 1));
        long acc = 0;
        for (int i = 0; i < HIST.length(); i++) {
            acc += HIST.get(i);
            if (acc >= idx) {
                return i;
            }
        }
        return HIST.length() - 1;
    }

    private static class Client implements Runnable {
        private final int port;
        private final int duration;
        private volatile Socket sock;
        private volatile boolean closed;
        private boolean closedFlag;
        private final byte[] buf = new byte[MAX_RES];

        Client(int port, int duration) {
            this.port = port;
            this.duration = duration;
        }

        @Override
        public void run() {
            try {
                sock = new Socket("127.0.0.1", port);
                sock.setKeepAlive(true);
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(5000);
                OutputStream out = sock.getOutputStream();
                InputStream in = sock.getInputStream();
                long deadline = System.nanoTime() + duration * 1_000_000_000L;
                while (System.nanoTime() < deadline) {
                    long start = System.nanoTime();
                    out.write(REQUEST.getBytes(StandardCharsets.UTF_8));
                    TOTAL.increment();
                    long end = readFullResponse(in, start);
                    if (end < 0) {
                        ERRORS.increment();
                        break;
                    }
                    LATENCY.add(end - start);
                }
            } catch (IOException e) {
                ERRORS.increment();
            } catch (RuntimeException e) {
                ERRORS.increment();
            } finally {
                close();
            }
        }

        /**
         * 读完整响应：在缓冲任意位置定位头部结束符，按 Content-Length 补齐 body。
         * @return 完成时刻的 nanoTime，失败返回 -1
         */
        private long readFullResponse(InputStream in, long start) throws IOException {
            int len = 0;
            int contentLen = -1;
            int headerEnd = -1;
            while (true) {
                int n = in.read(buf, len, MAX_RES - len);
                if (n <= 0) {
                    return -1;
                }
                len += n;
                if (headerEnd < 0) {
                    headerEnd = findHeaderEnd(len);
                    if (headerEnd >= 0) {
                        contentLen = parseContentLength(headerEnd);
                        if (contentLen < 0) {
                            contentLen = 0;
                        }
                    }
                }
                if (headerEnd >= 0) {
                    int have = len - headerEnd;
                    int need = contentLen - have;
                    while (need > 0) {
                        int r = in.read(buf, len, Math.min(need, MAX_RES - len));
                        if (r <= 0) {
                            return -1;
                        }
                        len += r;
                        need -= r;
                    }
                    return System.nanoTime();
                }
                if (len >= MAX_RES - 1) {
                    return -1;
                }
            }
        }

        private int findHeaderEnd(int len) {
            for (int i = 3; i < len; i++) {
                if (buf[i] == '\n' && buf[i - 1] == '\r' && buf[i - 2] == '\n' && buf[i - 3] == '\r') {
                    return i + 1;
                }
            }
            return -1;
        }

        private int parseContentLength(int headerEnd) {
            for (int i = 0; i + 13 < headerEnd; i++) {
                if (buf[i] == 'C' && buf[i + 1] == 'o' && buf[i + 2] == 'n' && buf[i + 3] == 't'
                        && buf[i + 4] == 'e' && buf[i + 5] == 'n' && buf[i + 6] == 't' && buf[i + 7] == '-'
                        && buf[i + 8] == 'L' && buf[i + 9] == 'e' && buf[i + 10] == 'n' && buf[i + 11] == 'g'
                        && buf[i + 12] == 't' && buf[i + 13] == ':') {
                    int j = i + 14;
                    while (j < headerEnd && (buf[j] == ' ' || buf[j] == '\t')) {
                        j++;
                    }
                    int v = 0;
                    boolean has = false;
                    while (j < headerEnd && buf[j] >= '0' && buf[j] <= '9') {
                        v = v * 10 + (buf[j] - '0');
                        j++;
                        has = true;
                    }
                    return has ? v : -1;
                }
            }
            return -1;
        }

        void close() {
            if (closedFlag) {
                return;
            }
            closedFlag = true;
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
}
