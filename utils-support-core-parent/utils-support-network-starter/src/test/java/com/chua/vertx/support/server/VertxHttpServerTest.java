// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.vertx.support.server;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VertxHttpServer} 的真实链路回归。
 *
 * <p>该实现属于「非原生包」的 Server 子类，此前<b>没有任何服务端用例</b>：
 * 它把请求交给过滤器链而不是直接路由，链上一环出错就只剩 500 或挂起，
 * 靠编译和人工点页面发现不了。这里用真实 socket 走一遍注册映射 → 响应回传。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class VertxHttpServerTest {

    /**
     * 取空闲端口。
     *
     * @return 端口
     * @throws Exception 套接字失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /**
     * 发一个 HTTP/1.1 请求并读完整响应。
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
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 注册映射后必须能通过真实 HTTP 请求拿到正文。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxHttpServer：注册映射后返回 200 与正文")
    void servesRegisteredMapping() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);

        VertxHttpServer server = new VertxHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, (request, response) -> {
            response.setStatus(200).setContentType("text/plain").setBody("vertx-ok");
            response.end();
        });
        server.start();
        try {
            String response = get(port, "/echo");
            assertTrue(response.contains("200"), "未返回 200：" + head(response));
            assertTrue(response.contains("vertx-ok"), "正文未正确返回：" + head(response));
        } finally {
            server.stop();
        }
    }

    /**
     * 未注册的路径必须给出确定结果（4xx/5xx 或空响应体），不能挂起。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxHttpServer：未注册路径不挂起")
    void unknownPathDoesNotHang() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);

        VertxHttpServer server = new VertxHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, (request, response) -> {
            response.setStatus(200).setContentType("text/plain").setBody("ok");
            response.end();
        });
        server.start();
        try {
            long start = System.currentTimeMillis();
            String response = get(port, "/nope");
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 8000L, "未注册路径耗时 " + elapsed + "ms，疑似挂起");
            assertTrue(response.isEmpty() || response.contains("40") || response.contains("404")
                            || response.contains("500"),
                    "未注册路径应给出确定结果，实际：" + head(response));
        } finally {
            server.stop();
        }
    }

    /**
     * 截取响应前 160 字符便于断言失败时阅读。
     *
     * @param response 响应
     * @return 摘要
     */
    private static String head(String response) {
        return response.substring(0, Math.min(160, response.length())).replace("\r\n", "|");
    }
}
