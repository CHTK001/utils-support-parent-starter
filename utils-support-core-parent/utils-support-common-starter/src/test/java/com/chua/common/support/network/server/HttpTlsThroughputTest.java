package com.chua.common.support.network.server;

import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.nio.NioHttpServer;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 服务端 TLS 吞吐基准（真实 TLS 握手 + keep-alive 回环）。
 *
 * <p><b>默认跳过</b>：加 {@code -Dbench.throughput=true} 才跑，不污染常规回归。</p>
 *
 * <p>四个场景同骨架、同负载（1024B 响应、64 并发、3s 预热 + 10s 测量），
 * 只切换「明文/TLS」与「NIO/AIO」两个维度，用于给出 TLS 的加解密开销比例。
 * 输出固定格式 {@code BENCH|场景|qps=..|p50us=..|p99us=..|err=..} 方便脚本抓取。</p>
 *
 * <p>本机 loopback 自压，数字是下限；同机多会话会带来 40%~50% 漂移，
 * 只用于同一轮内的相对比较。</p>
 *
 * @author CH
 */
@EnabledIfSystemProperty(named = "bench.throughput", matches = "true")
class HttpTlsThroughputTest {

    /**
     * 测量时长（毫秒）。
     */
    private static final int DURATION_MS = Integer.getInteger("bench.durationMs", 10_000);

    /**
     * 预热时长（毫秒）。
     */
    private static final int WARMUP_MS = Integer.getInteger("bench.warmupMs", 3_000);

    /**
     * 并发连接数（每连接串行 keep-alive）。
     */
    private static final int CONCURRENCY = Integer.getInteger("bench.concurrency", 64);

    /**
     * 每次请求的采样条数（环形覆盖）。
     */
    private static final int SAMPLES = 8192;

    /**
     * 响应体大小（字节）。
     */
    private static final int PAYLOAD = 1024;

    /**
     * 读写超时（毫秒）。
     */
    private static final int SO_TIMEOUT_MS = Integer.getInteger("bench.soTimeoutMs", 10_000);

    @Test
    @Timeout(300)
    @DisplayName("基准：NioHttpServer 明文（基线）")
    void nioPlain() throws Exception {
        print("nio-http-plain", run(NioHttpServer::new, false));
    }

    @Test
    @Timeout(300)
    @DisplayName("基准：NioHttpServer TLS")
    void nioTls() throws Exception {
        print("nio-http-tls", run(NioHttpServer::new, true));
    }

    @Test
    @Timeout(300)
    @DisplayName("基准：AioHttpServer 明文（基线）")
    void aioPlain() throws Exception {
        print("aio-http-plain", run(AioHttpServer::new, false));
    }

    @Test
    @Timeout(300)
    @DisplayName("基准：AioHttpServer TLS")
    void aioTls() throws Exception {
        print("aio-http-tls", run(AioHttpServer::new, true));
    }

    /**
     * 起服务 → 压测 → 停服务。
     *
     * @param factory 服务端工厂
     * @param tls     是否启用 TLS（自签自动证书）
     * @return 统计结果
     * @throws Exception 启动或压测失败
     */
    private static Result run(ServerFactory factory, boolean tls) throws Exception {
        int port = freePort();
        ServerSetting.SslConfig ssl = tls
                ? ServerSetting.SslConfig.builder().selfSignedAuto(true).build() : null;
        ServerSetting setting = ServerSetting.builder()
                .protocol("http")
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .ssl(ssl)
                .build();
        AbstractServer server = factory.create(setting);
        server.registerMapping("/echo", HttpMethod.GET, echoHandler());
        server.start();
        try {
            return bench(port, tls);
        } finally {
            server.stop();
        }
    }

    /**
     * 响应固定 1024B 的处理器。
     *
     * @return 处理器
     */
    private static ServerHandler echoHandler() {
        char[] pad = new char[PAYLOAD];
        Arrays.fill(pad, 'e');
        String body = new String(pad);
        return new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                response.setStatus(200).setContentType("text/plain").setBody(body);
                response.end();
            }
        };
    }

    /**
     * keep-alive 压测主体：每线程一条连接，串行请求-响应直到截止时间。
     *
     * @param port 服务端端口
     * @param tls  是否 TLS
     * @return 统计结果
     * @throws Exception 压测失败
     */
    private static Result bench(int port, boolean tls) throws Exception {
        SSLContext ctx = tls ? trustAllContext() : null;
        long[] counts = new long[CONCURRENCY];
        long[] errors = new long[CONCURRENCY];
        long[][] samples = new long[CONCURRENCY][];
        byte[] request = "GET /echo HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        java.util.concurrent.atomic.AtomicInteger finished =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> errTypes =
                new java.util.concurrent.ConcurrentHashMap<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            if (i > 0 && i % 64 == 0) {
                Thread.sleep(100L);
            }
            final int index = i;
            samples[index] = new long[SAMPLES];
            Thread.ofVirtual().start(() -> {
                long warmEnd = System.currentTimeMillis() + WARMUP_MS;
                long end = warmEnd + DURATION_MS;
                long done = 0;
                long failed = 0;
                try {
                    while (System.currentTimeMillis() < end) {
                        boolean warm = System.currentTimeMillis() < warmEnd;
                        long phaseEnd = warm ? warmEnd : end;
                        try (Socket socket = open(port, tls, ctx)) {
                            long finishedRequests = loop(socket, request, phaseEnd,
                                    warm ? new long[SAMPLES] : samples[index]);
                            if (!warm) {
                                done += finishedRequests;
                            }
                        } catch (Exception e) {
                            failed++;
                            String key = e.getClass().getSimpleName();
                            errTypes.computeIfAbsent(key,
                                    k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                        }
                    }
                } finally {
                    counts[index] = done;
                    errors[index] = failed;
                    finished.incrementAndGet();
                }
            });
        }
        long deadline = System.currentTimeMillis() + WARMUP_MS + DURATION_MS + 30_000L;
        while (System.currentTimeMillis() < deadline && finished.get() < CONCURRENCY) {
            Thread.sleep(50L);
        }
        return merge(counts, errors, samples, errTypes);
    }

    /**
     * 建立一条连接（必要时完成 TLS 握手）。
     *
     * @param port 端口
     * @param tls  是否 TLS
     * @param ctx  TLS 上下文
     * @return 已连接 socket
     * @throws IOException 连接失败
     */
    private static Socket open(int port, boolean tls, SSLContext ctx) throws IOException {
        if (!tls) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), SO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SO_TIMEOUT_MS);
            return socket;
        }
        SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(SO_TIMEOUT_MS);
        socket.startHandshake();
        return socket;
    }

    /**
     * 在一条连接上串行发请求直到截止时间。
     *
     * @param socket   连接
     * @param request  请求字节
     * @param deadline 截止时间（毫秒）
     * @param samples  延迟采样
     * @return 完成的请求数
     * @throws IOException 连接断开
     */
    private static long loop(Socket socket, byte[] request, long deadline, long[] samples)
            throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
        long done = 0;
        int slot = 0;
        while (System.currentTimeMillis() < deadline) {
            long start = System.nanoTime();
            out.write(request);
            out.flush();
            int length = readContentLength(in);
            if (length < 0) {
                throw new IOException("连接被关闭");
            }
            byte[] body = new byte[length];
            if (readFully(in, body, length) != length) {
                throw new IOException("响应体截断");
            }
            samples[slot++ & (SAMPLES - 1)] = System.nanoTime() - start;
            done++;
        }
        return done;
    }

    /**
     * 读响应头并取 Content-Length。
     *
     * @param in 输入流
     * @return 响应体长度；连接关闭返回 -1
     * @throws IOException 读取失败
     */
    private static int readContentLength(InputStream in) throws IOException {
        int length = -1;
        StringBuilder line = new StringBuilder(96);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return length >= 0 ? length : -1;
            }
            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                if (line.length() == 0) {
                    return length;
                }
                String text = line.toString();
                int colon = text.indexOf(':');
                if (colon > 0 && "content-length".equalsIgnoreCase(text.substring(0, colon).trim())) {
                    length = Integer.parseInt(text.substring(colon + 1).trim());
                }
                line.setLength(0);
                continue;
            }
            line.append((char) b);
            if (line.length() > 8192) {
                throw new IOException("响应头过长");
            }
        }
    }

    /**
     * 定长读取。
     *
     * @param in  输入流
     * @param buf 缓冲
     * @param len 目标长度
     * @return 实际读取
     * @throws IOException 读取失败
     */
    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n < 0) {
                return total;
            }
            total += n;
        }
        return total;
    }

    /**
     * 信任一切证书的 TLS 上下文（自签证书测试用）。
     *
     * @return TLS 上下文
     * @throws Exception 初始化失败
     */
    private static SSLContext trustAllContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return ctx;
    }

    /**
     * 合并各线程计数与延迟采样。
     *
     * @param counts 成功计数
     * @param errors 失败计数
     * @param samples 延迟采样
     * @return 结果
     */
    private static Result merge(long[] counts, long[] errors, long[][] samples,
                                java.util.concurrent.ConcurrentHashMap<String,
                                        java.util.concurrent.atomic.AtomicInteger> errTypes) {
        long total = 0;
        long failed = 0;
        int n = 0;
        for (int i = 0; i < counts.length; i++) {
            total += counts[i];
            failed += errors[i];
            for (long v : samples[i]) {
                if (v > 0) {
                    n++;
                }
            }
        }
        long[] all = new long[n];
        int k = 0;
        for (long[] arr : samples) {
            for (long v : arr) {
                if (v > 0) {
                    all[k++] = v;
                }
            }
        }
        Arrays.sort(all);
        StringBuilder detail = new StringBuilder();
        errTypes.forEach((type, counter) -> {
            if (detail.length() > 0) {
                detail.append(';');
            }
            detail.append(type).append(':').append(counter.get());
        });
        return new Result(total, failed, all, detail.toString());
    }

    /**
     * 输出一行可抓取的基准结果。
     *
     * @param name   场景名
     * @param result 结果
     */
    private static void print(String name, Result result) {
        System.out.println("BENCH|" + name
                + "|qps=" + String.format(Locale.ROOT, "%.0f", result.qps())
                + "|p50us=" + String.format(Locale.ROOT, "%.0f", result.percentile(0.50))
                + "|p99us=" + String.format(Locale.ROOT, "%.0f", result.percentile(0.99))
                + "|err=" + result.errors
                + (result.details.isEmpty() ? "" : "|errDetail=" + result.details));
        System.out.flush();
        assertEquals(0, result.errors, name + " 出现连接失败");
        assertTrue(result.count > 0, name + " 没有完成的请求");
    }

    /**
     * 取一个空闲 TCP 端口。
     *
     * @return 端口
     * @throws IOException 绑定失败
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 服务端工厂（NIO / AIO）。
     */
    @FunctionalInterface
    private interface ServerFactory {
        /**
         * 创建服务端。
         *
         * @param setting 配置
         * @return 服务端
         */
        AbstractServer create(ServerSetting setting);
    }

    /**
     * 基准统计结果。
     */
    private static final class Result {
        private final long count;
        private final long errors;
        private final long[] samples;
        private final String details;

        private Result(long count, long errors, long[] samples, String details) {
            this.count = count;
            this.errors = errors;
            this.samples = samples;
            this.details = details;
        }

        private double qps() {
            return count * 1000.0 / DURATION_MS;
        }

        private double percentile(double ratio) {
            if (samples.length == 0) {
                return 0;
            }
            int idx = (int) Math.min(samples.length - 1L, Math.round(ratio * (samples.length - 1)));
            return samples[idx] / 1000.0;
        }
    }
}
