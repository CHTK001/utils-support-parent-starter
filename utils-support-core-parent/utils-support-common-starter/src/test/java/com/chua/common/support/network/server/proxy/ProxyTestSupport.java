// [P3C A20 豁免] A20/A30/A31: 测试夹具与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代理测试的公共夹具：回声后端、HTTP 后端、空闲端口、读满工具。
 *
 * <p>这些 proxy 之前一个用例都没有，补测时会共用同一套"真实后端"，
 * 所以夹具单独放这里，避免每个测试类各写一份被代理服务。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
final class ProxyTestSupport {

    /**
     * HTTP 后端固定返回的正文。
     */
    static final String HTTP_BODY = "proxied-body-ok";

    /**
     * 工具类不允许实例化。
     */
    private ProxyTestSupport() {
    }

    /**
     * 原样回写的 TCP 后端，模拟被代理的真实服务。
     */
    static final class EchoBackend implements AutoCloseable {

        /**
         * 监听套接字。
         */
        private final ServerSocket serverSocket;

        /**
         * 运行标志。
         */
        private final AtomicBoolean running = new AtomicBoolean(true);

        /**
         * 已接受的连接。
         */
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        /**
         * 启动后端。
         *
         * @throws IOException 绑定失败
         */
        EchoBackend() throws IOException {
            serverSocket = new ServerSocket(0, 200, InetAddress.getByName("127.0.0.1"));
            Thread.ofVirtual().name("proxy-echo-backend").start(this::loop);
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
                byte[] buffer = new byte[8192];
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
         * 实际监听端口。
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
     * 极简 HTTP 后端：不管请求什么路径，都回固定正文并关闭连接。
     */
    static final class HttpBackend implements AutoCloseable {

        /**
         * 监听套接字。
         */
        private final ServerSocket serverSocket;

        /**
         * 运行标志。
         */
        private final AtomicBoolean running = new AtomicBoolean(true);

        /**
         * 已接受的连接。
         */
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        /**
         * 启动后端。
         *
         * @throws IOException 绑定失败
         */
        HttpBackend() throws IOException {
            serverSocket = new ServerSocket(0, 200, InetAddress.getByName("127.0.0.1"));
            Thread.ofVirtual().name("proxy-http-backend").start(this::loop);
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
         * 读掉请求头后回固定响应。
         *
         * @param socket 连接
         */
        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5000);
                readHeaders(socket.getInputStream());
                byte[] body = HTTP_BODY.getBytes(StandardCharsets.UTF_8);
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
         * 实际监听端口。
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
     * keep-alive 版 HTTP 后端：同一连接上连续响应多个请求，用于测 HTTP 代理/服务器的稳态吞吐。
     *
     * <p>{@link HttpBackend} 每次响应后发 {@code Connection: close}，拿它压测会把建连成本算进去，
     * 既不能反映代理真实吞吐，也无法与 TCP 那组长连接回环横向比。</p>
     */
    static final class KeepAliveHttpBackend implements AutoCloseable {

        /**
         * 监听套接字。
         */
        private final ServerSocket serverSocket;

        /**
         * 运行标志。
         */
        private final AtomicBoolean running = new AtomicBoolean(true);

        /**
         * 已接受的连接。
         */
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        /**
         * 启动后端。
         *
         * @throws IOException 绑定失败
         */
        KeepAliveHttpBackend() throws IOException {
            serverSocket = new ServerSocket(0, 500, InetAddress.getByName("127.0.0.1"));
            Thread.ofVirtual().name("proxy-http-keepalive-backend").start(this::loop);
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
         * 单连接上循环应答。
         *
         * @param socket 连接
         */
        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(30000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] body = HTTP_BODY.getBytes(StandardCharsets.UTF_8);
                byte[] header = ("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.US_ASCII);
                while (running.get()) {
                    if (!readHeadersEof(in)) {
                        return;
                    }
                    out.write(header);
                    out.write(body);
                    out.flush();
                }
            } catch (IOException ignored) {
                // 对端关闭属正常收尾
            }
        }

        /**
         * 读掉请求头；连接结束时返回 false。
         *
         * @param in 输入流
         * @return true 表示读到一个完整请求头
         * @throws IOException 读取失败
         */
        private boolean readHeadersEof(InputStream in) throws IOException {
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
                    return true;
                } else {
                    matched = 0;
                }
            }
            return false;
        }

        /**
         * 实际监听端口。
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
     * 读掉 HTTP 请求头（到空行为止）。
     *
     * @param in 输入流
     * @throws IOException 读取失败
     */
    static void readHeaders(InputStream in) throws IOException {
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
                return;
            } else {
                matched = 0;
            }
        }
    }

    /**
     * 造一个未监听过的端口（用于"后端不可达"）。
     *
     * @return 端口
     * @throws IOException 套接字失败
     */
    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
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
    static int readFully(InputStream in, byte[] buffer, int length) throws IOException {
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
     * 读到连接关闭为止。
     *
     * @param in 输入流
     * @return 读到的全部内容
     * @throws IOException 读取失败
     */
    static String readToEof(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }
}
