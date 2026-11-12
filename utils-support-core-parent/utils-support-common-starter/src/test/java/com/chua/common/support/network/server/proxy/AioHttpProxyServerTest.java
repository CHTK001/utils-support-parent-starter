// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTestSupport.HttpBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static com.chua.common.support.network.server.proxy.ProxyTestSupport.HTTP_BODY;
import static com.chua.common.support.network.server.proxy.ProxyTestSupport.readToEof;
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
}
