package com.chua.common.support.network.sip;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

/**
 * 端口侧隧道代理，将本机端口映射到对端隧道服务。
 *
 * <p>实现"内网穿透"的访问方侧：在本地监听一个端口，所有到达该端口的 TCP 连接
 * 都会通过隧道转发到远端服务提供方暴露的服务。访问 {@code localhost:port}
 * 即相当于访问对端电脑上的本地服务。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * SipClient client = SipClient.kcp("kcp://127.0.0.1:19461");
 * // 本地 8080 -> 对端 "web" 服务
 * new SipTunnelPort(client, "web", 8080).start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipTunnelPort {

    /**
     * 底层 SIP 客户端
     */
    private final SipClient client;

    /**
     * 目标隧道服务名称
     */
    private final String serviceName;

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
     * 创建端口侧隧道代理。
     *
     * @param client      底层 SIP 客户端
     * @param serviceName 目标隧道服务名称
     * @param localPort   本地监听端口
     */
    public SipTunnelPort(SipClient client, String serviceName, int localPort) {
        this(client, serviceName, "127.0.0.1", localPort);
    }

    /**
     * 创建端口侧隧道代理，指定监听地址。
     *
     * @param client      底层 SIP 客户端
     * @param serviceName 目标隧道服务名称
     * @param localHost   本地监听地址
     * @param localPort   本地监听端口
     */
    public SipTunnelPort(SipClient client, String serviceName, String localHost, int localPort) {
        this.client = client;
        this.serviceName = serviceName;
        this.localHost = localHost;
        this.localPort = localPort;
    }

    /**
     * 启动端口侧隧道代理：连接 SipServer 并在本地端口监听。
     *
     * @return 当前代理实例，支持链式调用
     */
    public SipTunnelPort start() {
        if (running) {
            return this;
        }
        client.connect().register(localAddress(), 0);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(localHost, localPort), 50);
            running = true;
            acceptThread = ThreadUtils.newThread(this::acceptLoop, "sip-tunnel-port-" + localPort);
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("SIP 隧道端口已映射: {}:{} -> [{}]", localHost, localPort, serviceName);
        } catch (IOException e) {
            throw new RuntimeException("SIP 隧道端口监听失败: " + localPort, e);
        }
        return this;
    }

    /**
     * 停止端口侧隧道代理。
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
        client.close();
    }

    /**
     * 获取本机可达地址。
     *
     * @return 本机 IP 地址
     */
    private String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * 接受连接循环。
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ThreadUtils.newThread(() -> forward(socket), "sip-tunnel-fwd-" + localPort).start();
            } catch (IOException e) {
                if (running) {
                    log.debug("SIP 隧道端口接受连接异常: {}", e.getMessage());
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
        SipTunnelSession session;
        try {
            session = client.openTunnel(serviceName);
        } catch (RuntimeException e) {
            log.warn("SIP 隧道开启失败: {}", e.getMessage());
            closeQuietly(socket);
            return;
        }
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
    private void readSocket(Socket socket, SipTunnelSession session) {
        try (InputStream in = socket.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (!session.isOpen()) {
                    break;
                }
                session.sendBytes(Arrays.copyOf(buffer, read));
            }
        } catch (IOException ignored) {
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
        } catch (IOException ignored) {
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
        } catch (IOException ignored) {
        }
    }
}
