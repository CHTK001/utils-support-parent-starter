// [P3C A20 豁免] A20/A30/A31: 压测参数与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServerListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * WebSocket 消息吞吐与广播扇出的测量。
 *
 * <p><b>默认跳过</b>：加 {@code -Dbench.ws=true} 才跑。</p>
 *
 * <p>两个场景，回答两个不同问题：</p>
 * <ol>
 *   <li><b>elapsed 回环吞吐</b>（{@link #echoThroughput}）：单连接内"发一条收一条"，
 *       衡量服务端处理 + 定向投递链路能撑多少条/秒。</li>
 *   <li><b>广播扇出</b>（{@link #broadcastFanout}）：「一次 publish 送达 N 条连接」
 *       要多久，衡量百万连接场景真正吃紧的那条路径。</li>
 * </ol>
 *
 * <p>同机自压（客户端与服务端同 JVM），数字是<b>下限</b>，只能横向比较。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@EnabledIfSystemProperty(named = "bench.ws", matches = "true")
class WebSocketThroughputTest {

    /**
     * 回环场景的并发连接数。
     */
    private static final int ECHO_CONCURRENCY = 64;

    /**
     * 回环场景预热时长（毫秒）。
     */
    private static final int WARMUP_MS = 2_000;

    /**
     * 回环场景测量时长（毫秒）。
     */
    private static final int ECHO_DURATION_MS = 8_000;

    /**
     * 广播场景的连接数（-Dbench.ws.clients 覆盖）。
     */
    private static int fanoutClients() {
        return Integer.getInteger("bench.ws.clients", 500);
    }

    /**
     * 广播场景发布帧数（-Dbench.ws.frames 覆盖）。
     */
    private static int fanoutFrames() {
        return Integer.getInteger("bench.ws.frames", 30);
    }

    /**
     * 分批建连的批大小：Windows 的 listen() 有效 accept 队列约 200（SOMAXCONN 上限），
     * 一次并发过多会让超出的 SYN 被内核丢弃、靠 TCP 重传慢慢补。
     */
    private static final int CONNECT_CHUNK = 100;

    /**
     * 批次之间的间隔（毫秒），留给内核 accept 队列排空。
     */
    private static final long CONNECT_GAP_MS = 250L;

    /**
     * 服务端配置。
     *
     * @return 配置
     */
    private static ServerSetting setting() {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 取空闲端口。
     *
     * @return 端口
     * @throws IOException 失败
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 完成 WebSocket 握手。
     *
     * @param port 端口
     * @return 已握手的连接
     * @throws Exception 握手失败
     */
    private static Socket wsConnect(int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
        socket.setSoTimeout(30000);
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
            throw new IllegalStateException("握手失败: " + response);
        }
        return socket;
    }

    /**
     * 发送带掩码的文本帧。
     *
     * @param out  输出流
     * @param text 文本
     * @throws IOException 写失败
     */
    private static void sendTextFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(payload.length + 14);
        buffer.write(0x81);
        if (payload.length < 126) {
            buffer.write(0x80 | payload.length);
        } else {
            buffer.write(0x80 | 126);
            buffer.write((payload.length >> 8) & 0xFF);
            buffer.write(payload.length & 0xFF);
        }
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        buffer.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            buffer.write(payload[i] ^ mask[i % 4]);
        }
        out.write(buffer.toByteArray());
        out.flush();
    }

    /**
     * 读一帧服务端发来的（无掩码）文本帧。
     *
     * @param in 输入流
     * @return 负载；连接关闭时返回 null
     * @throws IOException 读取失败
     */
    private static byte[] readServerFrame(InputStream in) throws IOException {
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

    /**
     * 回环吞吐：每连接发一条收一条，服务端经 listener 立即回给同一条连接。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(180)
    @DisplayName("吞吐：WebSocket 回环消息（条/秒）")
    void echoThroughput() throws Exception {
        ServerSetting setting = setting();
        setting.setPort(freePort());
        WebSocketSyncServer server = new WebSocketSyncServer(setting);
        server.addListener(new SyncServerListener() {
            @Override
            public void onMessage(String clientId, String topic, Object message) {
                server.send(clientId, topic, message);
            }
        });
        server.start();
        try {
            int port = server.getPort();
            String payload = "x".repeat(64);
            Thread[] threads = new Thread[ECHO_CONCURRENCY];
            long[] counts = new long[ECHO_CONCURRENCY];
            int[] errors = new int[ECHO_CONCURRENCY];

            for (int i = 0; i < ECHO_CONCURRENCY; i++) {
                final int index = i;
                threads[index] = Thread.ofVirtual().start(() -> {
                    try (Socket socket = wsConnect(port)) {
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        // 预热
                        long warmDeadline = System.currentTimeMillis() + WARMUP_MS;
                        while (System.currentTimeMillis() < warmDeadline) {
                            sendTextFrame(out, "t:" + payload);
                            if (readServerFrame(in) == null) {
                                return;
                            }
                        }
                        long deadline = System.currentTimeMillis() + ECHO_DURATION_MS;
                        long done = 0;
                        while (System.currentTimeMillis() < deadline) {
                            sendTextFrame(out, "t:" + payload);
                            if (readServerFrame(in) == null) {
                                break;
                            }
                            done++;
                        }
                        counts[index] = done;
                    } catch (Exception e) {
                        errors[index]++;
                    }
                });
            }
            for (Thread thread : threads) {
                thread.join(WARMUP_MS + ECHO_DURATION_MS + 30000L);
            }
            long total = 0;
            int error = 0;
            for (long value : counts) {
                total += value;
            }
            for (int value : errors) {
                error += value;
            }
            // [P3C E1 豁免]
            System.out.println("BENCH|ws-echo|msgs=" + total
                    + "|qps=" + String.format(java.util.Locale.ROOT, "%.0f", total * 1000.0 / ECHO_DURATION_MS)
                    + "|conns=" + ECHO_CONCURRENCY + "|err=" + error);
            // [P3C E1 豁免]
            System.out.flush();
        } finally {
            server.stop();
        }
    }

    /**
     * 读完子进程输出直到出现指定前缀的行。
     *
     * @param reader 子进程输出
     * @param prefix 行前缀
     * @param timeoutMs 超时
     * @return 命中行；超时返回 null
     * @throws Exception 读取失败
     */
    private static String waitForLine(java.io.BufferedReader reader, String prefix, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String line;
        while (System.currentTimeMillis() < deadline) {
            line = reader.readLine();
            if (line == null) {
                return null;
            }
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    /**
     * 广播扇出（客户端在独立进程）：排除同 JVM 里客户端被服务端抢 CPU 的干扰。
     *
     * <p>同进程实测 2 万条时，服务端 accepted/hsOk 都是 20000，但客户端线程集体
     * 在"读 101"上超时——压测端被饿死。这里把客户端拆到子进程再测同一场景。</p>
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(900)
    @DisplayName("吞吐：广播扇出（独立进程客户端）")
    void broadcastFanoutExternal() throws Exception {
        final int clientCount = fanoutClients();
        final int frameCount = fanoutFrames();
        // 单进程超过 ~2 万条时客户端自身会吃紧；大连接数用多进程分摊
        final int processes = Math.max(1, Integer.getInteger("bench.ws.processes", 1));
        ServerSetting setting = setting();
        setting.setPort(freePort());
        setting.setMaxConnections(clientCount + 1000);
        WebSocketSyncServer server = new WebSocketSyncServer(setting);
        server.start();
        java.util.List<Process> children = new java.util.ArrayList<>();
        try {
            String testClasses = new java.io.File("target/test-classes").getAbsolutePath();
            String mainClasses = new java.io.File("target/classes").getAbsolutePath();
            String classpath = testClasses + java.io.File.pathSeparator
                    + mainClasses + java.io.File.pathSeparator
                    + System.getProperty("java.class.path");
            java.util.List<java.io.BufferedReader> readers = new java.util.ArrayList<>();
            int remaining = clientCount;
            for (int p = 0; p < processes && remaining > 0; p++) {
                int share = Math.min(remaining, (clientCount + processes - 1) / processes);
                remaining -= share;
                ProcessBuilder builder = new ProcessBuilder(
                        System.getProperty("java.home") + java.io.File.separator + "bin"
                                + java.io.File.separator + "java",
                        "-Xmx2g",
                        "-cp", classpath,
                        WebSocketFanoutClient.class.getName(),
                        String.valueOf(server.getPort()),
                        String.valueOf(share),
                        String.valueOf(frameCount),
                        "100", "250");
                builder.redirectErrorStream(true);
                Process child = builder.start();
                children.add(child);
                readers.add(new java.io.BufferedReader(
                        new java.io.InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8)));
            }

            long readyStart = System.currentTimeMillis();
            int connectedTotal = 0;
            for (java.io.BufferedReader reader : readers) {
                String ready = waitForLine(reader, "READY", 300_000L);
                // [P3C E1 豁免]
                System.out.println("BENCH|ws-fanout-child|" + ready);
                if (ready == null) {
                    return;
                }
                connectedTotal += parseCount(ready, "connected=");
            }
            // [P3C E1 豁免]
            System.out.println("BENCH|ws-fanout-ready|processes=" + processes
                    + "|connected=" + connectedTotal + "/" + clientCount
                    + "|waitMs=" + (System.currentTimeMillis() - readyStart));
            // [P3C E1 豁免]
            System.out.flush();

            long start = System.nanoTime();
            String payload = "x".repeat(64);
            for (int i = 0; i < frameCount; i++) {
                server.publish("t", payload);
            }
            long publishMs = (System.nanoTime() - start) / 1_000_000L;

            long deliveredTotal = 0;
            for (java.io.BufferedReader reader : readers) {
                String done = waitForLine(reader, "DONE", 300_000L);
                deliveredTotal += parseCount(done, "delivered=");
            }
            long totalMs = (System.nanoTime() - start) / 1_000_000L;
            long expected = (long) clientCount * frameCount;
            // [P3C E1 豁免]
            System.out.println("BENCH|ws-fanout-external|clients=" + clientCount
                    + "|processes=" + processes
                    + "|frames=" + frameCount
                    + "|delivered=" + deliveredTotal + "/" + expected
                    + "|publishMs=" + publishMs
                    + "|totalMs=" + totalMs
                    + "|framesPerSec=" + String.format(java.util.Locale.ROOT, "%.0f",
                    deliveredTotal * 1000.0 / Math.max(1L, totalMs)));
            // [P3C E1 豁免]
            System.out.flush();
        } finally {
            for (Process child : children) {
                child.destroy();
            }
            server.stop();
        }
    }

    /**
     * 从子进程输出行里取 {@code key} 后的数字。
     *
     * @param line 行内容，可为 null
     * @param key  键（含等号）
     * @return 数值；取不到返回 0
     */
    private static long parseCount(String line, String key) {
        if (line == null) {
            return 0;
        }
        int at = line.indexOf(key);
        if (at < 0) {
            return 0;
        }
        int i = at + key.length();
        int j = i;
        while (j < line.length() && Character.isDigit(line.charAt(j))) {
            j++;
        }
        return j > i ? Long.parseLong(line.substring(i, j)) : 0;
    }

    /**
     * 广播扇出：发布 N 帧给 C 条连接，测全部送达耗时（客户端与服务端同 JVM）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(600)
    @DisplayName("吞吐：WebSocket 广播扇出（帧/秒 + 送达耗时）")
    void broadcastFanout() throws Exception {
        ServerSetting setting = setting();
        setting.setPort(freePort());
        // auto=false 时 maxConnections 走默认值（实测约 200），广播场景的连接数必须显式放开，
        // 否则超出的连接会在握手阶段被连接预算直接关掉，只统计到前 200 条。
        setting.setMaxConnections(fanoutClients() + 1000);
        WebSocketSyncServer server = new WebSocketSyncServer(setting);
        server.start();
        try {
            final int clientCount = fanoutClients();
            final int frameCount = fanoutFrames();
            int port = server.getPort();
            AtomicIntegerArray received = new AtomicIntegerArray(clientCount);
            AtomicBoolean running = new AtomicBoolean(true);
            Thread[] clients = new Thread[clientCount];
            AtomicInteger connectFailures = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> firstError =
                    new java.util.concurrent.atomic.AtomicReference<>();
            int connected = 0;

            for (int i = 0; i < clientCount; i++) {
                final int index = i;
                clients[index] = Thread.ofVirtual().start(() -> {
                    try (Socket socket = wsConnect(port)) {
                        InputStream in = socket.getInputStream();
                        while (running.get()) {
                            byte[] frame = readServerFrame(in);
                            if (frame == null) {
                                return;
                            }
                            received.incrementAndGet(index);
                        }
                    } catch (Exception e) {
                        // 记录首个失败原因：大连接数下"全部失败"必须能定位，不能静默
                        connectFailures.incrementAndGet();
                        firstError.compareAndSet(null,
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
                // 分批建连：Windows 的 listen() 有效 accept 队列约 200（SOMAXCONN 上限），
                // 一次性并发过多会让超出的 SYN 被内核丢弃、靠 TCP 重传补（实测 500 并发 10 秒只进 203 条）。
                // HttpBench 里 CLUSTER=100 + GAP_MS=300 也是同一个原因。
                if ((i + 1) % CONNECT_CHUNK == 0) {
                    Thread.sleep(CONNECT_GAP_MS);
                }
            }
            // 等全部连接完成握手（大连接数下要放宽，2 万条约 50 秒）
            long readyStart = System.currentTimeMillis();
            long deadline = readyStart + 180000L;
            while (System.currentTimeMillis() < deadline
                    && server.getConnectedClients().size() < clientCount) {
                // 全部客户端都失败时不必再空等到超时，直接出诊断
                if (connectFailures.get() >= clientCount) {
                    break;
                }
                Thread.sleep(50L);
            }
            connected = server.getConnectedClients().size();
            // [P3C E1 豁免]
            System.out.println("BENCH|ws-fanout-ready|clients=" + connected + "/" + clientCount
                    + "|waitMs=" + (System.currentTimeMillis() - readyStart)
                    + "|connectFailures=" + connectFailures.get()
                    + "|firstError=" + firstError.get()
                    + "|accepted=" + server.getAcceptedTotal()
                    + "|hsOk=" + server.getHandshakeOkTotal()
                    + "|hsFail=" + server.getHandshakeFailTotal()
                    + "|hsErr=" + server.getLastHandshakeError());
            // [P3C E1 豁免]
            System.out.flush();
            Thread.sleep(500L);

            String payload = "x".repeat(64);
            long start = System.nanoTime();
            for (int i = 0; i < frameCount; i++) {
                server.publish("t", payload);
            }
            long publishDone = System.nanoTime();

            long expected = (long) clientCount * frameCount;
            long waitUntil = System.currentTimeMillis() + 120000L;
            long delivered = 0;
            while (System.currentTimeMillis() < waitUntil) {
                delivered = 0;
                for (int i = 0; i < clientCount; i++) {
                    delivered += received.get(i);
                }
                if (delivered >= expected) {
                    break;
                }
                Thread.sleep(50L);
            }
            long elapsedMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
            long publishMs = Math.max(1L, (publishDone - start) / 1_000_000L);
            // [P3C E1 豁免]
            System.out.println("BENCH|ws-fanout|clients=" + connected + "/" + clientCount
                    + "|frames=" + frameCount
                    + "|delivered=" + delivered + "/" + expected
                    + "|publishMs=" + publishMs
                    + "|totalMs=" + elapsedMs
                    + "|framesPerSec=" + String.format(java.util.Locale.ROOT, "%.0f",
                    delivered * 1000.0 / elapsedMs));
            // [P3C E1 豁免]
            System.out.flush();

            running.set(false);
            for (Thread client : clients) {
                client.join(5000L);
            }
        } finally {
            server.stop();
        }
    }
}
