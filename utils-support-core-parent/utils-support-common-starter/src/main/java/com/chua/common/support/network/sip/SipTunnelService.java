package com.chua.common.support.network.sip;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * 服务侧隧道代理，将本机的 TCP 服务暴露为 SIP 隧道服务，供对端访问。
 *
 * <p>实现"内网穿透"的服务端侧：本机服务（如 {@code 127.0.0.1:8080}）无需对外网开放，
 * 只要本代理与 SipServer 建立长连接并注册服务，对端即可通过隧道访问该服务。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * SipClient client = SipClient.tcp("tcp://127.0.0.1:19460");
 * new SipTunnelService(client, "web", "127.0.0.1", 8080).start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipTunnelService {

    /**
     * 底层 SIP 客户端
     */
    private final SipClient client;

    /**
     * 服务名称
     */
    private final String serviceName;

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
     * 创建服务侧隧道代理。
     *
     * @param client      底层 SIP 客户端
     * @param serviceName 服务名称
     * @param localHost   本地服务地址
     * @param localPort   本地服务端口
     */
    public SipTunnelService(SipClient client, String serviceName, String localHost, int localPort) {
        this.client = client;
        this.serviceName = serviceName;
        this.localHost = localHost;
        this.localPort = localPort;
    }

    /**
     * 启动服务侧隧道代理：注册服务并监听隧道开启请求。
     *
     * @return 当前代理实例，支持链式调用
     */
    public SipTunnelService start() {
        if (started) {
            return this;
        }
        started = true;
        client.onTunnelOpen((channelId, name) -> {
            if (serviceName.equals(name)) {
                bridgeToLocal(client.tunnelSession(channelId));
            }
        });
        client.connect().register(localAddress(), 0).registerTunnel(serviceName);
        log.info("SIP 隧道服务已暴露: [{}] -> {}:{}", serviceName, localHost, localPort);
        return this;
    }

    /**
     * 关闭服务侧隧道代理。
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
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
     * 将隧道会话桥接到本地服务。
     *
     * @param session 隧道会话
     */
    private void bridgeToLocal(SipTunnelSession session) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(localHost, localPort), 5000);
            session.onBytes(data -> writeSocket(socket, data));
            session.onClose(channelId -> closeQuietly(socket));
            readSocket(socket, session);
        } catch (IOException e) {
            log.error("SIP 隧道连接本地服务失败: {}:{}", localHost, localPort, e);
            session.close();
        }
    }

    /**
     * 读取本地服务数据并写入隧道。
     *
     * @param socket  本地服务连接
     * @param session 隧道会话
     */
    private void readSocket(Socket socket, SipTunnelSession session) {
        ThreadUtils.newThread(() -> {
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
        }, "sip-tunnel-service-" + serviceName).start();
    }

    /**
     * 将隧道数据写入本地服务。
     *
     * @param socket 本地服务连接
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
