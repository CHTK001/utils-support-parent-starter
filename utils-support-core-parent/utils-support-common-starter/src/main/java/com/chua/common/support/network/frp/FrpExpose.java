package com.chua.common.support.network.frp;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * FRP 暴露服务：将本机的 TCP 服务暴露为显式映射目标，供对端访问。
 *
 * <p>实现"内网穿透"的服务端侧：本机服务（如 {@code 127.0.0.1:8080}）无需对外网开放，
 * 只要本代理与 FrpServer 建立长连接并注册 {@code targetHost:targetPort} 暴露规则，
 * 对端即可通过 FRP 隧道访问该服务。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * FrpClient client = FrpClient.tcp("tcp://127.0.0.1:19470").token("xxx");
 * // 本机 8080 以 "web" 服务的逻辑标识对外提供
 * new FrpExpose(client, "web", 8080, 8080).start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class FrpExpose {

    /**
     * 底层 FRP 客户端
     */
    private final FrpClient client;

    /**
     * 目标地址（对外逻辑标识）
     */
    private final String targetHost;

    /**
     * 目标端口（对外逻辑标识）
     */
    private final int targetPort;

    /**
     * 本地服务地址
     */
    private final String localHost;

    /**
     * 本地服务端口
     */
    private final int localPort;

    /**
     * 是否已启动
     */
    private volatile boolean started;

    /**
     * 创建暴露服务。
     *
     * @param client     底层 FRP 客户端
     * @param targetHost 目标地址（对外逻辑标识）
     * @param targetPort 目标端口（对外逻辑标识）
     * @param localPort  本地服务端口
     */
    public FrpExpose(FrpClient client, String targetHost, int targetPort, int localPort) {
        this(client, targetHost, targetPort, "127.0.0.1", localPort);
    }

    /**
     * 创建暴露服务，指定本地服务地址。
     *
     * @param client     底层 FRP 客户端
     * @param targetHost 目标地址（对外逻辑标识）
     * @param targetPort 目标端口（对外逻辑标识）
     * @param localHost  本地服务地址
     * @param localPort  本地服务端口
     */
    public FrpExpose(FrpClient client, String targetHost, int targetPort, String localHost, int localPort) {
        this.client = client;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.localHost = localHost;
        this.localPort = localPort;
    }

    /**
     * 启动暴露服务：连接服务器、注册暴露规则并监听隧道开启请求。
     *
     * @return 当前代理实例，支持链式调用
     */
    public FrpExpose start() {
        if (started) {
            return this;
        }
        started = true;
        client.onTunnelOpen((channelId, target) -> {
            // 异步桥接，避免阻塞信号读取线程
            ThreadUtils.startVirtualThread("frp-expose-bridge-" + targetHost, () -> {
                FrpTunnelSession session = client.tunnelSession(channelId);
                if (session != null) {
                    bridgeToLocal(session);
                }
            });
        });
        client.connect().expose(targetHost, targetPort, localPort);
        log.info("FRP 暴露服务已注册: [{}:{}] -> {}:{}", targetHost, targetPort, localHost, localPort);
        return this;
    }

    /**
     * 关闭暴露服务。
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        client.removeMapping(targetHost, targetPort, localPort);
        client.close();
    }

    /**
     * 将隧道会话桥接到本地服务。
     *
     * @param session 隧道会话
     */
    private void bridgeToLocal(FrpTunnelSession session) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(localHost, localPort), 5000);
            log.debug("FRP bridgeToLocal: channel={}, connected to {}:{}", session.getChannelId(),
                    localHost, localPort);
            session.onBytes((buf, off, len) -> writeSocket(socket, buf, off, len));
            session.onClose(channelId -> closeQuietly(socket));
            readSocket(socket, session);
        } catch (IOException e) {
            log.error("FRP 隧道连接本地服务失败: {}:{}", localHost, localPort, e);
            session.close();
        }
    }

    /**
     * 读取本地服务数据并写入隧道。
     *
     * @param socket  本地服务连接
     * @param session 隧道会话
     */
    private void readSocket(Socket socket, FrpTunnelSession session) {
        ThreadUtils.startVirtualThread("frp-expose-" + targetHost, () -> {
            try (InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (!session.isOpen()) {
                        break;
                    }
                    session.sendBytes(buffer, 0, read);
                }
                log.debug("FRP 本地服务读循环退出(EOF): channel={}", session.getChannelId());
            } catch (IOException e) {
                log.debug("[FRP] 隧道数据读取失败: {}", e.getMessage());
            } finally {
                session.close();
            }
        });
    }

    /**
     * 将隧道数据切片写入本地服务（零拷贝：直接写 {@code data[off, off+len)}）。
     *
     * @param socket 本地服务连接
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
            log.debug("[frp] 发送数据失败，已忽略: {}", e.getMessage());
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
            log.debug("[frp] 发送数据失败，已忽略: {}", e.getMessage());
        }
    }
}