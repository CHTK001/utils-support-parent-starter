// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTestSupport.HttpBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static com.chua.common.support.network.server.proxy.ProxyTestSupport.HTTP_BODY;
import static com.chua.common.support.network.server.proxy.ProxyTestSupport.readToEof;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AioHttpProxyServer} 的真实链路回归。
 *
 * <p>它是<b>正向代理</b>（支持绝对 URI 与 CONNECT 隧道），与 {@code HttpProxyServer}
 * 的反向语义完全不同：目标地址来自请求本身而不是配置。这里验最常用的绝对 URI 转发。</p>
 *
 * <p>CONNECT 隧道<b>尚未覆盖</b>——需要后端扮演真代理，普通 HTTP 后端验不出来。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class AioHttpProxyServerTest {

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
        setting.setEventLoops(2);
        return setting;
    }

    /**
     * 正向代理：客户端用绝对 URI 指定目标，代理据此连后端并回传正文。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("AioHttpProxyServer：绝对 URI 转发到目标并回传正文")
    void forwardsAbsoluteUri() throws Exception {
        try (HttpBackend backend = new HttpBackend()) {
            AioHttpProxyServer proxy = new AioHttpProxyServer(setting());
            proxy.start();
            try {
                int proxyPort = proxy.getPort();
                assertTrue(proxyPort > 0, "代理未绑定端口");
                String target = "127.0.0.1:" + backend.port();
                String request = "GET http://" + target + "/hello HTTP/1.1\r\n"
                        + "Host: " + target + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
                try (Socket client = new Socket("127.0.0.1", proxyPort)) {
                    client.setSoTimeout(15000);
                    OutputStream out = client.getOutputStream();
                    out.write(request.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    String response = readToEof(client.getInputStream());
                    assertNotNull(response, "未收到任何响应");
                    assertTrue(response.contains("200"),
                            "未拿到 200，实际响应：" + response.substring(0, Math.min(160, response.length())));
                    assertTrue(response.contains(HTTP_BODY), "响应正文未正确回传");
                }
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * CONNECT 隧道：建立后应能双向透传任意字节。
     *
     * <p>这是 HTTPS 经正向代理的必经路径，也是 AioHttpProxyServer 里最容易写坏的
     * （异步双向泵 + 两段 Channel 生命周期）。用回声后端当隧道另一端即可验证。</p>
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("AioHttpProxyServer：CONNECT 隧道双向透传")
    void tunnelsViaConnect() throws Exception {
        try (ProxyTestSupport.EchoBackend backend = new ProxyTestSupport.EchoBackend()) {
            AioHttpProxyServer proxy = new AioHttpProxyServer(setting());
            proxy.start();
            try {
                String target = "127.0.0.1:" + backend.port();
                String request = "CONNECT " + target + " HTTP/1.1\r\n"
                        + "Host: " + target + "\r\n"
                        + "\r\n";
                try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                    client.setSoTimeout(15000);
                    OutputStream out = client.getOutputStream();
                    out.write(request.getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String head = readHead(client.getInputStream());
                    assertTrue(head.contains("200"),
                            "CONNECT 未成功，响应头：" + head.replace("\r\n", "|"));

                    String payload = "tunnel-echo";
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    byte[] buffer = new byte[64];
                    int read = ProxyTestSupport.readFully(client.getInputStream(),
                            buffer, payload.length());
                    assertEquals(payload, new String(buffer, 0, read, StandardCharsets.UTF_8),
                            "隧道内回传内容不一致");
                }
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 读到 HTTP 响应头结束（空行）为止。
     *
     * @param in 输入流
     * @return 响应头文本
     * @throws Exception 读取失败
     */
    private static String readHead(InputStream in) throws Exception {
        StringBuilder builder = new StringBuilder();
        int matched = 0;
        int read;
        while ((read = in.read()) >= 0) {
            builder.append((char) read);
            if (read == '\r') {
                matched = matched == 2 ? 3 : 1;
            } else if (read == '\n') {
                if (matched == 1) {
                    matched = 2;
                } else if (matched == 3) {
                    return builder.toString();
                } else {
                    matched = 0;
                }
            } else {
                matched = 0;
            }
            if (builder.length() > 8192) {
                break;
            }
        }
        return builder.toString();
    }
}
