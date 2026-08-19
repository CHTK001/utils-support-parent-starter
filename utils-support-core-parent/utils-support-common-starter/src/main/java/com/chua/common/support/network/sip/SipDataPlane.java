package com.chua.common.support.network.sip;

import com.chua.common.support.lang.algorithm.hmac.HMacUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * frp 数据平面：在独立端口上为每条隧道建立一条独立 TCP 长连接，
 * 握手校验签名后进入裸字节流双向透传（TCP 代理模式），无帧封装。
 *
 * <p>数据平面与 SIP 信令解耦：</p>
 * <ul>
 *   <li>信令（open/close/register）仍走 {@code sip/} 行协议</li>
 *   <li>隧道数据走本类监听的独立端口，每个隧道由访问方与提供方各建立一条
 *       TCP 连接到服务器，服务器按 {@code channelId} 双向桥接，无队头阻塞</li>
 * </ul>
 * 连接建立后首行发送 {@code channelId|role|signature} 完成绑定，
 * 随后进入裸字节流双向透传（与 TcpProxyServer 相同模式）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipDataPlane {

    /**
     * 数据帧头类型：访问方连接
     */
    static final String ROLE_VISITOR = "visitor";

    /**
     * 数据帧头类型：提供方连接
     */
    static final String ROLE_PROVIDER = "provider";

    /**
     * 监听端口
     */
    private final int port;

    /**
     * 监听地址
     */
    private final String host;

    /**
     * 认证令牌（与 SipServer 相同）
     */
    private final String token;

    /**
     * 服务端 Socket
     */
    private ServerSocket serverSocket;

    /**
     * 接受连接线程
     */
    private Thread acceptThread;

    /**
     * 是否运行中
     */
    private volatile boolean running;

    /**
     * 通道路由：channelId -> 通道桥接器
     */
    private final Map<String, DataChannel> channels = new ConcurrentHashMap<>();

    /**
     * 数据平面关闭回调（channelId, 原因）
     */
    private final BiConsumer<String, String> closeHandler;

    /**
     * 创建数据平面。
     *
     * @param host         监听地址
     * @param port         监听端口
     * @param token        认证令牌
     * @param closeHandler 通道关闭回调（channelId, 原因）
     */
    public SipDataPlane(String host, int port, String token, BiConsumer<String, String> closeHandler) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.closeHandler = closeHandler;
    }

    /**
     * 启动数据平面。
     *
     * @return 当前实例，支持链式调用
     */
    public SipDataPlane start() {
        if (running) {
            return this;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(host, port), 64);
            running = true;
            acceptThread = ThreadUtils.newThread(this::acceptLoop, "sip-data-plane-" + port);
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("SIP 数据平面启动成功: {}:{}", host, port);
        } catch (IOException e) {
            throw new RuntimeException("SIP 数据平面监听失败: " + port, e);
        }
        return this;
    }

    /**
     * 停止数据平面，关闭全部通道与监听。
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        for (DataChannel channel : channels.values()) {
            channel.close();
        }
        channels.clear();
        log.info("SIP 数据平面已停止");
    }

    /**
     * 判断数据平面是否运行中。
     *
     * @return true 表示运行中
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 关闭指定通道。
     *
     * @param channelId 通道标识
     */
    public void closeChannel(String channelId) {
        DataChannel channel = channels.remove(channelId);
        if (channel != null) {
            channel.close();
        }
    }

    /**
     * 接受连接循环，为每个连接建立处理器。
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ThreadUtils.newThread(() -> handleConnection(socket), "sip-data-conn").start();
            } catch (IOException e) {
                if (running) {
                    log.debug("SIP 数据平面接受连接异常: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 处理一条数据连接：读取首行握手（含签名校验），随后裸字节流桥接。
     *
     * @param socket 数据连接
     */
    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            String handshake = readLine(socket);
            if (handshake == null || handshake.isEmpty()) {
                socket.close();
                return;
            }
            String[] parts = handshake.split("\\|", 3);
            if (parts.length < 3) {
                socket.close();
                return;
            }
            String channelId = parts[0];
            String role = parts[1];
            String signature = parts[2];
            // 校验签名：HMAC-SHA256(token, channelId + role)
            String expected = HMacUtils.hmacSha256Hex(token, channelId + role);
            if (!expected.equals(signature)) {
                log.warn("SIP 数据平面签名校验失败: channelId={}, role={}", channelId, role);
                socket.close();
                return;
            }
            DataChannel channel = channels.get(channelId);
            if (channel == null) {
                log.debug("SIP 数据平面未知 channelId: {}", channelId);
                socket.close();
                return;
            }
            socket.setSoTimeout(0);
            if (ROLE_VISITOR.equals(role)) {
                channel.bindVisitor(socket);
            } else if (ROLE_PROVIDER.equals(role)) {
                channel.bindProvider(socket);
            } else {
                socket.close();
            }
        } catch (IOException e) {
            log.debug("SIP 数据平面连接处理异常: {}", e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 创建并注册通道（由 SipServer 在隧道建立时调用）。
     *
     * @param channelId 通道标识
     */
    void createChannel(String channelId) {
        channels.computeIfAbsent(channelId, id -> new DataChannel(id, () -> {
            DataChannel removed = channels.remove(id);
            if (removed != null && closeHandler != null) {
                closeHandler.accept(id, "closed");
            }
        }));
    }

    /**
     * 读取首行握手数据。
     *
     * @param socket 数据连接
     * @return 首行内容
     * @throws IOException IO 异常
     */
    private String readLine(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            buffer.write(b);
        }
        if (buffer.size() == 0 && b == -1) {
            throw new EOFException("connection closed");
        }
        return buffer.toString("UTF-8").trim();
    }

    /**
     * 单条隧道的数据桥接器：负责访问方与提供方两条数据连接的裸字节流双向转发。
     *
     * @since 4.0.0.42
     */
    private static final class DataChannel {

        /**
         * 通道标识
         */
        private final String channelId;

        /**
         * 关闭回调
         */
        private final Runnable onClose;

        /**
         * 访问方数据连接
         */
        private volatile Socket visitor;

        /**
         * 提供方数据连接
         */
        private volatile Socket provider;

        /**
         * 是否已关闭
         */
        private volatile boolean closed;

        /**
         * 创建通道桥接器。
         *
         * @param channelId 通道标识
         * @param onClose   关闭回调
         */
        private DataChannel(String channelId, Runnable onClose) {
            this.channelId = channelId;
            this.onClose = onClose;
        }

        /**
         * 绑定访问方连接，与提供方裸字节流桥接。
         *
         * @param socket 访问方数据连接
         */
        void bindVisitor(Socket socket) {
            this.visitor = socket;
            if (provider != null) {
                bridge(socket, provider, ROLE_VISITOR);
            }
        }

        /**
         * 绑定提供方连接，与访问方裸字节流桥接。
         *
         * @param socket 提供方数据连接
         */
        void bindProvider(Socket socket) {
            this.provider = socket;
            if (visitor != null) {
                bridge(socket, visitor, ROLE_PROVIDER);
            }
        }

        /**
         * 为一条数据连接启动读取线程，将其裸字节流转发到对端连接（与 TcpProxyServer 相同模式）。
         *
         * @param source 来源连接
         * @param target 目标连接
         * @param role   来源角色
         */
        private void bridge(Socket source, Socket target, String role) {
            ThreadUtils.newThread(() -> {
                try {
                    InputStream in = source.getInputStream();
                    OutputStream out = target.getOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (!closed && (n = in.read(buf)) != -1) {
                        synchronized (out) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    close();
                }
            }, "sip-data-bridge-" + channelId + "-" + role).start();
        }

        /**
         * 关闭通道两端连接。
         */
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeQuietly(visitor);
            closeQuietly(provider);
            if (onClose != null) {
                onClose.run();
            }
        }

        /**
         * 静默关闭连接。
         *
         * @param socket 连接
         */
        private void closeQuietly(Socket socket) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
