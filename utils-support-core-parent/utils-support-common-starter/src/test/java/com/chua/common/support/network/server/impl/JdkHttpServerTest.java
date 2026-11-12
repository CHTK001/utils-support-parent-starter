// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkHttpServer} 的真实链路回归。
 *
 * <p>它是阻塞式（一连接一线程）HTTP 服务器，与 NIO/AIO 版是三条独立实现路径；
 * 之前零覆盖。这里只验最基本也最容易坏的两件事：路由分发正确、响应体能完整写出。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class JdkHttpServerTest {

    /**
     * 小响应正文。
     */
    private static final String SMALL_BODY = "jdk-http-ok";

    /**
     * 大响应正文大小（字节）。
     */
    private static final int BIG_BODY = 64 * 1024;

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
     * 发 GET 并读完整响应。
     *
     * @param port 端口
     * @param path 路径
     * @return 响应全文
     * @throws Exception 套接字失败
     */
    private static String get(int port, String path) throws Exception {
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(10000);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream out = client.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = client.getInputStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 路由命中时状态码与正文都要正确。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkHttpServer：路由命中返回 200 与正文")
    void servesRegisteredMapping() throws Exception {
        JdkHttpServer server = new JdkHttpServer(setting());
        ServerHandler handler = (req, res) -> {
            res.setStatus(200).setContentType("text/plain").setBody(SMALL_BODY);
            res.end();
        };
        server.registerMapping("/echo", HttpMethod.GET, handler);
        server.start();
        try {
            int port = server.getPort();
            assertTrue(port > 0, "服务器未绑定端口");
            String response = get(port, "/echo");
            assertTrue(response.contains("200"), "未返回 200：" + head(response));
            assertTrue(response.contains(SMALL_BODY), "正文未正确返回：" + head(response));
        } finally {
            server.stop();
        }
    }

    /**
     * 64KB 正文必须完整写出（阻塞写路径最容易在这里截断）。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("JdkHttpServer：64KB 正文完整返回")
    void servesLargeBody() throws Exception {
        String big = "x".repeat(BIG_BODY);
        JdkHttpServer server = new JdkHttpServer(setting());
        ServerHandler handler = (req, res) -> {
            res.setStatus(200).setContentType("text/plain").setBody(big);
            res.end();
        };
        server.registerMapping("/big", HttpMethod.GET, handler);
        server.start();
        try {
            String response = get(server.getPort(), "/big");
            int bodyStart = response.indexOf("\r\n\r\n");
            assertTrue(bodyStart > 0, "响应没有头体分隔：" + head(response));
            String body = response.substring(bodyStart + 4);
            assertTrue(body.length() >= BIG_BODY,
                    "正文被截断：期望 " + BIG_BODY + " 实际 " + body.length());
        } finally {
            server.stop();
        }
    }

    /**
     * 截取响应前 120 字符用于断言失败时的可读信息。
     *
     * @param response 响应
     * @return 摘要
     */
    private static String head(String response) {
        return response.substring(0, Math.min(120, response.length())).replace("\r\n", "|");
    }
}
