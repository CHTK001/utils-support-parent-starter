// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.vertx.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VertxTcpServer} 与 {@link VertxTcpProxyServer} 的真实链路回归。
 *
 * <p>这两个实现此前零用例。它们与自研 NIO/AIO 走完全不同的传输路径（Vert.x 事件循环 +
 * NetSocket 回调），JDK/AIO 版通过不代表这两个通过，必须单独验。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class VertxTcpServerTest {

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
     * 极简回声后端。
     */
    private static final class EchoBackend implements AutoCloseable {

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
        EchoBackend() throws IOException {
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
         * 单连接回声。
         *
         * @param socket 连接
         */
        private void serve(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
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
     * 未注册帧处理器时必须是回显（这是实现的默认契约）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpServer：默认回显字节原样返回")
    void echoesByDefault() throws Exception {
        int port = freePort();
        VertxTcpServer server = new VertxTcpServer(setting(port));
        server.start();
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(10000);
            client.setTcpNoDelay(true);
            String payload = "vertx-tcp-echo";
            client.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            byte[] buffer = new byte[64];
            int read = readFully(client.getInputStream(), buffer, payload.length());
            assertEquals(payload, new String(buffer, 0, read, StandardCharsets.UTF_8),
                    "回显内容不符");
        } finally {
            server.stop();
        }
    }

    /**
     * 代理必须把字节原样转给后端并回传。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpProxyServer：字节双向原样转发")
    void proxiesToBackend() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            int port = freePort();
            VertxTcpProxyServer proxy = new VertxTcpProxyServer(setting(port),
                    new InetSocketAddress("127.0.0.1", backend.port()));
            proxy.start();
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(10000);
                client.setTcpNoDelay(true);
                String payload = "vertx-proxy-echo";
                client.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();

                byte[] buffer = new byte[64];
                int read = readFully(client.getInputStream(), buffer, payload.length());
                assertEquals(payload, new String(buffer, 0, read, StandardCharsets.UTF_8),
                        "经 Vert.x 代理往返的内容不一致");
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 后端不可达时必须在限期内断开，不能挂住客户端。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpProxyServer：后端不可达时快速断开")
    void closesWhenBackendUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        int port = freePort();
        VertxTcpProxyServer proxy = new VertxTcpProxyServer(setting(port),
                new InetSocketAddress("127.0.0.1", closedPort));
        proxy.start();
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(15000);
            client.getOutputStream().write('x');
            client.getOutputStream().flush();

            long start = System.currentTimeMillis();
            int read;
            boolean failedFast;
            try {
                read = client.getInputStream().read();
                failedFast = read == -1;
            } catch (IOException e) {
                read = -2;
                failedFast = true;
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(failedFast, "后端不可达时应 EOF 或连接重置，实际读到: " + read);
            assertTrue(elapsed < 12000L, "后端不可达时耗时 " + elapsed + "ms，应快速断开");
        } finally {
            proxy.stop();
        }
    }
}
