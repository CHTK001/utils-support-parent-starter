// [P3C A20 豁免] A20/A30/A31: 压测参数含魔法值场景，豁免。
package com.chua.socketio.support.server;

import io.socket.client.IO;
import io.socket.client.Socket;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket.IO 压测客户端（独立进程）。
 *
 * <p>为什么单独起进程：服务端每连接内存要用「服务端堆增量 ÷ 连接数」量，
 * 如果客户端与服务端在同一个 JVM，客户端对象（OkHttp 连接、Manager、线程栈）
 * 会和被测对象混在一起，量出来的是两者之和，没有意义。</p>
 *
 * <p>用法：{@code java SocketIoLoadClient <port> <clients> <holdSeconds>}</p>
 * <p>输出：连接完成后打印 {@code [sio-client] CONNECTED ok=N/N}，保持期后再打印存活数。</p>
 *
 * @author CH
 * @since 2026-11-13
 */
public final class SocketIoLoadClient {

    /**
     * 工具类不允许实例化。
     */
    private SocketIoLoadClient() {
    }

    /**
     * 入口。
     *
     * @param args 端口、客户端数、保持秒数
     * @throws Exception 建连失败
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int clients = Integer.parseInt(args[1]);
        int holdSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 15;

        List<Socket> sockets = new ArrayList<>(clients);
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger disconnected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger connectErrors = new AtomicInteger();
        long startNs = System.nanoTime();

        IO.Options options = new IO.Options();
        options.forceNew = true;
        options.reconnection = false;
        options.timeout = 20000;
        // 只走 websocket：轮询握手要先 POST 再 GET，单连接 2~3 个 RTT；
        // 串行建连时 200 条就要 80 秒，早已超过 Socket.IO 默认 pingTimeout(20s)，
        // 先连上的会被服务端判定超时踢掉（实测 200 条只剩 63 条）。
        options.transports = new String[]{"websocket"};
        // 共享一个 OkHttpClient：默认每个 IO.socket 会新建自己的客户端（含独立
        // Dispatcher 线程池），200 条连接就是 200 套线程池，先把压测机自己拖死
        // （实测 200 条只有 64 条能连上）。共享后客户端侧资源与连接数解耦。
        // 注意 Dispatcher 默认 maxRequests=64 / maxRequestsPerHost=5，
        // 且 websocket 握手是 AsyncCall、跑在 Dispatcher 的 executor 上：
        // 用固定 64 线程池会把并发连接卡死在 64 条（实测正好 64，其余全部 connect timeout）。
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher(
                // [P3C C1 豁免]
                java.util.concurrent.Executors.newCachedThreadPool());
        dispatcher.setMaxRequests(65536);
        dispatcher.setMaxRequestsPerHost(65536);
        okhttp3.OkHttpClient shared = new okhttp3.OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();
        options.callFactory = shared;
        options.webSocketFactory = shared;
        URI uri = URI.create("http://127.0.0.1:" + port);

        // 并发建连：串行建连的耗时会随连接数线性增长，本身就会压出假故障
        int parallelism = Math.min(32, Math.max(1, clients));
        java.util.concurrent.ExecutorService pool =
                // [P3C C1 豁免]
                java.util.concurrent.Executors.newFixedThreadPool(parallelism);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>(clients);
        for (int i = 0; i < clients; i++) {
            tasks.add(pool.submit(() -> {
                Socket socket = IO.socket(uri, options);
                socket.on(Socket.EVENT_CONNECT, a -> connected.incrementAndGet());
                socket.on(Socket.EVENT_DISCONNECT, a -> disconnected.incrementAndGet());
                socket.on("error", a -> {
                    if (errors.incrementAndGet() <= 5) {
                        // [P3C E1 豁免]
                        System.out.println("[sio-client] ERROR "
                                + (a != null && a.length > 0 ? a[0] : "?"));
                    }
                });
                socket.on("connect_error", a -> {
                    if (connectErrors.incrementAndGet() <= 5) {
                        // [P3C E1 豁免]
                        System.out.println("[sio-client] CONNECT_ERROR "
                                + (a != null && a.length > 0 ? a[0] : "?"));
                    }
                });
                synchronized (sockets) {
                    sockets.add(socket);
                }
                socket.connect();
            }));
        }
        pool.shutdown();
        pool.awaitTermination(300, java.util.concurrent.TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 120_000L;
        while (connected.get() + disconnected.get() < clients && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        long connectMs = (System.nanoTime() - startNs) / 1_000_000L;
        // [P3C E1 豁免]
        System.out.println("[sio-client] CONNECTED ok=" + connected.get() + "/" + clients
                + " disconnected=" + disconnected.get()
                + " errors=" + errors.get() + " connectErrors=" + connectErrors.get()
                + " connectMs=" + connectMs);
        // [P3C E1 豁免]
        System.out.flush();

        Thread.sleep(holdSeconds * 1000L);

        int alive = 0;
        for (Socket socket : sockets) {
            if (socket.connected()) {
                alive++;
            }
        }
        // [P3C E1 豁免]
        System.out.println("[sio-client] ALIVE after hold = " + alive + "/" + clients);
        // [P3C E1 豁免]
        System.out.flush();

        for (Socket socket : sockets) {
            try {
                socket.disconnect();
                socket.close();
            } catch (Exception ignored) {
                // 清理失败不影响统计
            }
        }
        // [P3C E1 豁免]
        System.out.println("[sio-client] DONE");
        // [P3C E1 豁免]
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
