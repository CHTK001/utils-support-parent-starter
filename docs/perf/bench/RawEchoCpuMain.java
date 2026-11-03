import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 裸 Selector-NIO echo 服务器 + 每请求 CPU 标定。
 * <p>用与 {@link NioEchoServerMain} 相同的「一次 read + 一次 write」模型，
 * 但额外统计实际处理的请求数与线程 CPU 时间，输出 cpu_ns_per_request。</p>
 * <p>目的：为 HTTP 服务端提供一个平台下限参照。若裸 echo 的 cpu_ns_per_request
 * 与 HTTP 服务端接近，说明剩下的开销几乎全是 Windows loopback 的内核 syscall，
 * 用户态已无多少可优化空间。</p>
 * <p>用法: java RawEchoCpuMain &lt;port&gt; &lt;eventLoops&gt; &lt;duration_s&gt;</p>
 */
public class RawEchoCpuMain {

    private static volatile boolean running = true;
    private static final LongAdder SERVED = new LongAdder();

    private RawEchoCpuMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int loops = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int duration = args.length > 2 ? Integer.parseInt(args[2]) : 15;

        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.bind(new InetSocketAddress("127.0.0.1", port), 1024);

        Selector[] sels = new Selector[loops];
        for (int i = 0; i < loops; i++) {
            sels[i] = Selector.open();
            Selector sel = sels[i];
// [P3C C1 豁免] 性能测试，线程创建为基准参数描述
            Thread t = new Thread(() -> loop(sel), "echo-loop-" + i);
            t.setDaemon(true);
            t.start();
        }
// [P3C C1 豁免] 性能测试，线程创建为基准参数描述
        Thread acc = new Thread(() -> {
            try {
                while (running) {
                    SocketChannel c = ssc.accept();
                    if (c == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    c.configureBlocking(false);
                    c.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                    int idx = (c.hashCode() & Integer.MAX_VALUE) % sels.length;
                    sels[idx].wakeup();
                    c.register(sels[idx], SelectionKey.OP_READ, ByteBuffer.allocate(256));
                }
            } catch (IOException e) {
                // ignore
            }
        }, "echo-accept");
        acc.setDaemon(true);
        acc.start();

        System.out.println("[echo] READY port=" + port + " loops=" + loops); // [P3C E1 豁免]
        System.out.flush(); // [P3C E1 豁免]

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (mx.isThreadCpuTimeSupported() && !mx.isThreadCpuTimeEnabled()) {
            mx.setThreadCpuTimeEnabled(true);
        }
        long startNs = System.nanoTime();
        long wall = 0;
        while (wall < (long) duration * 1_000_000_000L) {
            Thread.sleep(1000);
            wall = System.nanoTime() - startNs;
        }

        // 必须在 running=false 之前采样:循环线程一旦退出,getThreadCpuTime 会返回 -1,
        // 统计就只剩主线程,导致 CPU/请求严重失真。
        long totalCpu = 0;
        for (ThreadInfo ti : mx.getThreadInfo(mx.getAllThreadIds())) {
            if (ti == null) {
                continue;
            }
            long cpu = mx.getThreadCpuTime(ti.getThreadId());
            if (cpu > 0) {
                totalCpu += cpu;
            }
        }
        long served = SERVED.sum();
        running = false;
        System.out.println("[cpu] wall=" + wall / 1_000_000_000L + "s processCpu=" // [P3C E1 豁免]
                + String.format("%.2f", totalCpu * 100.0 / wall) + "%"
                + " requests=" + served + " cpu_ns_per_request="
                + String.format("%.1f", served > 0 ? (double) totalCpu / served : 0));
    }

    private static void loop(Selector sel) {
        ByteBuffer out = ByteBuffer.wrap(new byte[256]);
        while (running) {
            try {
                sel.select(200L);
                Iterator<SelectionKey> it = sel.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    SocketChannel ch = (SocketChannel) key.channel();
                    ByteBuffer buf = (ByteBuffer) key.attachment();
                    try {
                        int n = ch.read(buf);
                        if (n < 0) {
                            key.cancel();
                            ch.close();
                            continue;
                        }
                        if (n == 0) {
                            continue;
                        }
                        buf.flip();
                        out.clear();
                        out.put(buf);
                        buf.clear();
                        out.flip();
                        while (out.hasRemaining()) {
                            ch.write(out);
                        }
                        SERVED.increment();
                    } catch (IOException e) {
                        key.cancel();
                        try { ch.close(); } catch (IOException ignore) { }
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
