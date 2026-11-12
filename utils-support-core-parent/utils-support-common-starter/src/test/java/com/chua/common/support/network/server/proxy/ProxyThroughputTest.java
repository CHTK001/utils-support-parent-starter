// [P3C A20 豁免] A20/A30/A31: 压测参数与断言含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * proxy 三层链路（客户端 → 代理 → 真实后端）的吞吐测量。
 *
 * <p><b>默认跳过</b>：加 {@code -Dbench.throughput=true} 才跑，避免污染常规回归。</p>
 *
 * <p><b>口径</b>：压测端与代理在同一个 JVM（同机自压），所以数字是<b>下限</b>，
 * 只能横向比较实现之间的相对差距，不能当生产能力。每个场景含 3 秒预热、10 秒测量。</p>
 *
 * <p>输出格式固定为 {@code BENCH|场景|qps=..|p50us=..|p99us=..|err=..}，便于脚本抓取。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@EnabledIfSystemProperty(named = "bench.throughput", matches = "true")
class ProxyThroughputTest {

    /**
     * 每个场景的测量时长（毫秒）。
     */
    private static final int DURATION_MS = 10_000;

    /**
     * 预热时长（毫秒）。
     */
    private static final int WARMUP_MS = 3_000;

    /**
     * 每线程环形采样容量（够统计 p99，又不会吃内存）。
     */
    private static final int SAMPLES = 8192;

    /**
     * 默认并发连接数。
     */
    private static final int CONCURRENCY = 128;

    /**
     * 单请求负载（字节）。
     */
    private static final int PAYLOAD = 1024;

    /**
     * 读取超时（毫秒）。
     */
    private static final int SO_TIMEOUT_MS = 10_000;

    /**
     * 代理监听配置。
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
     * 一次测量的汇总结果。
     */
    private static final class Result {

        /**
         * 完成请求数。
         */
        private long count;

        /**
         * 错误数。
         */
        private int errors;

        /**
         * 延迟样本（纳秒）。
         */
        private long[] latencies = new long[0];

        /**
         * 计算每秒请求数。
         *
         * @param durationMs 测量时长
         * @return qps
         */
        double qps(int durationMs) {
            return count * 1000.0 / Math.max(1, durationMs);
        }

        /**
         * 取延迟分位。
         *
         * @param ratio 分位（0~1）
         * @return 微秒
         */
        double percentile(double ratio) {
            if (latencies.length == 0) {
                return 0;
            }
            int index = (int) Math.min(latencies.length - 1L, Math.round(latencies.length * ratio));
            return latencies[index] / 1000.0;
        }

        /**
         * 排序延迟样本（分位计算前必须调用）。
         */
        void sort() {
            Arrays.sort(latencies);
        }
    }

    /**
     * 读满指定字节。
     *
     * @param in     输入流
     * @param buffer 目标
     * @param length 需要读取的字节数
     * @return 实读字节数
     * @throws IOException 读取失败
     */
    private static int readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read;
    }

    /**
     * 一条已建立连接上的回环压测（发 payload、收满同样长度）。
     *
     * @param socket   已连接（或已穿透代理）的套接字
     * @param durationMs 测量时长
     * @param samples  环形样本数组
     * @return 完成请求数
     * @throws IOException 读写失败
     */
    private static long loopOn(Socket socket, int durationMs, long[] samples) throws IOException {
        byte[] payload = new byte[PAYLOAD];
        Arrays.fill(payload, (byte) 'p');
        byte[] buffer = new byte[PAYLOAD];
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        long deadline = System.currentTimeMillis() + durationMs;
        long done = 0;
        int slot = 0;
        while (System.currentTimeMillis() < deadline) {
            long start = System.nanoTime();
            out.write(payload);
            out.flush();
            if (readFully(in, buffer, PAYLOAD) != PAYLOAD) {
                break;
            }
            samples[slot++ & (SAMPLES - 1)] = System.nanoTime() - start;
            done++;
        }
        return done;
    }

    /**
     * 汇总各线程结果。
     *
     * @param counts   每线程完成数
     * @param errors   每线程错误数
     * @param samples  每线程样本
     * @return 汇总
     */
    private static Result merge(long[] counts, int[] errors, long[][] samples) {
        Result result = new Result();
        long totalSamples = 0;
        for (long[] array : samples) {
            for (long value : array) {
                if (value > 0) {
                    totalSamples++;
                }
            }
        }
        long[] merged = new long[(int) totalSamples];
        int at = 0;
        for (long[] array : samples) {
            for (long value : array) {
                if (value > 0) {
                    merged[at++] = value;
                }
            }
        }
        for (long value : counts) {
            result.count += value;
        }
        for (int value : errors) {
            result.errors += value;
        }
        result.latencies = merged;
        result.sort();
        return result;
    }

    /**
     * 打印一行结果。
     *
     * @param name   场景名
     * @param result 结果
     */
    private static void print(String name, Result result) {
        System.out.println("BENCH|" + name
                + "|qps=" + String.format(java.util.Locale.ROOT, "%.0f", result.qps(DURATION_MS))
                + "|p50us=" + String.format(java.util.Locale.ROOT, "%.0f", result.percentile(0.50))
                + "|p99us=" + String.format(java.util.Locale.ROOT, "%.0f", result.percentile(0.99))
                + "|err=" + result.errors);
        System.out.flush();
    }

    /**
     * 通用 TCP 回环压测：对给定代理端口开 N 条连接并发收发。
     *
     * @param port 代理端口
     * @return 结果
     * @throws Exception 线程失败
     */
    private static Result benchTcp(int port) throws Exception {
        Thread[] threads = new Thread[CONCURRENCY];
        long[] counts = new long[CONCURRENCY];
        int[] errors = new int[CONCURRENCY];
        long[][] samples = new long[CONCURRENCY][];

        // 预热：不计入统计，只为把 JIT 与连接建好
        for (int i = 0; i < CONCURRENCY; i++) {
            final int index = i;
            samples[index] = new long[SAMPLES];
            threads[index] = Thread.ofVirtual().start(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), SO_TIMEOUT_MS);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(SO_TIMEOUT_MS);
                    loopOn(socket, WARMUP_MS, new long[SAMPLES]);
                    counts[index] = loopOn(socket, DURATION_MS, samples[index]);
                } catch (Exception e) {
                    errors[index]++;
                }
            });
        }
        for (Thread thread : threads) {
            thread.join(WARMUP_MS + DURATION_MS + 20000L);
        }
        return merge(counts, errors, samples);
    }

    /**
     * JDK 版 TCP 代理吞吐。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(180)
    @DisplayName("吞吐：TcpProxyServer")
    void tcpProxyThroughput() throws Exception {
        try (ProxyTestSupport.EchoBackend backend = new ProxyTestSupport.EchoBackend()) {
            TcpProxyServer proxy = new TcpProxyServer(setting(),
                    remote -> new InetSocketAddress("127.0.0.1", backend.port()), 3000, 30000);
            proxy.start();
            try {
                print("tcp-proxy-jdk", benchTcp(proxy.getPort()));
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * AIO 版 TCP 代理吞吐。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(180)
    @DisplayName("吞吐：AioTcpProxyServer")
    void aioTcpProxyThroughput() throws Exception {
        try (ProxyTestSupport.EchoBackend backend = new ProxyTestSupport.EchoBackend()) {
            AioTcpProxyServer proxy = new AioTcpProxyServer(setting())
                    .setTarget("127.0.0.1", backend.port());
            proxy.start();
            try {
                print("tcp-proxy-aio", benchTcp(proxy.getPort()));
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 直连后端基线（作为代理开销的对照）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(180)
    @DisplayName("吞吐：直连后端基线")
    void directBackendBaseline() throws Exception {
        try (ProxyTestSupport.EchoBackend backend = new ProxyTestSupport.EchoBackend()) {
            print("tcp-direct-baseline", benchTcp(backend.port()));
        }
    }

    /**
     * SOCKS5 隧道吞吐（每线程握手一次，之后在隧道内回环）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(180)
    @DisplayName("吞吐：Socks5ProxyServer 隧道")
    void socks5Throughput() throws Exception {
        try (ProxyTestSupport.EchoBackend backend = new ProxyTestSupport.EchoBackend()) {
            Socks5ProxyServer proxy = new Socks5ProxyServer(setting());
            proxy.start();
            try {
                int proxyPort = proxy.getPort();
                Thread[] threads = new Thread[CONCURRENCY];
                long[] counts = new long[CONCURRENCY];
                int[] errors = new int[CONCURRENCY];
                long[][] samples = new long[CONCURRENCY][];
                for (int i = 0; i < CONCURRENCY; i++) {
                    final int index = i;
                    samples[index] = new long[SAMPLES];
                    threads[index] = Thread.ofVirtual().start(() -> {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress("127.0.0.1", proxyPort), SO_TIMEOUT_MS);
                            socket.setTcpNoDelay(true);
                            socket.setSoTimeout(SO_TIMEOUT_MS);
                            if (!socks5Connect(socket, backend.port())) {
                                errors[index]++;
                                return;
                            }
                            loopOn(socket, WARMUP_MS, new long[SAMPLES]);
                            counts[index] = loopOn(socket, DURATION_MS, samples[index]);
                        } catch (Exception e) {
                            errors[index]++;
                        }
                    });
                }
                for (Thread thread : threads) {
                    thread.join(WARMUP_MS + DURATION_MS + 20000L);
                }
                print("socks5-tunnel", merge(counts, errors, samples));
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 完成 SOCKS5 无认证握手并 CONNECT 到目标。
     *
     * @param socket 已连到代理的连接
     * @param port   目标端口（127.0.0.1）
     * @return 是否成功
     * @throws IOException 协议失败
     */
    private static boolean socks5Connect(Socket socket, int port) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();
        byte[] selected = new byte[2];
        if (readFully(in, selected, 2) != 2 || selected[1] != 0x00) {
            return false;
        }
        out.write(new byte[]{
                0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
                (byte) ((port >> 8) & 0xFF), (byte) (port & 0xFF)});
        out.flush();
        byte[] reply = new byte[10];
        return readFully(in, reply, 10) == 10 && reply[1] == 0x00;
    }

    /**
     * UDP 每秒包数（pps）：单包往返，128 个线程各自一个 DatagramSocket。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(180)
    @DisplayName("吞吐：JdkUdpServer pps")
    void udpPps() throws Exception {
        ServerSetting udpSetting = setting();
        com.chua.common.support.network.server.impl.JdkUdpServer server =
                new com.chua.common.support.network.server.impl.JdkUdpServer(udpSetting);
        server.registerHandler("*", (data, sender) -> data);
        server.start();
        try {
            int port = server.getPort();
            byte[] payload = new byte[PAYLOAD];
            Thread[] threads = new Thread[CONCURRENCY];
            long[] counts = new long[CONCURRENCY];
            int[] errors = new int[CONCURRENCY];
            for (int i = 0; i < CONCURRENCY; i++) {
                final int index = i;
                threads[index] = Thread.ofVirtual().start(() -> {
                    try (DatagramSocket client = new DatagramSocket()) {
                        client.setSoTimeout(SO_TIMEOUT_MS);
                        InetAddress address = InetAddress.getByName("127.0.0.1");
                        byte[] buffer = new byte[8192];
                        long deadline = System.currentTimeMillis() + DURATION_MS;
                        while (System.currentTimeMillis() < deadline) {
                            client.send(new DatagramPacket(payload, payload.length, address, port));
                            client.receive(new DatagramPacket(buffer, buffer.length));
                            counts[index]++;
                        }
                    } catch (Exception e) {
                        errors[index]++;
                    }
                });
            }
            for (Thread thread : threads) {
                thread.join(DURATION_MS + 20000L);
            }
            Result result = new Result();
            for (long value : counts) {
                result.count += value;
            }
            for (int value : errors) {
                result.errors += value;
            }
            print("udp-pps", result);
        } finally {
            server.stop();
        }
    }
}
