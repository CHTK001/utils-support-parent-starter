// [P3C A20 豁免] [P3C E1 豁免] [P3C MJ-方法注释 豁免] 压测参数魔法值场景。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTestSupport.KeepAliveHttpBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AioHttpProxyServer 高并发 Keep-Alive 正确性回归测试。
 *
 * <p>背景：高并发下后端响应与下一响应在同一 TCP 段到达（粘包）时，
 * 若首包内多读的字节未保留给下一请求，下一响应头会丢失，
 * 解析错位导致连接被关闭，表现为压测下偶发 EOF/超时。
 * 本测试以 256 并发 × 10s 对 keep-alive 后端连续压测，断言全程零断开。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class AioHttpProxyStressTest {

    @Test
    @DisplayName("AioHttpProxyServer 256 并发 keep-alive 全程零断开")
    void keepAliveAt256NoDisconnect() throws Exception {
        try (KeepAliveHttpBackend backend = new KeepAliveHttpBackend()) {
            ServerSetting setting = new ServerSetting();
            setting.setHost("127.0.0.1");
            setting.setPort(0);
            setting.setAuto(false);
            AioHttpProxyServer proxy = new AioHttpProxyServer(setting);
            proxy.start();
            try {
                int port = proxy.getPort();
                int concurrency = 256;
                Thread[] threads = new Thread[concurrency];
                AtomicLong done = new AtomicLong();
                AtomicInteger errTimeout = new AtomicInteger();
                AtomicInteger errEof = new AtomicInteger();
                AtomicInteger errConnect = new AtomicInteger();
                AtomicInteger errBadBody = new AtomicInteger();
                AtomicInteger errBadStatus = new AtomicInteger();
                String target = "127.0.0.1:" + backend.port();
                byte[] request = ("GET http://" + target + "/x HTTP/1.1\r\n"
                        + "Host: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                for (int i = 0; i < concurrency; i++) {
                    // Windows 内核把 listen backlog 截断到约 200：一次性并发建连会丢 SYN/RST，
                    // 表现为 ConnectException（被外层 catch 计成 errEof）。分批建连规避该平台限制。
                    if (i > 0 && i % 64 == 0) {
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    threads[i] = Thread.ofVirtual().start(() -> {
                        long deadline = System.currentTimeMillis() + 10_000;
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                            socket.setTcpNoDelay(true);
                            socket.setSoTimeout(10_000);
                            OutputStream out = socket.getOutputStream();
                            InputStream in = new java.io.BufferedInputStream(
                                    socket.getInputStream(), 16384);
                            while (System.currentTimeMillis() < deadline) {
                                try {
                                    out.write(request);
                                    out.flush();
                                    String head = readHead(in);
                                    if (head == null) {
                                        errEof.incrementAndGet();
                                        return;
                                    }
                                    if (!head.startsWith("HTTP/1.1 200")) {
                                        errBadStatus.incrementAndGet();
                                        return;
                                    }
                                    int len = contentLength(head);
                                    if (len < 0) {
                                        errBadStatus.incrementAndGet();
                                        return;
                                    }
                                    byte[] body = new byte[len];
                                    if (readFully(in, body, len) != len) {
                                        errBadBody.incrementAndGet();
                                        if (errBadBody.get() <= 3) {
                                            System.out.println("[stress] EOF in body, head="
                                                    + head.replace("\r", "\\r").replace("\n", "\\n")
                                                    + " got=" + new String(body, 0, Math.min(body.length, 40),
                                                    StandardCharsets.US_ASCII).replace("\r", "\\r")
                                                    .replace("\n", "\\n"));
                                        }
                                        return;
                                    }
                                    if (!"proxied-body-ok".equals(
                                            new String(body, StandardCharsets.US_ASCII))) {
                                        errBadBody.incrementAndGet();
                                        return;
                                    }
                                    done.incrementAndGet();
                                } catch (SocketTimeoutException e) {
                                    errTimeout.incrementAndGet();
                                    return;
                                } catch (IOException e) {
                                    errEof.incrementAndGet();
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            if (e instanceof java.net.ConnectException
                                    || e instanceof java.net.SocketTimeoutException) {
                                errConnect.incrementAndGet();
                            } else {
                                errEof.incrementAndGet();
                            }
                        }
                    });
                }
                for (Thread t : threads) {
                    t.join(30_000);
                }
                assertTrue(done.get() > 50_000, "总完成请求数异常偏低: " + done.get());
                assertEquals(0, errConnect.get(),
                        "建连被拒/超时（Windows backlog 限制，需分批建连）");
                assertEquals(0, errEof.get(), "存在连接被关闭(EOF)");
                assertEquals(0, errTimeout.get(), "存在请求超时");
                assertEquals(0, errBadBody.get(), "响应体不完整");
                assertEquals(0, errBadStatus.get(), "响应状态非 200");
            } finally {
                proxy.stop();
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
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
                return head.toString();
            } else {
                matched = 0;
            }
            if (head.length() > 8192) {
                return null;
            }
        }
        return null;
    }

    private static int contentLength(String head) {
        String text = head.toLowerCase(java.util.Locale.ROOT);
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

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                return off;
            }
            off += n;
        }
        return off;
    }
}
