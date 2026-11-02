import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 纯 socket 回显压测客户端（配合 {@link NioEchoServerMain}）。
 * <p>每次发送 256 字节，读回 256 字节，统计吞吐与延迟。</p>
 * <p>用法: java RawClientMain &lt;port&gt; &lt;connections&gt; &lt;duration_s&gt;</p>
 */
public class RawClientMain {

    private static final int MSG = 256;

    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder ERRORS = new LongAdder();
    private static final LongAdder LATENCY = new LongAdder();
    private static final AtomicLongArray HIST = new AtomicLongArray(5001);

    private RawClientMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int connections = Integer.parseInt(args[1]);
        int duration = Integer.parseInt(args[2]);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            Thread t = new Thread(new Client(port, duration), "rawcli-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(duration * 1000L + 3000);
        }

        long total = TOTAL.sum();
        long err = ERRORS.sum();
        int q50 = quantile(50);
        int q95 = quantile(95);
        int q99 = quantile(99);
        double avg = total == 0 ? 0 : (double) LATENCY.sum() / total / 1000.0;
        System.out.println("[rawcli] result: requests=" + total + " err=" + err
                + " qps=" + total / Math.max(1L, duration)
                + " p50=" + q50 + "us p95=" + q95 + "us p99=" + q99 + "us"
                + " avg=" + String.format("%.3f", avg) + "us");
    }

    private static int quantile(int p) {
        long count = 0;
        for (int i = 0; i < HIST.length(); i++) {
            count += HIST.get(i);
        }
        if (count == 0) {
            return 0;
        }
        long idx = Math.max(0, Math.min(Math.round(p / 100.0 * count), count - 1));
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

        Client(int port, int duration) {
            this.port = port;
            this.duration = duration;
        }

        @Override
        public void run() {
            byte[] req = new byte[MSG];
            for (int i = 0; i < MSG; i++) {
                req[i] = (byte) (i & 0xFF);
            }
            byte[] buf = new byte[MSG];
            try (Socket sock = new Socket("127.0.0.1", port)) {
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(5000);
                OutputStream out = sock.getOutputStream();
                InputStream in = sock.getInputStream();
                long deadline = System.nanoTime() + duration * 1_000_000_000L;
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    out.write(req);
                    int read = 0;
                    while (read < MSG) {
                        int n = in.read(buf, read, MSG - read);
                        if (n <= 0) {
                            ERRORS.increment();
                            return;
                        }
                        read += n;
                    }
                    TOTAL.increment();
                    long d = System.nanoTime() - t0;
                    LATENCY.add(d);
                    int us = (int) (d / 1000L);
                    if (us >= 0 && us < HIST.length()) {
                        HIST.getAndIncrement(us);
                    }
                }
            } catch (Exception e) {
                ERRORS.increment();
            }
        }
    }
}
