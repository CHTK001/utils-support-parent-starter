package com.chua.common.support.network.syslog;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Syslog 日志收集服务端，支持 UDP（RFC 5426）与 TCP（RFC 6587 逐行分帧）两种传输。
 *
 * <p>收到报文后解析为 {@link SyslogMessage}（自动识别 RFC 5424 / RFC 3164），
 * 逐个回调 {@link SyslogListener}。监听器在 I/O 线程内同步执行，应保持轻量。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * SyslogServer server = new SyslogServer(SyslogConfig.builder()
 *         .port(5514)
 *         .transport(SyslogTransport.UDP)
 *         .build());
 * server.addListener(msg -> System.out.println(msg.getSeverity() + " " + msg.getMessage()));
 * server.start();
 * ...
 * server.stop();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class SyslogServer extends AbstractServer {

    /**
     * UDP 接收线程名。
     */
    private static final String UDP_RECEIVE_THREAD_NAME = "syslog-udp-receive";

    /**
     * TCP accept 线程名。
     */
    private static final String TCP_ACCEPT_THREAD_NAME = "syslog-tcp-accept";

    /**
     * TCP 读线程名前缀。
     */
    private static final String TCP_READ_THREAD_PREFIX = "syslog-tcp-read-";

    /**
     * 服务端协议标识（ServerSetting.protocol）。
     */
    private static final String PROTOCOL_NAME = "syslog";

    /**
     * 服务端配置。
     */
    private final SyslogConfig config;

    /**
     * 消息监听器列表。
     */
    private final List<SyslogListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 活跃 TCP 连接（停止时统一关闭以解除读阻塞）。
     */
    private final List<Socket> tcpConnections = new CopyOnWriteArrayList<>();

    /**
     * TCP 连接序号（线程命名用）。
     */
    private volatile long connectionSequence;

    /**
     * UDP 套接字。
     */
    private volatile DatagramSocket udpSocket;

    /**
     * TCP 监听套接字。
     */
    private volatile ServerSocket tcpServerSocket;

    /**
     * 创建使用默认配置（0.0.0.0:514，UDP）的 Syslog 服务端。
     */
    public SyslogServer() {
        this(SyslogConfig.defaults());
    }

    /**
     * 创建使用指定配置的 Syslog 服务端。
     *
     * @param config Syslog 配置
     */
    public SyslogServer(SyslogConfig config) {
        super(buildServerSetting(config));
        this.config = config;
    }

    /**
     * 创建使用外部服务端配置的 Syslog 服务端（供 SPI 装配按类型实例化）。
     *
     * @param setting 服务端配置，host 与 port 生效
     */
    public SyslogServer(ServerSetting setting) {
        this(SyslogConfig.builder()
                .bindHost(setting.getHost())
                .port(setting.getPort())
                .build());
    }

    /**
     * 由 Syslog 配置构建通用服务端配置。
     *
     * @param syslogConfig Syslog 配置
     * @return 通用服务端配置
     */
    private static ServerSetting buildServerSetting(SyslogConfig syslogConfig) {
        var setting = ServerSetting.defaults();
        setting.setHost(syslogConfig.getBindHost());
        setting.setPort(syslogConfig.getPort());
        setting.setProtocol(PROTOCOL_NAME);
        return setting;
    }

    /**
     * 注册消息监听器。
     *
     * @param listener 监听器
     * @return 当前实例（链式调用）
     */
    public SyslogServer addListener(SyslogListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /**
     * 移除消息监听器。
     *
     * @param listener 监听器
     * @return 当前实例（链式调用）
     */
    public SyslogServer removeListener(SyslogListener listener) {
        listeners.remove(listener);
        return this;
    }

    /**
     * 启动服务端：按传输协议绑定 UDP 或 TCP 端口。
     */
    @Override
    protected void doStart() {
        try {
            if (config.getTransport() == SyslogTransport.UDP) {
                startUdp();
            } else {
                startTcp();
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Syslog 服务端启动失败: " + config.getBindHost() + ":" + config.getPort(), e);
        }
        this.running = true;
        log.info("Syslog 服务端已启动: {}:{} ({})", config.getBindHost(), getPort(), config.getTransport());
    }

    /**
     * 绑定 UDP 端口并启动接收线程。
     *
     * @throws IOException 绑定失败
     */
    private void startUdp() throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.getBindHost(), config.getPort()));
        this.udpSocket = socket;
        setting.setPort(socket.getLocalPort());
        Thread.ofPlatform().daemon().name(UDP_RECEIVE_THREAD_NAME).start(this::udpReceiveLoop);
    }

    /**
     * 绑定 TCP 端口并启动 accept 线程。
     *
     * @throws IOException 绑定失败
     */
    private void startTcp() throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getBindHost(), config.getPort()),
                setting.resolveBacklog(config.getBacklog()));
        this.tcpServerSocket = serverSocket;
        setting.setPort(serverSocket.getLocalPort());
        Thread.ofPlatform().daemon().name(TCP_ACCEPT_THREAD_NAME).start(this::tcpAcceptLoop);
    }

    /**
     * UDP 接收循环：逐报文解析并分发。
     */
    private void udpReceiveLoop() {
        DatagramSocket socket = this.udpSocket;
        byte[] buffer = new byte[config.getMaxMessageLength()];
        while (socket != null && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketException e) {
                // 服务端停止时主动关闭套接字，正常退出
                break;
            } catch (IOException e) {
                if (socket.isClosed()) {
                    break;
                }
                log.warn("Syslog UDP 接收异常: {}", e.getMessage());
                continue;
            }
            String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            dispatch(raw);
        }
    }

    /**
     * TCP accept 循环：每连接一个读线程。
     */
    private void tcpAcceptLoop() {
        ServerSocket serverSocket = this.tcpServerSocket;
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                tcpConnections.add(socket);
                Thread.ofPlatform().daemon()
                        .name(TCP_READ_THREAD_PREFIX + ++connectionSequence)
                        .start(() -> tcpReadLoop(socket));
            } catch (SocketException e) {
                // 服务端停止时主动关闭监听套接字，正常退出
                break;
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
                log.warn("Syslog TCP accept 异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 单条 TCP 连接的逐行读取循环。
     *
     * @param socket 已接入的连接
     */
    private void tcpReadLoop(Socket socket) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            // 对端断开或服务端停止，连接结束
        } finally {
            tcpConnections.remove(socket);
            try {
                socket.close();
            } catch (IOException ignore) {
                // 关闭失败可忽略
            }
        }
    }

    /**
     * 解析并分发一条报文；单个监听器异常不影响其它监听器。
     *
     * @param raw 原始报文
     */
    private void dispatch(String raw) {
        SyslogMessage message = SyslogMessage.parse(raw);
        for (SyslogListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                log.warn("Syslog 监听器处理失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 停止接收：关闭监听/接收套接字，使 receive 与 accept 立即解除阻塞。
     */
    @Override
    protected void doStopAccepting() {
        closeQuietly(udpSocket);
        closeQuietly(tcpServerSocket);
    }

    /**
     * 停止服务端：关闭所有 TCP 连接并清理引用。
     */
    @Override
    protected void doStop() {
        for (Socket socket : tcpConnections) {
            try {
                socket.close();
            } catch (IOException ignore) {
                // 关闭失败可忽略
            }
        }
        tcpConnections.clear();
        udpSocket = null;
        tcpServerSocket = null;
        log.info("Syslog 服务端已停止");
    }

    /**
     * 关闭 UDP 套接字。
     *
     * @param socket UDP 套接字，可为 null
     */
    private void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * 关闭 TCP 监听套接字。
     *
     * @param socket TCP 监听套接字，可为 null
     */
    private void closeQuietly(ServerSocket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignore) {
                // 关闭失败可忽略
            }
        }
    }

    /**
     * 获取实际监听端口（支持动态端口）。
     *
     * @return 端口号
     */
    @Override
    public int getPort() {
        DatagramSocket socket = this.udpSocket;
        if (socket != null && !socket.isClosed()) {
            return socket.getLocalPort();
        }
        ServerSocket serverSocket = this.tcpServerSocket;
        if (serverSocket != null && !serverSocket.isClosed()) {
            return serverSocket.getLocalPort();
        }
        return config.getPort();
    }

    /**
     * 获取协议类型：UDP 传输为 UDP，TCP 传输为 TCP。
     *
     * <p>父类构造器会先回调本方法（装配 {@code ServerFilterManager}），此时 {@code config}
     * 尚未赋值，按缺省传输 UDP 处理；构造完成后返回真实传输协议。</p>
     *
     * @return 协议类型
     */
    @Override
    public ProtocolType getProtocolType() {
        SyslogConfig syslogConfig = this.config;
        if (syslogConfig == null) {
            return ProtocolType.UDP;
        }
        return syslogConfig.getTransport() == SyslogTransport.TCP ? ProtocolType.TCP : ProtocolType.UDP;
    }
}
