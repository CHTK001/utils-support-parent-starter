// [P3C A20 豁免] A20/A30/A31: 压测客户端与协议常量含魔法值场景，豁免。
// [P3C E1 豁免] [P3C SE-安全 豁免] CLI 压测入口，标准输出与退出码用于脚本判定。
package com.chua.common.support.network.bench;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 独立进程的 HTTP keep-alive 压测客户端。
 *
 * <p>为什么要独立进程：{@code HttpBench} 把客户端与服务端放在同一个 JVM 里，
 * 测出来的数字被同机 CPU 争抢压过，既不能当服务端上限，也无法和 nginx 这种
 * 原生服务做公平比较——nginx 的压测端永远在别的进程里。</p>
 *
 * <p>用法：{@code java -cp <cp> HttpLoadClient <host> <port> <path> <concurrency> <seconds>}</p>
 *
 * <p>输出一行：{@code BENCH|http-load|<label>|qps=..|p50us=..|p99us=..|err=..|bytes=..}</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class HttpLoadClient {

    /**
     * 每线程环形采样容量。
     */
    private static final int SAMPLES = 8192;

    /**
     * 工具类不允许实例化。
     */
    private HttpLoadClient() {
    }

    /**
     * 入口。
     *
     * @param args host port path concurrency seconds [label]
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("USAGE: <host> <port> <path> <concurrency> <seconds> [label]");
            System.exit(2);
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String path = args[2];
        int concurrency = Integer.parseInt(args[3]);
        int seconds = Integer.parseInt(args[4]);
        String label = args.length > 5 ? args[5] : host + ":" + port;

        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Accept: */*\r\n"
                + "\r\n";
        byte[] requestBytes = request.getBytes(StandardCharsets.US_ASCII);

        Thread[] threads = new Thread[concurrency];
        long[] counts = new long[concurrency];
        int[] errors = new int[concurrency];
        long[][] samples = new long[concurrency][];
        long[] bodyBytes = new long[concurrency];

        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            samples[index] = new long[SAMPLES];
            threads[index] = Thread.ofVirtual().start(() -> {
                long done = 0;
                long bodyTotal = 0;
                int slot = 0;
                long deadline = System.currentTimeMillis() + seconds * 1000L;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 5000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(15000);
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    // 复用同一个缓冲：每请求 new byte[len] 会在高 QPS 下制造大量短命对象，
                    // 让压测端自己成为瓶颈（GC 抖动会直接反映成服务端 p99 变差）。
                    byte[] body = new byte[8192];
                    while (System.currentTimeMillis() < deadline) {
                        long start = System.nanoTime();
                        out.write(requestBytes);
                        out.flush();
                        int length = readContentLength(in);
                        if (length < 0) {
                            break;
                        }
                        if (body.length < length) {
                            body = new byte[Math.max(length, body.length * 2)];
                        }
                        if (readFully(in, body, length) != length) {
                            break;
                        }
                        samples[index][slot++ & (SAMPLES - 1)] = System.nanoTime() - start;
                        bodyTotal += length;
                        done++;
                    }
                } catch (Exception e) {
                    errors[index]++;
                }
                counts[index] = done;
                bodyBytes[index] = bodyTotal;
            });
        }
        for (Thread thread : threads) {
            thread.join(seconds * 1000L + 30000L);
        }

        long total = 0;
        long totalBody = 0;
        int error = 0;
        long[] merged = new long[0];
        int capacity = 0;
        for (long[] array : samples) {
            for (long value : array) {
                if (value > 0) {
                    capacity++;
                }
            }
        }
        merged = new long[capacity];
        int at = 0;
        for (long[] array : samples) {
            for (long value : array) {
                if (value > 0) {
                    merged[at++] = value;
                }
            }
        }
        java.util.Arrays.sort(merged);
        for (long value : counts) {
            total += value;
        }
        for (long value : bodyBytes) {
            totalBody += value;
        }
        for (int value : errors) {
            error += value;
        }
        double qps = total * 1.0 / Math.max(1, seconds);
        long p50 = merged.length == 0 ? 0 : merged[(int) (merged.length * 0.50)] / 1000;
        long p99 = merged.length == 0 ? 0 : merged[(int) Math.min(merged.length - 1L, merged.length * 0.99)] / 1000;
        long avgBody = total == 0 ? 0 : totalBody / total;

        System.out.println("BENCH|http-load|" + label
                + "|qps=" + String.format(java.util.Locale.ROOT, "%.0f", qps)
                + "|p50us=" + p50
                + "|p99us=" + p99
                + "|err=" + error
                + "|bytes=" + avgBody
                + "|conns=" + concurrency);
        System.out.flush();
        System.exit(0);
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
     * 读响应头并解析 Content-Length。
     *
     * @param in 输入流
     * @return 正文长度；连接关闭或解析失败返回 -1
     * @throws IOException 读取失败
     */
    private static int readContentLength(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int matched = 0;
        int read;
        while ((read = in.read()) >= 0) {
            head.append((char) read);
            if (matched == 0 && read == '\r') {
                matched = 1;
            } else if (matched == 1 && read == '\n') {
                matched = 2;
            } else if (matched == 2 && read == '\r') {
                matched = 3;
            } else if (matched == 3 && read == '\n') {
                break;
            } else {
                matched = 0;
            }
            if (head.length() > 8192) {
                break;
            }
        }
        String text = head.toString().toLowerCase(java.util.Locale.ROOT);
        int at = text.indexOf("content-length:");
        if (at < 0) {
            return -1;
        }
        int i = at + "content-length:".length();
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        int j = i;
        while (j < text.length() && Character.isDigit(text.charAt(j))) {
            j++;
        }
        return j > i ? Integer.parseInt(text.substring(i, j)) : -1;
    }
}
