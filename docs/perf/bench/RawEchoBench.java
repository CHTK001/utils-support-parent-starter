import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯 socket echo 基准（无框架）：衡量 loopback 下 socket 往返延迟。
 * 用法: java RawEchoBench <connections> <duration_s>
 */
public class RawEchoBench {

    private static final int MSG_LEN = 256;

    public static void main(String[] args) throws Exception {
        int connections = Integer.parseInt(args[0]);
        int duration = Integer.parseInt(args[1]);

        ServerSocketChannel ssc = ServerSocketChannel.open();
        InetSocketAddress bind = new InetSocketAddress("127.0.0.1", 0);
        ssc.bind(bind);
        InetSocketAddress local = (InetSocketAddress) ssc.getLocalAddress();
        System.out.println("[raw] port=" + local.getPort() + " connections=" + connections + " duration=" + duration + "s");

        try {
            Thread server = new Thread(() -> {
                try {
                    while (true) {
                        SocketChannel c = ssc.accept();
                        new Thread(() -> echo(c), "raw-echo").start();
                    }
                } catch (IOException e) {
                    System.err.println("[raw] server err: " + e);
                }
            });
            server.setDaemon(true);
            server.start();

            List<Thread> clients = new ArrayList<>();
            AtomicLong counter = new AtomicLong();
            AtomicLong latSum = new AtomicLong();
            AtomicLong errCount = new AtomicLong();

            for (int i = 0; i < connections; i++) {
                final int idx = i;
                Thread t = new Thread(() -> {
                    try {
                        SocketChannel c = SocketChannel.open();
                        c.connect(local);
                        c.setOption(java.net.StandardSocketOptions.SO_KEEPALIVE, true);
                        c.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                        byte[] req = new byte[MSG_LEN];
                        for (int k = 0; k < MSG_LEN; k++) req[k] = (byte) (k & 0xFF);
                        ClientRunner(duration, c, req, latSum, counter, errCount);
                    } catch (Exception e) {
                        System.err.println("[raw] conn-" + idx + " err: " + e);
                        errCount.incrementAndGet();
                    }
                }, "raw-echo-client-" + i);
                t.setDaemon(true);
                clients.add(t);
                t.start();
            }

            for (Thread t : clients) {
                t.join(2000);
            }
            Thread.sleep(500);
            long total = counter.get();
            double avg = total > 0 ? latSum.get() / total : 0;
            System.out.println("raw: total=" + total + " err=" + errCount.get() + " avg=" + (long) (avg / 1_000_000) + "us");
            server.interrupt();
        } finally {
            ssc.close();
        }
    }

    private static void ClientRunner(int duration, SocketChannel c, byte[] req, AtomicLong latSum, AtomicLong counter, AtomicLong errCount) {
        try {
            ByteBuffer outB = ByteBuffer.wrap(req);
            byte[] buf = new byte[MSG_LEN];
            long deadline = System.nanoTime() + duration * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                long start = System.nanoTime();
                outB.position(0);
                outB.limit(MSG_LEN);
                c.write(outB);
                ByteBuffer inB = ByteBuffer.wrap(buf);
                int read = 0;
                try {
                    while (read < MSG_LEN) {
                        int r = c.read(inB);
                        if (r > 0) read += r;
                        else if (r == -1) break;
                    }
                } catch (Exception e) {
                    errCount.incrementAndGet();
                    if (e instanceof IOException) {
                        System.err.println("[raw] conn io err: " + e);
                    }
                    break;
                }
                latSum.addAndGet(System.nanoTime() - start);
                counter.incrementAndGet();
            }
        } catch (Exception e) {
            errCount.incrementAndGet();
            System.err.println("[raw] runner err: " + e);
        }
    }

    private static void echo(SocketChannel c) {
        byte[] buf = new byte[4096];
        try {
            while (true) {
                int r = c.read(ByteBuffer.wrap(buf));
                if (r <= 0) break;
                ByteBuffer b = ByteBuffer.wrap(buf, 0, r);
                while (b.hasRemaining()) {
                    c.write(b);
                }
            }
        } catch (IOException e) {
        } finally {
            try { c.close(); } catch (IOException e) { }
        }
    }
}
