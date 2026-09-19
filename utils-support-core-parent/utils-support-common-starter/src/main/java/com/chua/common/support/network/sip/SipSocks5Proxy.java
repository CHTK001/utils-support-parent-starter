package com.chua.common.support.network.sip;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.Socks5ProxyServer;
import com.chua.common.support.network.server.proxy.AbstractProxyServer;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * SIP SOCKS5 代理 — 让三方软件通过 SOCKS5 协议访问隧道服务。
 *
 * <p>三方软件（浏览器、SSH、数据库客户端、FTP 客户端等）只需配置一个 SOCKS5 代理地址，
 * 连接目标域名时自动路由到对应的 SIP 隧道服务，无需为每个服务单独开端口映射。</p>
 *
 * <p>路由规则：SOCKS5 请求的目标主机名即为隧道服务名称，端口为服务端口。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * SipClient client = SipClient.tcp("tcp://127.0.0.1:19460").token("xxx");
 * client.socks5(1080);  // 浏览器配 SOCKS5 localhost:1080，动态路由
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SipSocks5Proxy extends Socks5ProxyServer {

    /**
     * 关联的 SIP 客户端
     */
    private final SipClient client;

    /**
     * 创建 SIP SOCKS5 代理。
     *
     * @param setting 服务器配置
     * @param client  关联的 SIP 客户端
     */
    public SipSocks5Proxy(ServerSetting setting, SipClient client) {
        super(setting);
        this.client = client;
    }

    /**
     * 处理 SOCKS5 CONNECT 命令：通过 SIP 隧道连接目标服务。
     *
     * <p>目标主机名即为隧道服务名称，通过 {@link SipClient#openTunnel(String)} 建立隧道，
     * 然后将 SOCKS5 连接与隧道双向桥接。</p>
     *
     * @param clientSocket 客户端 Socket
     * @param out          输出流
     * @param target       目标地址（主机名 = 服务名，端口 = 服务端口）
     * @throws IOException IO 异常
     */
    @Override
    protected void handleConnect(Socket clientSocket, OutputStream out, InetSocketAddress target) throws IOException {
        var serviceName = target.getHostString();
        var servicePort = target.getPort();
        if (serviceName == null || serviceName.isBlank()) {
            writeReply(out, REP_HOST_UNREACHABLE, new InetSocketAddress(0));
            return;
        }
        log.debug("SIP SOCKS5 请求: {} -> {}:{}", serviceName, serviceName, servicePort);
        // 打开 SIP 隧道
        SipTunnelSession session;
        try {
            session = client.openTunnel(serviceName, 5000);
        } catch (Exception e) {
            log.warn("SIP SOCKS5 隧道开启失败: {} ({})", serviceName, e.getMessage());
            writeReply(out, REP_HOST_UNREACHABLE, new InetSocketAddress(0));
            return;
        }
        // 回复 SOCKS5 成功
        writeReply(out, REP_SUCCESS, new InetSocketAddress(0));
        // 双向桥接：SOCKS5 连接 <-> SIP 隧道
        var bridge = new Socks5TunnelBridge(clientSocket, session);
        bridge.start();
    }

    /**
     * SOCKS5 连接与 SIP 隧道双向桥接。
     *
     * <p>将 SOCKS5 客户端的 InputStream 与 SIP 隧道会话双向透传。</p>
     *
     * @author CH
     * @since 4.0.0.43
     */
    private static class Socks5TunnelBridge {

        /**
         * SOCKS5 客户端 Socket
         */
        private final Socket clientSocket;

        /**
         * SIP 隧道会话
         */
        private final SipTunnelSession session;

        /**
         * 创建桥接器。
         *
         * @param clientSocket 客户端 Socket
         * @param session      SIP 隧道会话
         */
        Socks5TunnelBridge(Socket clientSocket, SipTunnelSession session) {
            this.clientSocket = clientSocket;
            this.session = session;
        }

        /**
         * 启动双向桥接。
         */
        void start() {
            session.onBytes(data -> writeSocket(clientSocket, data));
            session.onClose(channelId -> closeQuietly(clientSocket));
            readSocket(clientSocket, session);
        }

        /**
         * 读取客户端数据并写入隧道。
         *
         * @param socket  SOCKS5 客户端 Socket
         * @param session 隧道会话
         */
        private void readSocket(Socket socket, SipTunnelSession session) {
            ThreadUtils.startVirtualThread("sip-socks5-read-" + session.getChannelId(), () -> {
                try (var in = socket.getInputStream()) {
                    var buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (!session.isOpen()) {
                            break;
                        }
                        session.sendBytes(java.util.Arrays.copyOf(buffer, read));
                    }
                } catch (IOException ignored) {
                } finally {
                    session.close();
                }
            });
        }

        /**
         * 将隧道数据写入客户端 Socket。
         *
         * @param socket 客户端 Socket
         * @param data   字节数据
         */
        private void writeSocket(Socket socket, byte[] data) {
            try {
                var out = socket.getOutputStream();
                out.write(data);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        /**
         * 静默关闭 Socket。
         *
         * @param socket 客户端 Socket
         */
        private void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
