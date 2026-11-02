import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * 纯 Selector-NIO echo 服务端（单次 write），用于测量 Windows 上
 * 非阻塞 {@code SocketChannel.write} / {@code read} 的真实成本。
 * <p>用法: java NioEchoServerMain &lt;port&gt; &lt;eventLoops&gt; &lt;duration_s&gt;</p>
 * <p>与 HttpServerMain 同构：事件循环线程内联 read→write，无任何框架层。</p>
 */
public class NioEchoServerMain {

    private static volatile boolean running = true;

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
            final Selector sel = sels[i];
            Thread t = new Thread(() -> loop(sel), "echo-loop-" + i);
            t.setDaemon(true);
            t.start();
        }
        // accept 线程：注册到 loops[0]
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

        System.out.println("[echo] READY port=" + port + " loops=" + loops);
        System.out.flush();
        Thread.sleep(duration * 1_000L);
        running = false;
        System.out.println("[echo] done");
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
