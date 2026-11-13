// [P3C A20 豁免] 测试断言与协议常量含魔法值场景，豁免。
package com.chua.jrebel.support.server;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JRebelHttpServer} 的真实链路回归（此前零用例）。
 *
 * <p>服务端基于 JDK HttpServer 注册 JRebel/XRebel 鉴权端点，本测试用
 * <b>JDK HttpClient</b> 走真实 HTTP，验证每个端点的协议契约（JSON/XML）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class JRebelHttpServerTest {

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
     * 启动服务端。
     *
     * @param port 端口
     * @return 服务端
     */
    private static JRebelHttpServer server(int port) {
        ServerSetting setting = ServerSetting.builder()
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .build();
        return new JRebelHttpServer(setting);
    }

    /**
     * GET 请求。
     *
     * @param client HttpClient
     * @param port    端口
     * @param path    路径（含查询串）
     * @return 响应
     * @throws Exception 请求失败
     */
    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * POST JSON 请求。
     *
     * @param client HttpClient
     * @param port    端口
     * @param path    路径
     * @param body    JSON 体
     * @return 响应
     * @throws Exception 请求失败
     */
    private static HttpResponse<String> post(HttpClient client, int port, String path, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 鉴权三连：validate-connection / 创建 lease / ping。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JRebelHttpServer：validate-connection / lease / ping")
    void licenseEndpoints() throws Exception {
        int port = freePort();
        JRebelHttpServer server = server(port);
        server.start();
        HttpClient client = HttpClient.newHttpClient();
        try {
            Map<String, Object> validate = Json.fromJson(
                    get(client, port, "/jrebel/validate-connection").body(), Map.class);
            assertEquals("SUCCESS", validate.get("statusCode"), "validate-connection 状态不符");
            assertEquals(Boolean.TRUE, validate.get("canGetLease"), "canGetLease 应为 true");

            Map<String, Object> lease = Json.fromJson(
                    post(client, port, "/jrebel/leases",
                            "{\"username\":\"tester\",\"offline\":false}").body(), Map.class);
            assertEquals("SUCCESS", lease.get("statusCode"), "createLease 状态不符");
            assertNotNull(lease.get("serverGuid"), "createLease 未返回 serverGuid");
            assertNotNull(lease.get("signature"), "createLease 未返回签名");

            Map<String, Object> ping = Json.fromJson(
                    get(client, port, "/rpc/ping.action").body(), Map.class);
            assertEquals("SUCCESS", ping.get("statusCode"), "ping 状态不符");
        } finally {
            client.close();
            server.stop();
        }
    }

    /**
     * guid 生成与 ticket 获取/释放（XML 契约）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JRebelHttpServer：guid / obtainTicket / releaseTicket")
    void guidAndTickets() throws Exception {
        int port = freePort();
        JRebelHttpServer server = server(port);
        server.start();
        HttpClient client = HttpClient.newHttpClient();
        try {
            Map<String, Object> guid = Json.fromJson(
                    get(client, port, "/guid?clientId=dev-01").body(), Map.class);
            assertEquals("SUCCESS", guid.get("status"), "guid 状态不符");
            assertNotNull(guid.get("guid"), "guid 为空");

            String ticket = get(client, port,
                    "/rpc/obtainTicket.action?userName=tester&salt=my-salt").body();
            assertTrue(ticket.contains("<responseCode>OK</responseCode>"), "obtainTicket 响应码不符");
            assertTrue(ticket.contains("<salt>my-salt</salt>"), "obtainTicket 未回传 salt");
            assertTrue(ticket.contains("licensee=tester"), "obtainTicket 未带 licensee");

            String released = get(client, port, "/rpc/releaseTicket.action?ticketId=1").body();
            assertTrue(released.contains("<responseCode>OK</responseCode>"), "releaseTicket 响应码不符");
        } finally {
            client.close();
            server.stop();
        }
    }

    /**
     * 未注册路径必须返回 404（走通用路由）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JRebelHttpServer：未注册路径 404")
    void unknownPathIs404() throws Exception {
        int port = freePort();
        JRebelHttpServer server = server(port);
        server.start();
        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpResponse<String> response = get(client, port, "/no-such-endpoint");
            assertEquals(404, response.statusCode(), "未知端点应 404");
        } finally {
            client.close();
            server.stop();
        }
    }
}
