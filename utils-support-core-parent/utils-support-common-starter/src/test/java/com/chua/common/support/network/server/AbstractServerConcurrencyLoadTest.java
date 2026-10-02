package com.chua.common.support.network.server;

import com.chua.common.support.network.PortBindRetry;
import com.chua.common.support.network.ProtocolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端的并发、负载与稳定性。
 *
 * <p>被测对象是 {@link AbstractServer} 本身，而不是某个具体实现 —— 服务管理的每个节点
 * 最终都收敛到 {@code start()} / {@code stop()}，端口绑定重试也在这里。协议刻意做成
 * 「发一行、回一行」，这样断言的是<b>回显内容是否正确</b>，而不是「有没有报错」：
 * 只看错误率的压测在服务端把请求悄悄丢掉时同样会全绿。</p>
 *
 * <p>用真实的 {@link ServerSocket} 与真实线程，不用 mock：并发缺陷（重复 bind、
 * 端口泄漏、连接被吞）恰恰只在真实套接字上才暴露。</p>
 *
 * <p>负载规模按「能在开发机上稳定跑完」选，不追求压满 CPU。目的是抓结构性缺陷
 * （竞态、泄漏、状态不一致），不是测吞吐上限 —— 后者依赖硬件，结论不可迁移。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class AbstractServerConcurrencyLoadTest {

    /**
     * 并发压测用的回显服务。
     *
     * <p>刻意继承真实的 {@link AbstractServer}，这样 {@code start()} / {@code stop()} /
     * 端口绑定重试走的都是生产代码，而不是测试另写一套。</p>
     */
    private static final class LoadEchoServer extends AbstractServer {

        private final AtomicInteger bindCount = new AtomicInteger();

        private final AtomicInteger activeConnections = new AtomicInteger();

        private final AtomicInteger peakConnections = new AtomicInteger();

        private final AtomicInteger servedLines = new AtomicInteger();

        private final AtomicInteger handlerFailures = new AtomicInteger();

        private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

        private volatile ServerSocket serverSocket;

        private volatile ExecutorService acceptExecutor;

        LoadEchoServer(ServerSetting setting) {
            super(setting);
        }

        @Override
        public ProtocolType getProtocolType() {
            return ProtocolType.TCP;
        }

        @Override
        protected void doStart() {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(getSetting().getHost(), getSetting().getPort()), 128);
                serverSocket = socket;
                bindCount.incrementAndGet();
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                acceptExecutor = executor;
                executor.submit(this::acceptLoop);
            } catch (IOException e) {
                throw new IllegalStateException("回显服务 bind 失败", e);
            }
        }

        @Override
        protected void doStop() {
            ServerSocket socket = serverSocket;
            if (socket != null && !socket.isClosed()) {
                // doStop() 没有 throws 声明，收尾阶段的关闭异常只能就地消化：
                // 此时服务器已在停，关闭失败不影响「端口是否释放」这个判定
                // （下面的端口探测会独立验证）。
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // 见上：关闭失败由端口探测独立兜底
                }
            }
            openSockets.forEach(LoadEchoServer::closeQuietly);
            openSockets.clear();
            ExecutorService executor = acceptExecutor;
            if (executor != null) {
                executor.shutdownNow();
            }
        }

        private void acceptLoop() {
            ServerSocket socket = serverSocket;
            while (socket != null && !socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    openSockets.add(client);
                    ExecutorService executor = acceptExecutor;
                    if (executor == null) {
                        closeQuietly(client);
                        continue;
                    }
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (socket.isClosed()) {
                        return;
                    }
                    handlerFailures.incrementAndGet();
                }
            }
        }

        private void handleClient(Socket client) {
            int current = activeConnections.incrementAndGet();
            peakConnections.accumulateAndGet(current, Math::max);
            try (Socket socket = client;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                OutputStream out = socket.getOutputStream();
                String line;
                while ((line = reader.readLine()) != null) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    servedLines.incrementAndGet();
                }
            } catch (IOException e) {
                // 客户端被 stop() 主动关闭时会走到这里，不算故障。
                // 注意只能用 client（方法参数）：try-with-resources 声明的资源变量
                // 只在 try 块内可见，catch 里引用 socket 会编译不过。
                if (!client.isClosed()) {
                    handlerFailures.incrementAndGet();
                }
            } finally {
                activeConnections.decrementAndGet();
                openSockets.remove(client);
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 收尾阶段的关闭失败不影响判定
            }
        }
    }

    /**
     * 等待指定毫秒。
     *
     * @param millis 毫秒
     * @throws InterruptedException 等待被中断
     */
    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /**
     * 等待端口上的监听者消失。
 *
     * <p><b>为什么必须沉降，不能查一次就下结论</b>：实测本用例原本 50% 抖动
     * （6 次里 3 次失败，失败点随机落在第 1/25/55 轮）。诊断数据：</p>
 *
 * <pre>
 * round=1 bindable=true listenNow=true listen@200ms=false listen@1300ms=false
 *        serverRunning=false serverSocketClosed=true
 * </pre>
 *
 * <p>即：{@code stop()} 返回后端口<b>已经可以重新 bind</b>、serverSocket 已关闭、
 * {@code isRunning()} 为 false —— 没有任何泄漏迹象；唯独紧接着的那一次 connect
 * 成功了，随后 200ms 内就消失。这是内核 accept 队列里尚未完成握手的连接被 close()
 * 顺手带过，属于操作系统层面的瞬时行为。</p>
 *
 * <p>泄漏点随机分布而不是单调递增，也是佐证：真泄漏会累积增长，随机点说明是竞态。
 * 所以查一次 connect 就有假阳性，会把一个没有缺陷的实现判成泄漏。</p>
 *
 * <p>反过来，真正的 socket 泄漏（LISTEN socket 没被关掉）会持续接受连接，
 * 永远不会自己消失，因此「等它消失」这个判据不会漏掉真泄漏。</p>
 *
 * @param port    端口
 * @param budgetMs 最多等待多少毫秒
 * @return 在预算内消失返回 true
 * @throws InterruptedException 等待被中断
 */
    private static boolean waitListenerGone(int port, long budgetMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + budgetMs;
        do {
            if (!hasListener(port)) {
                return true;
            }
            sleep(50L);
        } while (System.currentTimeMillis() < deadline);
        return !hasListener(port);
    }

    /**
     * 取一个当前空闲的端口。
     *
     * @return 空闲端口
     * @throws IOException 套接字操作失败
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /**
     * 指定端口当前是否已被占用。
     *
     * @param port 端口
     * @return 已被占用返回 true
     */
    private static boolean isBound(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * 指定端口上是否真的有监听者。
 *
 * <p>刻意与 {@link #isBound(int)} 分开：那个方法的判据是「能不能 bind 上去」，
 * 而 TIME_WAIT 状态下的端口同样 bind 不上去，于是**它把残留的 TIME_WAIT 也算成
 * 占用**。本测试每轮都会做探测 bind/close，几十轮下来足以自己把端口搅进
 * TIME_WAIT，然后给出一个「存在 socket 泄漏」的假结论。</p>
 *
 * <p>「有没有东西在监听」的独立判据是能否 connect 成功 —— TIME_WAIT 里的套接字
 * 不接受连接，只有真正 LISTEN 的才会。泄漏判定必须用这个。</p>
 *
 * @param port 端口
 * @return 有监听者返回 true
 */
    private static boolean hasListener(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 构造指向本机指定端口的设置。
     *
     * @param port 端口
     * @return 服务端设置
     */
    private static ServerSetting setting(int port) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        return setting;
    }

    /**
     * 用一个客户端连上回显服务，发一行收一行。
     *
     * @param port 端口
     * @param line 要发送的内容
     * @return 服务端回显的内容
     * @throws Exception 连接或读写失败
     */
    private static String roundTrip(int port, String line) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return reader.readLine();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("并发 start 同一个实例：只能绑定一次，且不得有线程拿到异常")
    void concurrentStartBindsExactlyOnce() throws Exception {
        int port = freePort();
        LoadEchoServer server = new LoadEchoServer(setting(port));
        int threads = 32;

        try {
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                Thread worker = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        server.start();
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                }, "concurrent-start-" + i);
                workers.add(worker);
                worker.start();
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "工作线程未全部就绪");
            go.countDown();
            for (Thread worker : workers) {
                worker.join(30_000);
            }

            assertEquals(0, failures.size(),
                    "并发 start 不该有线程失败，实际首个异常=" + (failures.isEmpty() ? "" : failures.get(0)));
            assertEquals(1, server.bindCount.get(),
                    "同一个实例被 32 个线程同时 start，只应真正 bind 一次；"
                            + "多次 bind 意味着幂等判断在并发下失效");
            assertTrue(server.isRunning());
            assertTrue(isBound(port), "端口 " + port + " 应处于监听状态");
            assertEquals("hello", roundTrip(port, "hello"), "bind 成功后必须能正常回显");
        } finally {
            server.stop();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("两个实例抢同一端口：恰好一个成功，败者必须干净失败而不是假成功")
    void exactlyOneInstanceWinsThePort() throws Exception {
        int port = freePort();
        LoadEchoServer first = new LoadEchoServer(setting(port));
        LoadEchoServer second = new LoadEchoServer(setting(port));
        // 不重试，让抢不到的一方立刻暴露；这里要验的是「互斥」而不是「自愈」
        first.setBindRetryPolicy(PortBindRetry.Policy.noRetry());
        second.setBindRetryPolicy(PortBindRetry.Policy.noRetry());

        try {
            first.start();
            assertTrue(first.isRunning());
            assertEquals(1, first.bindCount.get());

            AtomicReference<Throwable> loserFailure = new AtomicReference<>();
            try {
                second.start();
            } catch (Throwable e) {
                loserFailure.set(e);
            }

            assertNotNull(loserFailure.get(),
                    "端口已被占用时第二个实例必须失败；报成功就是「节点状态 RUNNING "
                            + "但实际没监听」那类假绿灯");
            assertFalse(second.isRunning(), "绑定失败的实例不得处于运行态");
            assertTrue(PortBindRetry.isBindConflict(loserFailure.get()),
                    "失败原因必须能追溯到 BindException，实际=" + loserFailure.get());
            assertEquals(0, second.servedLines.get(), "失败实例不应服务过任何请求");
            assertEquals("still-alive", roundTrip(port, "still-alive"),
                    "胜出的实例不应被败者影响");
        } finally {
            first.stop();
            second.stop();
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("并发 start/stop 交错后端口必须释放，不留泄漏")
    void interleavedStartStopDoesNotLeakPorts() throws Exception {
        int port = freePort();
        int iterations = 60;
        int leakedAt = -1;
        String diag = "";

        // 必须持有全部实例引用：否则每轮的 server 立刻不可达，GC 的 cleaner 会把
// ServerSocket 顺手关掉 —— 于是「即使 doStop() 忘了 close，端口也会因为被 GC 而空闲」，
// 泄漏检测就成了摆设。实测踩过：把 doStop() 里的 close 短路掉后测试仍然通过，
// 原因就是 GC 兜住了。持有引用后 GC 无法掩盖真实泄漏。
        List<LoadEchoServer> servers = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            LoadEchoServer server = new LoadEchoServer(setting(port));
            servers.add(server);
            server.start();
            assertTrue(isBound(port), "第 " + i + " 轮：启动后端口 " + port + " 应被监听");
            server.stop();
            // 沉降后再判：立刻 connect 会命中内核 accept 队列里未完成握手的连接，
            // 那是 close() 的瞬时副作用，不是泄漏。真泄漏的 LISTEN 永不消失。
            if (!waitListenerGone(port, 3_000L)) {
                leakedAt = i;
                diag = " round=" + i
                        + " bindable=" + (!isBound(port))
                        + " 3s 后仍在监听"
                        + " serverRunning=" + server.isRunning()
                        + " serverSocketClosed=" + (server.serverSocket == null || server.serverSocket.isClosed());
                break;
            }
        }
        assertEquals(-1, leakedAt,
                "第 " + leakedAt + " 轮 start/stop 之后端口上持续存在监听者（已等 3s），"
                        + "说明 socket 泄漏。" + diag);
    }

    @Test
    @Timeout(180)
    @DisplayName("并发连接 + 持续流量：回显必须逐条正确，服务端不得积累错误或残留连接")
    void sustainedConcurrentTrafficIsServedCorrectly() throws Exception {
        int port = freePort();
        LoadEchoServer server = new LoadEchoServer(setting(port));
        int clients = 40;
        int linesPerClient = 25;
        int totalLines = clients * linesPerClient;

        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            server.start();

            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>(clients);
            for (int c = 0; c < clients; c++) {
                final int clientId = c;
                futures.add(pool.submit(() -> {
                    go.await();
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
                        socket.setSoTimeout(60_000);
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        OutputStream out = socket.getOutputStream();
                        for (int i = 0; i < linesPerClient; i++) {
                            String payload = "c" + clientId + "-l" + i;
                            out.write((payload + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            String echoed = reader.readLine();
                            if (!payload.equals(echoed)) {
                                return "期望 " + payload + " 实际 " + echoed;
                            }
                        }
                    }
                    return null;
                }));
            }
            go.countDown();

            long began = System.nanoTime();
            List<String> mismatches = new ArrayList<>();
            for (Future<String> future : futures) {
                String mismatch = future.get(150, TimeUnit.SECONDS);
                if (mismatch != null) {
                    mismatches.add(mismatch);
                }
            }
            long elapsedMs = (System.nanoTime() - began) / 1_000_000L;

            // 注意：断言消息是**急切求值**的，写 mismatches.get(0) 会在列表为空时抛
            // IndexOutOfBoundsException —— 那样这条断言根本走不到失败分支，
            // 压测「通过」还是「挂掉」全成了消息的函数。消息必须自身是安全的。
            String firstMismatch = mismatches.isEmpty() ? "(无)" : mismatches.get(0);
            assertTrue(mismatches.isEmpty(),
                    "有 " + mismatches.size() + " 条回显不匹配，首条：" + firstMismatch
                            + "；只统计错误率的压测在这里会全绿 —— 服务端把请求换错了内容也一样通过");

            assertEquals(totalLines, server.servedLines.get(),
                    "服务端统计的服务行数应与客户端发送数完全一致");
            assertEquals(0, server.handlerFailures.get(),
                    "服务端不应出现处理异常，实际 " + server.handlerFailures.get());
            assertTrue(server.peakConnections.get() > 1,
                    "峰值并发连接应为 " + clients + " 量级，实际只有 " + server.peakConnections.get()
                            + "；说明并发被串行化了，这份压测等于没压");

            // 压测期间服务必须一直可用：中途取样一次，证明不是「压完就好了」
            assertEquals("mid-flight-probe", roundTrip(port, "mid-flight-probe"),
                    "压测进行中服务必须仍然可用");

            System.out.printf("  [load] %d 并发 x %d 行 = %d 行, %d ms, 峰值连接 %d, 吞吐 %.0f 行/s%n",
                    clients, linesPerClient, totalLines, elapsedMs,
                    server.peakConnections.get(), totalLines * 1000.0 / Math.max(1L, elapsedMs));
        } finally {
            pool.shutdownNow();
            server.stop();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("压测结束后无残留连接，服务仍可正常重启并继续服务")
    void noResidualConnectionsAndRestartable() throws Exception {
        int port = freePort();
        LoadEchoServer server = new LoadEchoServer(setting(port));
        int clients = 20;

        try {
            server.start();
            CountDownLatch done = new CountDownLatch(clients);
            List<Thread> workers = new ArrayList<>(clients);
            for (int i = 0; i < clients; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        roundTrip(port, "ping");
                    } catch (Exception ignored) {
                        // 客户端异常在「无残留」这一断言里不关心，见 handlerFailures
                    } finally {
                        done.countDown();
                    }
                }, "residual-" + i);
                workers.add(worker);
                worker.start();
            }
            assertTrue(done.await(90, TimeUnit.SECONDS), "客户端未全部结束");
            for (Thread worker : workers) {
                worker.join(10_000);
            }

            // 虚拟线程退出可能有短暂延迟，等待活跃连接数归零，但设上限避免挂死
            long deadline = System.currentTimeMillis() + 20_000;
            while (server.activeConnections.get() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertEquals(0, server.activeConnections.get(),
                    "客户端全部结束后服务端仍有 " + server.activeConnections.get() + " 个活跃连接，说明连接没被回收");

            server.stop();
            // 同样是沉降后判定：stop() 之后立刻 connect 会命中内核 accept 队列里
            // 尚未完成握手的连接，实测本用例原本也会偶发失败（8 次里 1 次）
            assertTrue(waitListenerGone(port, 3_000L), "停止后端口上不应再有监听者（已等 3s）");

            server.start();
            assertEquals(2, server.bindCount.get(), "重启应再次真正 bind");
            assertEquals("after-restart", roundTrip(port, "after-restart"), "重启后必须能继续服务");
        } finally {
            server.stop();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("端口被占用时靠退避重试自愈：占用方退出后服务自动接上")
    void recoversFromOccupiedPortUnderLoad() throws Exception {
        int port = freePort();
        LoadEchoServer server = new LoadEchoServer(setting(port));
        server.setBindRetryPolicy(new PortBindRetry.Policy(40, 100L, 200L, 30_000L));

        try (ServerSocket occupant = new ServerSocket()) {
            occupant.setReuseAddress(true);
            occupant.bind(new InetSocketAddress("127.0.0.1", port));

            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    occupant.close();
                } catch (Exception ignored) {
                    // 释放失败会让下面断言超时，但不该被静默吞掉后当作通过
                }
            }, "occupant-releaser");
            releaser.start();

            long began = System.nanoTime();
            server.start();
            long elapsedMs = (System.nanoTime() - began) / 1_000_000L;
            releaser.join(30_000);

            assertTrue(server.isRunning(), "占用方退出后应自动绑定成功");
            assertEquals(1, server.bindCount.get(), "自愈过程只应真正 bind 一次");
            assertTrue(elapsedMs >= 1_500L,
                    "应当等过占用期再绑定，实测只用了 " + elapsedMs + "ms，说明没真的经历竞争");
            assertEquals("recovered", roundTrip(port, "recovered"), "自愈后必须能正常服务");
        } finally {
            server.stop();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("未启动时不得对外提供服务（负载测试自身的对照组）")
    void stoppedServerServesNothing() throws Exception {
        int port = freePort();
        LoadEchoServer server = new LoadEchoServer(setting(port));

        assertFalse(server.isRunning());
        assertEquals(0, server.bindCount.get(), "未 start 不该发生 bind");
        assertFalse(isBound(port), "未 start 时端口不应被占用");

        server.stop();
        assertFalse(server.isRunning(), "对未启动实例重复 stop 必须安全");
    }
}