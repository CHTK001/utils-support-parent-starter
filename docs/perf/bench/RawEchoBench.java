import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** 纯 NIO 原始回环基准: 不带任何框架，衡量 loopback 下 socket 往返延迟 */
public class RawEchoBench {

    private static final String REQUEST = "GET /hello HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: keep-alive\r\nAccept: */*\r\n\r\n";
    private static final int MAX_RES = 1024;

    public static void main(String[] args) throws Exception {
        int connections = Integer.parseInt(args[0]);
        int duration = Integer.parseInt(args[1]);

        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
        int port = ssc.getLocalPort();
        System.out.println("[raw] port=" + port + " connections=" + connections + " duration=" + duration + "s");
        try {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            for (int i = 0; i < connections; i++) {
                final int idx = i;
                pool.submit(() -> worker(port, idx, duration));
            }
            Thread.sleep(duration * 1000L);
            pool.shutdownNow();
        } finally {
            ssc.close();
        }
    }

    private static void worker(int port, int id, int duration) throws Exception {
        int total = 0;
        List<Long> lat = new ArrayList<>();
        long t0 = System.nanoTime();
        while (true) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.setSoLinger(false, 0);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                byte[] buf = new byte[MAX_RES];
                while (true) {
                    long start = System.nanoTime();
                    out.write(REQUEST.getBytes(StandardCharsets.UTF_8));
                    int len = 0;
                    try {
                        int n = in.read(buf);
                        if (n <= 0) break;
                        len = n;
                    } catch (Exception e) {
                        break;
                    }
                    total++;
                    if (System.nanoTime() - start < 5_000_000_000L) {
                        lat.add((System.nanoTime() - start) / 1000);
                    }
                }
            } catch (Exception e) {
                break;
            }
            if (System.nanoTime() - t0 > duration * 1_000_000_000L) break;
        }
        double p50 = 0, p95 = 0, p99 = 0;
        if (!lat.isEmpty()) {
            java.util.Collections.sort(lat);
            p50 = lat.get(lat.size() / 2);
            p95 = lat.get((int) (lat.size() * 0.95));
            p99 = lat.get((int) (lat.size() * 0.99));
        }
        synchronized (System.out) {
            System.out.println("conn-" + id + " req=" + total + " qps=" + total / (double) duration
                + " p50=" + (long) p50 + "us p95=" + (long) p95 + "us p99=" + (long) p99 + "us");
        }
    }
}
