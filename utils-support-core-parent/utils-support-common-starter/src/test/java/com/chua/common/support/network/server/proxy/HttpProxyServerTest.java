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
import static com.chua.common.support.network.server.proxy.ProxyTestSupport.closedPort;
import static com.chua.common.support.network.server.proxy.ProxyTestSupport.readToEof;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpProxyServer} 与 {@link JdkHttpProxyServer} 的真实链路回归。
 *
 * <p>两者语义不同，必须分开验：</p>
 * <ul>
 *   <li>{@code HttpProxyServer} 是<b>解析型</b>反向代理：自己读请求头、按 targetResolver 选后端、
 *       做 keep-alive 与 chunked 处理、解析失败回 502。</li>
 *   <li>{@code JdkHttpProxyServer} 继承 {@code TcpProxyServer}，是<b>透传型</b>：不解析 HTTP，
 *       原样把字节转给固定后端。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.52
 */
class HttpProxyServerTest {

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
     * 发一个 origin-form 请求并读完整响应。
     *
     * @param proxyPort 代理端口
     * @return 响应全文
     * @throws Exception 套接字失败
     */
    private static String request(int proxyPort) throws Exception {
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(10000);
            String request = "GET /hello HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream out = client.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readToEof(client.getInputStream());
        }
    }

    /**
     * 解析型反向代理：请求转发到后端并回传正文。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("HttpProxyServer：请求转发到后端并回传正文")
    void httpProxyForwardsToBackend() throws Exception {
        try (HttpBackend backend = new HttpBackend()) {
            HttpProxyServer proxy = new HttpProxyServer(setting(),
                    new java.net.InetSocketAddress("127.0.0.1", backend.port()));
            proxy.start();
            try {
                String response = request(proxy.getPort());
                assertTrue(response.contains("200"), "未拿到 200 响应，实际响应头："
                        + response.substring(0, Math.min(120, response.length())));
                assertTrue(response.contains(HTTP_BODY), "响应正文未经代理正确回传");
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 透传型反向代理：字节原样转给后端。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkHttpProxyServer：透传请求并回传正文")
    void jdkHttpProxyForwardsToBackend() throws Exception {
        try (HttpBackend backend = new HttpBackend()) {
            JdkHttpProxyServer proxy = new JdkHttpProxyServer(setting(),
                    new java.net.InetSocketAddress("127.0.0.1", backend.port()));
            proxy.start();
            try {
                String response = request(proxy.getPort());
                assertTrue(response.contains(HTTP_BODY), "响应正文未经透传代理正确回传");
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 后端不可达：客户端必须在限期内得到明确结果（502 或连接关闭），不能挂死。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("HttpProxyServer：后端不可达时快速给出结果")
    void httpProxyFailsFastWhenBackendDown() throws Exception {
        HttpProxyServer proxy = new HttpProxyServer(setting(),
                new java.net.InetSocketAddress("127.0.0.1", closedPort()));
        proxy.start();
        try {
            long start = System.currentTimeMillis();
            String response;
            try {
                response = request(proxy.getPort());
            } catch (java.io.IOException e) {
                // 连接被重置也属"快速失败"
                response = "";
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 12000L, "后端不可达时耗时 " + elapsed + "ms，应快速失败");
            assertTrue(response.isEmpty() || response.contains("502"),
                    "后端不可达时应回 502 或直接断开，实际响应：" + response);
        } finally {
            proxy.stop();
        }
    }
}
