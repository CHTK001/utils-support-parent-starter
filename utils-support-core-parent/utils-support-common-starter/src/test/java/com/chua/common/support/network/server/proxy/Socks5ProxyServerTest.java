// [P3C A20 豁免] A20/A30/A31: SOCKS5 协议码位与测试断言含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTestSupport.EchoBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static com.chua.common.support.network.server.proxy.ProxyTestSupport.closedPort;
import static com.chua.common.support.network.server.proxy.ProxyTestSupport.readFully;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Socks5ProxyServer} 的真实链路回归。
 *
 * <p>SOCKS5 是<b>自己实现的协议</b>（握手 → 认证 → CONNECT → 双向转发），
 * 之前零覆盖。这里用真实 socket 走完整协议，覆盖三条最容易出错的路径：</p>
 * <ol>
 *   <li>无认证 CONNECT 后隧道可用（字节双向通）</li>
 *   <li>开启认证时，错误口令必须被拒（不能放行）</li>
 *   <li>目标端口无人监听时，CONNECT 必须回失败状态码而不是假装成功</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.52
 */
class Socks5ProxyServerTest {

    /**
     * SOCKS5 版本号。
     */
    private static final int VERSION = 0x05;

    /**
     * 无认证方法。
     */
    private static final int METHOD_NO_AUTH = 0x00;

    /**
     * 用户名口令认证方法。
     */
    private static final int METHOD_USER_PASS = 0x02;

    /**
     * CONNECT 命令。
     */
    private static final int CMD_CONNECT = 0x01;

    /**
     * IPv4 地址类型。
     */
    private static final int ATYP_IPV4 = 0x01;

    /**
     * 成功状态码。
     */
    private static final int REP_SUCCESS = 0x00;

    /**
     * 代理监听配置。
     *
     * @return 配置
     */
    private static ServerSetting setting() {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 完成 SOCKS5 协商（可选用户名口令），并发出 CONNECT。
     *
     * @param in       输入流
     * @param out      输出流
     * @param port     目标端口（目标固定 127.0.0.1）
     * @param user     用户名，null 表示无认证
     * @param password 口令
     * @return CONNECT 应答状态码
     * @throws IOException 协议交互失败
     */
    private static int handshakeAndConnect(InputStream in, OutputStream out, int port,
                                           String user, String password) throws IOException {
        int method = user == null ? METHOD_NO_AUTH : METHOD_USER_PASS;
        out.write(new byte[]{(byte) VERSION, 1, (byte) method});
        out.flush();

        byte[] selected = new byte[2];
        readFully(in, selected, 2);
        assertEquals(VERSION, selected[0] & 0xFF, "服务端返回了非 SOCKS5 版本");
        assertEquals(method, selected[1] & 0xFF, "服务端选择的方法与预期不符");

        if (user != null) {
            byte[] userBytes = user.getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] auth = new byte[3 + userBytes.length + passBytes.length];
            auth[0] = 0x01;
            auth[1] = (byte) userBytes.length;
            System.arraycopy(userBytes, 0, auth, 2, userBytes.length);
            auth[2 + userBytes.length] = (byte) passBytes.length;
            System.arraycopy(passBytes, 0, auth, 3 + userBytes.length, passBytes.length);
            out.write(auth);
            out.flush();
            byte[] authReply = new byte[2];
            readFully(in, authReply, 2);
            if (authReply[1] != 0x00) {
                return -1;
            }
        }

        byte[] connect = new byte[]{
                (byte) VERSION, (byte) CMD_CONNECT, 0x00, (byte) ATYP_IPV4,
                127, 0, 0, 1,
                (byte) ((port >> 8) & 0xFF), (byte) (port & 0xFF)};
        out.write(connect);
        out.flush();

        byte[] reply = new byte[4];
        readFully(in, reply, 4);
        int atyp = reply[3] & 0xFF;
        int rest = atyp == ATYP_IPV4 ? 6 : 18;
        byte[] tail = new byte[rest];
        readFully(in, tail, rest);
        return reply[1] & 0xFF;
    }

    /**
     * 无认证 CONNECT 后隧道可双向传输。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("SOCKS5：无认证 CONNECT 后隧道双向可用")
    void tunnelsWithoutAuth() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            Socks5ProxyServer proxy = new Socks5ProxyServer(setting());
            proxy.start();
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                client.setSoTimeout(10000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                int status = handshakeAndConnect(in, out, backend.port(), null, null);
                assertEquals(REP_SUCCESS, status, "CONNECT 未成功，状态码=" + status);

                String payload = "socks-echo";
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] buffer = new byte[64];
                int read = readFully(in, buffer, payload.length());
                assertEquals(payload, new String(buffer, 0, read, StandardCharsets.UTF_8),
                        "经 SOCKS5 隧道回传的内容不一致");
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 开启认证时，错误口令必须被拒绝。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("SOCKS5：错误口令被拒绝")
    void rejectsWrongPassword() throws Exception {
        Socks5ProxyServer proxy = new Socks5ProxyServer(setting(), "u1", "right-pass");
        proxy.start();
        try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
            client.setSoTimeout(10000);
            int status = handshakeAndConnect(client.getInputStream(), client.getOutputStream(),
                    closedPort(), "u1", "wrong-pass");
            assertNotEquals(REP_SUCCESS, status, "错误口令竟然通过了认证");
        } finally {
            proxy.stop();
        }
    }

    /**
     * 目标端口无人监听时，CONNECT 必须回失败状态码。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("SOCKS5：目标不可达时 CONNECT 返回失败")
    void reportsFailureWhenTargetDown() throws Exception {
        Socks5ProxyServer proxy = new Socks5ProxyServer(setting());
        proxy.start();
        try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
            client.setSoTimeout(10000);
            int status = handshakeAndConnect(client.getInputStream(), client.getOutputStream(),
                    closedPort(), null, null);
            assertNotEquals(REP_SUCCESS, status,
                    "目标端口未监听，CONNECT 却返回成功——客户端会以为隧道已建立");
            assertTrue(status != 0, "状态码应是非 0 的失败码");
        } finally {
            proxy.stop();
        }
    }
}
