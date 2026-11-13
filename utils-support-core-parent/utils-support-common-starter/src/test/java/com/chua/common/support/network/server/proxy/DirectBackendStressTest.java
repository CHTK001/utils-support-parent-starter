// [P3C A20 豁免] [P3C E1 豁免] [P3C MJ-方法注释 豁免] 压测参数魔法值场景。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.proxy.ProxyTestSupport.KeepAliveHttpBackend;
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

/**
 * 直连对照：不经代理直接打 KeepAliveHttpBackend，用于区分 EOF 来源（代理 or 后端/测试）。
 *
 * <p>它同时是测试基础设施的自检：若直连都出现 EOF/超时，说明后端夹具本身在高并发下
 * 不可靠（历史根因是逐字节 read 的系统调用风暴），此时代理侧的失败不能归因于代理。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class DirectBackendStressTest {

    @Test
    void direct() throws Exception {
        try (KeepAliveHttpBackend backend = new KeepAliveHttpBackend()) {
            int port = backend.port();
            int concurrency = 256;
            Thread[] threads = new Thread[concurrency];
            AtomicLong done = new AtomicLong();
            AtomicInteger errEof = new AtomicInteger();
            AtomicInteger errConnect = new AtomicInteger();
            AtomicInteger errTimeout = new AtomicInteger();
            AtomicInteger errBadBody = new AtomicInteger();
            byte[] request = ("GET /x HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < concurrency; i++) {
                // Windows 内核把 listen backlog 截断到约 200：一次性并发建连会丢 SYN/RST
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
                                int len = contentLength(head);
                                if (len < 0) {
                                    errEof.incrementAndGet();
                                    return;
                                }
                                byte[] body = new byte[len];
                                if (readFully(in, body, len) != len) {
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
            System.out.println("[direct] done=" + done.get()
                    + " errEof=" + errEof.get()
                    + " errConnect=" + errConnect.get()
                    + " errTimeout=" + errTimeout.get()
                    + " errBadBody=" + errBadBody.get());

            assertEquals(0, errConnect.get(), "直连建连被拒/超时（Windows backlog 限制）");
            assertEquals(0, errEof.get(), "直连后端出现连接关闭(EOF)，测试基础设施异常");
            assertEquals(0, errTimeout.get(), "直连后端出现请求超时");
            assertEquals(0, errBadBody.get(), "直连后端响应体不完整");
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
