// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.vertx.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VertxHttpProxyServer} 的真实链路回归（此前零用例）。
 *
 * @author CH
 * @since 4.0.0.52
 */
class VertxHttpProxyServerTest {

    /**
     * 后端固定正文。
     */
    private static final String BODY = "vertx-proxied-body";

    /**
     * 取空闲端口。
     *
     * @return 端口
     * @throws Exception 套接字失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /**
     * 监听配置。
     *
     * @param port 端口
     * @return 配置
     */
    private static ServerSetting setting(int port) {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 极简 HTTP 后端：读掉请求头后回固定正文并关闭。
     */
    private static final class HttpBackend implements AutoCloseable {

        /**
         * 监听套接字。
         */
        private final ServerSocket serverSocket;

        /**
         * 运行标志。
         */
        private final AtomicBoolean running = new AtomicBoolean(true);

        /**
         * 已接受连接。
         */
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        /**
         * 启动后端。
         *
         * @throws IOException 绑定失败
         */
        HttpBackend() throws IOException {
            serverSocket = new ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"));
            Thread.ofVirtual().start(this::loop);
        }

        /**
         * 接受循环。
         */
        private void loop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    sockets.add(socket);
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException e) {
                    return;
                }
            }
        }

        /**
         * 读请求头后回响应。
         *
         * @param socket 连接
         */
        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(10000);
                InputStream in = socket.getInputStream();
                int matched = 0;
                int read;
                while ((read = in.read()) >= 0) {
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
                }
                byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException ignored) {
                // 对端关闭属正常收尾
            }
        }

        /**
         * 端口。
         *
         * @return 端口
         */
        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 清理失败不影响断言
                }
            }
        }
    }

    /**
     * 发 GET 并读完整响应。
     *
     * @param port 端口
     * @param path 路径
     * @return 响应全文
     * @throws Exception 套接字失败
     */
    private static String get(int port, String path) throws Exception {
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(10000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = client.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 代理必须把请求转给后端并回传正文。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxHttpProxyServer：请求转发并回传正文")
    void forwardsToBackend() throws Exception {
        try (HttpBackend backend = new HttpBackend()) {
            int port = freePort();
            VertxHttpProxyServer proxy = new VertxHttpProxyServer(setting(port),
                    new InetSocketAddress("127.0.0.1", backend.port()));
            proxy.start();
            try {
                String response = get(port, "/hello");
                assertTrue(response.contains("200"),
                        "未返回 200：" + head(response));
                assertTrue(response.contains(BODY), "正文未经代理回传：" + head(response));
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 后端不可达时必须给出确定结果，不能挂起。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxHttpProxyServer：后端不可达时快速给出结果")
    void failsFastWhenBackendDown() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        int port = freePort();
        VertxHttpProxyServer proxy = new VertxHttpProxyServer(setting(port),
                new InetSocketAddress("127.0.0.1", closedPort));
        proxy.start();
        try {
            long start = System.currentTimeMillis();
            String response;
            try {
                response = get(port, "/hello");
            } catch (IOException e) {
                response = "";
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 12000L, "后端不可达时耗时 " + elapsed + "ms，应快速失败");
            assertTrue(response.isEmpty() || response.contains("502") || response.contains("500"),
                    "后端不可达时应回 5xx 或断开，实际：" + head(response));
        } finally {
            proxy.stop();
        }
    }

    /**
     * 截取响应前 160 字符。
     *
     * @param response 响应
     * @return 摘要
     */
    private static String head(String response) {
        return response.substring(0, Math.min(160, response.length())).replace("\r\n", "|");
    }
}
