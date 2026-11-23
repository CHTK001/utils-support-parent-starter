package com.chua.common.support.network.frp;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * FRP 端口映射：将本机端口映射到对端暴露的目标服务。
 *
 * <p>实现"内网穿透"的访问方侧：在本地监听一个端口，所有到达该端口的 TCP 连接
 * 都会通过 FRP 隧道转发到远端服务提供方暴露的目标。访问 {@code localhost:port}
 * 即相当于访问对端电脑上的本地服务。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * FrpClient client = FrpClient.tcp("tcp://127.0.0.1:19470").token("xxx");
 * // 本地 18080 -> 对端 "web" 目标的 8080 服务
 * new FrpTunnelPort(client, "web", 8080, 18080).start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class FrpTunnelPort {

    /**
     * 底层 FRP 客户端
     */
    private final FrpClient client;

    /**
     * 目标地址
     */
    private final String targetHost;

    /**
     * 目标端口
     */
    private final int targetPort;

    /**
     * 本地监听端口
     */
    private final int localPort;

    /**
     * 本地监听地址
     */
    private final String localHost;

    /**
     * 本地监听 Socket
     */
    private ServerSocket serverSocket;

    /**
     * 接受连接线程
     */
    private Thread acceptThread;

    /**
     * 是否正在运行
     */
    private volatile boolean running;

    /**
     * 默认建隧重试间隔（毫秒）
     */
    private static final long DEFAULT_OPEN_RETRY_INTERVAL_MS = 200L;

    /**
     * 建隧失败后的额外重试次数（0 = 不重试，保持既有行为）
     */
    private int openTunnelRetries = 0;

    /**
     * 建隧重试间隔（毫秒）
     */
    private long openTunnelRetryIntervalMs = DEFAULT_OPEN_RETRY_INTERVAL_MS;

    /**
     * 创建端口映射代理。
     *
     * @param client     底层 FRP 客户端
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localPort  本地监听端口
     */
    public FrpTunnelPort(FrpClient client, String targetHost, int targetPort, int localPort) {
        this(client, targetHost, targetPort, "127.0.0.1", localPort);
    }

    /**
     * 创建端口映射代理，指定监听地址。
     *
     * @param client     底层 FRP 客户端
     * @param targetHost 目标地址
     * @param targetPort 目标端口
     * @param localHost  本地监听地址
     * @param localPort  本地监听端口
     */
    public FrpTunnelPort(FrpClient client, String targetHost, int targetPort, String localHost, int localPort) {
        this.client = client;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.localHost = localHost;
        this.localPort = localPort;
    }

    /**
     * 设置建隧失败后的额外重试次数。
     *
     * <p>用于吸收突发建隧、服务端瞬时繁忙、提供方短暂离线等瞬态窗口：
     * 重试期间到达的本地连接保持打开（数据停留在内核接收缓冲），全部尝试失败才关闭。
     * 默认 0（不重试，保持既有行为）。</p>
     *
     * @param retries 额外重试次数，&lt;0 时按 0 处理
     * @return 当前代理实例，支持链式调用
     */
    public FrpTunnelPort openTunnelRetries(int retries) {
        this.openTunnelRetries = Math.max(0, retries);
        return this;
    }

    /**
     * 设置建隧重试间隔（毫秒）。
     *
     * @param intervalMs 重试间隔，&lt;=0 时回退默认值 {@value #DEFAULT_OPEN_RETRY_INTERVAL_MS}ms
     * @return 当前代理实例，支持链式调用
     */
    public FrpTunnelPort openTunnelRetryIntervalMs(long intervalMs) {
        this.openTunnelRetryIntervalMs = intervalMs > 0 ? intervalMs : DEFAULT_OPEN_RETRY_INTERVAL_MS;
        return this;
    }

    /**
     * 启动端口映射代理：连接 FrpServer 并在本地端口监听。
     *
     * @return 当前代理实例，支持链式调用
     */
    public FrpTunnelPort start() {
        if (running) {
            return this;
        }
        client.connect().map(targetHost, targetPort, localPort);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(localHost, localPort), 50);
            running = true;
            Thread accept = ThreadUtils.newVirtualThread("frp-tunnel-port-" + localPort, this::acceptLoop);
            this.acceptThread = accept;
            accept.start();
            log.info("FRP 端口已映射: {}:{} -> {}:{}", localHost, localPort, targetHost, targetPort);
        } catch (IOException e) {
            throw new RuntimeException("FRP 端口监听失败: " + localPort, e);
        }
        return this;
    }

    /**
     * 停止端口映射代理。
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.debug("[frp] 关闭 ServerSocket 失败，已忽略: {}", e.getMessage());
            }
        }
        client.removeMapping(targetHost, targetPort, localPort);
        client.close();
    }

    /**
     * 接受连接循环。
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ThreadUtils.startVirtualThread("frp-tunnel-fwd-" + localPort, () -> forward(socket));
            } catch (IOException e) {
                if (running) {
                    log.debug("FRP 端口接受连接异常: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 将本地 TCP 连接转发到隧道服务。
     *
     * @param socket 本地连接
     */
    private void forward(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
        } catch (Exception e) {
            log.debug("[frp] 设置 TCP_NODELAY 失败，已忽略: {}", e.getMessage());
        }
        FrpTunnelSession session = openTunnelWithRetry();
        if (session == null) {
            closeQuietly(socket);
            return;
        }
        log.debug("FRP forward: channel={}, local socket connected", session.getChannelId());
        session.onBytes((buf, off, len) -> writeSocket(socket, buf, off, len));
        session.onClose(channelId -> closeQuietly(socket));
        readSocket(socket, session);
    }

    /**
     * 开启隧道，失败时按 {@link #openTunnelRetries(int)} 配置重试。
     *
     * <p>用于吸收突发建隧、服务端瞬时繁忙、提供方短暂离线等瞬态窗口：
     * 重试期间本地连接保持打开，重试成功后照常转发；全部尝试失败才关闭本地连接。
     * 注意：若某次尝试实际已在服务端建隧成功、仅因客户端预算超时未收到回执，
     * 该通道会成为孤儿通道，由服务端在任一端断开时回收。</p>
     *
     * @return 隧道会话，全部尝试失败返回 null
     */
    private FrpTunnelSession openTunnelWithRetry() {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= openTunnelRetries; attempt++) {
            if (attempt > 0) {
                client.incrementTunnelOpenRetry();
                log.warn("FRP 隧道开启失败，第 {}/{} 次重试: target={}:{}, reason={}",
                        attempt, openTunnelRetries, targetHost, targetPort,
                        last == null ? "" : last.getMessage());
                if (!running) {
                    return null;
                }
                ThreadUtils.sleep(openTunnelRetryIntervalMs);
            }
            try {
                return client.openTunnel(targetHost, targetPort);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        log.warn("FRP 隧道开启失败（含 {} 次重试），关闭本地连接: target={}:{}, reason={}",
                openTunnelRetries, targetHost, targetPort, last == null ? "" : last.getMessage());
        return null;
    }

    /**
     * 读取本地连接数据并写入隧道。
     *
     * @param socket  本地连接
     * @param session 隧道会话
     */
    private void readSocket(Socket socket, FrpTunnelSession session) {
        try (InputStream in = socket.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (!session.isOpen()) {
                    break;
                }
                session.sendBytes(buffer, 0, read);
            }
            log.debug("[frp] 本地连接读循环退出(EOF): channel={}", session.getChannelId());
        } catch (IOException e) {
            log.debug("[frp] IO 操作失败，已忽略: {}", e.getMessage());
        } finally {
            session.close();
        }
    }

    /**
     * 将隧道数据切片写入本地连接（零拷贝：直接写 {@code data[off, off+len)}）。
     *
     * @param socket 本地连接
     * @param data   字节数据
     * @param off    起始偏移
     * @param len    数据长度
     */
    private void writeSocket(Socket socket, byte[] data, int off, int len) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(data, off, len);
            out.flush();
        } catch (IOException e) {
            log.debug("[frp] IO 操作失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * 静默关闭连接。
     *
     * @param socket 连接
     */
    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("[frp] IO 操作失败，已忽略: {}", e.getMessage());
        }
    }
}