package com.chua.common.support.network.frp;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

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
        FrpTunnelSession session;
        try {
            session = client.openTunnel(targetHost, targetPort);
        } catch (RuntimeException e) {
            log.warn("FRP 隧道开启失败: {}", e.getMessage(), e);
            closeQuietly(socket);
            return;
        }
        log.debug("FRP forward: channel={}, local socket connected", session.getChannelId());
        session.onBytes(data -> writeSocket(socket, data));
        session.onClose(channelId -> closeQuietly(socket));
        readSocket(socket, session);
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
                session.sendBytes(Arrays.copyOf(buffer, read));
            }
        } catch (IOException e) {
            log.debug("[frp] IO 操作失败，已忽略: {}", e.getMessage());
        } finally {
            session.close();
        }
    }

    /**
     * 将隧道数据写入本地连接。
     *
     * @param socket 本地连接
     * @param data   字节数据
     */
    private void writeSocket(Socket socket, byte[] data) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(data);
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