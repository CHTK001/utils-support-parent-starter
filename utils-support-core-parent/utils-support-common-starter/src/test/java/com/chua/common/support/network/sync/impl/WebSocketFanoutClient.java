// [P3C A20 豁免] A20/A30/A31: 压测客户端与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 独立进程的 WebSocket 广播客户端（压测端）。
 *
 * <p><b>为什么要单独进程</b>：同一 JVM 里既跑服务端又跑两万个客户端虚拟线程时，
 * 实测服务端 <i>20,000 accept + 20,000 握手全部成功</i>，但客户端线程在「读 101」
 * 上集体超时——是压测端被饿死，不是服务端没干活。把客户端拆出去才能测到真实容量。</p>
 *
 * <p>用法：{@code java -cp <cp> WebSocketFanoutClient <port> <clients> <frames> [chunk] [gapMs]}</p>
 *
 * <p>输出两行给父进程读：
 * <pre>
 * READY connected=N failures=F err=...
 * DONE delivered=X/Y elapsedMs=Z
 * </pre></p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class WebSocketFanoutClient {

    /**
     * 工具类不允许实例化。
     */
    private WebSocketFanoutClient() {
    }

    /**
     * 入口。
     *
     * @param args port clients frames [chunk] [gapMs]
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            // [P3C E1 豁免]
            System.out.println("USAGE: <port> <clients> <frames> [chunk] [gapMs]");
            // [P3C SE-安全 豁免]
            System.exit(2);
            return;
        }
        int port = Integer.parseInt(args[0]);
        int clients = Integer.parseInt(args[1]);
        int frames = Integer.parseInt(args[2]);
        int chunk = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        long gapMs = args.length > 4 ? Long.parseLong(args[4]) : 250L;

        AtomicIntegerArray received = new AtomicIntegerArray(clients);
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> firstError = new AtomicReference<>();

        for (int i = 0; i < clients; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try (Socket socket = connect(port)) {
                    connected.incrementAndGet();
                    InputStream in = socket.getInputStream();
                    while (received.get(index) < frames) {
                        byte[] frame = readServerFrame(in);
                        if (frame == null) {
                            return;
                        }
                        received.incrementAndGet(index);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    firstError.compareAndSet(null, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
            // 分批建连：Windows 的 listen() 有效 accept 队列约 200（SOMAXCONN 上限）
            if ((i + 1) % chunk == 0) {
                Thread.sleep(gapMs);
            }
        }

        long readyDeadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < readyDeadline
                && connected.get() + failures.get() < clients) {
            Thread.sleep(50L);
        }
        // [P3C E1 豁免]
        System.out.println("READY connected=" + connected.get() + " failures=" + failures.get()
                + " err=" + firstError.get());
        // [P3C E1 豁免]
        System.out.flush();

        long expected = (long) clients * frames;
        long start = System.currentTimeMillis();
        long deadline = start + 180_000L;
        long delivered = 0;
        while (System.currentTimeMillis() < deadline) {
            delivered = 0;
            for (int i = 0; i < clients; i++) {
                delivered += received.get(i);
            }
            if (delivered >= expected) {
                break;
            }
            Thread.sleep(20L);
        }
        // [P3C E1 豁免]
        System.out.println("DONE delivered=" + delivered + "/" + expected
                + " elapsedMs=" + (System.currentTimeMillis() - start));
        // [P3C E1 豁免]
        System.out.flush();
        // [P3C SE-安全 豁免]
        System.exit(0);
    }

    /**
     * 完成 WebSocket 握手。
     *
     * @param port 端口
     * @return 已握手的连接
     * @throws Exception 握手失败
     */
    private static Socket connect(int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 15000);
        socket.setSoTimeout(60000);
        socket.setTcpNoDelay(true);
        String key = Base64.getEncoder().encodeToString(new byte[16]);
        String request = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key
                + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int p1 = -1;
        int p2 = -1;
        int p3 = -1;
        int read;
        while ((read = in.read()) >= 0) {
            head.write(read);
            if (p1 == '\r' && p2 == '\n' && p3 == '\r' && read == '\n') {
                break;
            }
            p1 = p2;
            p2 = p3;
            p3 = read;
        }
        String response = head.toString(StandardCharsets.US_ASCII);
        if (!response.startsWith("HTTP/1.1 101")) {
            socket.close();
            throw new IllegalStateException("握手失败: " + response.substring(0, Math.min(80, response.length())));
        }
        return socket;
    }

    /**
     * 读一帧服务端发来的（无掩码）帧。
     *
     * @param in 输入流
     * @return 负载；连接关闭时返回 null
     * @throws Exception 读取失败
     */
    private static byte[] readServerFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            return null;
        }
        long length = b1 & 0x7F;
        if (length == 126) {
            length = ((long) in.read() << 8) | in.read();
        } else if (length == 127) {
            length = 0L;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | in.read();
            }
        }
        byte[] payload = new byte[(int) length];
        int offset = 0;
        while (offset < payload.length) {
            int read = in.read(payload, offset, payload.length - offset);
            if (read < 0) {
                return null;
            }
            offset += read;
        }
        return payload;
    }
}
