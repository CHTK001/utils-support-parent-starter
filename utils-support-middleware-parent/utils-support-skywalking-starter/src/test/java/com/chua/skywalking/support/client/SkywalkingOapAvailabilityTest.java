// [P3C A20 豁免] A20/A30/A31: 验收测试 (Http 状态码 200、基准时间) 使用字面量。
package com.chua.skywalking.support.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「OAP 是否可用」的判定口径。
 * <p>
 * 缺陷 D1 的根因就在这里：早先 {@code status()} 调的是容错的 {@code version()}，
 * 任何连接失败都被吞成 {@code unknown}，于是「OAP 不可达」被判成「可用但版本未知」，
 * 前端拿到 {@code present=true} 却每个查询都超时。
 * </p>
 * <p>
 * 这里锁三条语义，全部离线可跑（不需要真实 OAP）：
 * </p>
 * <ol>
 *   <li>{@link SkywalkingOapClient#versionChecked()}：连接失败必须<b>抛异常</b>；</li>
 *   <li>{@link SkywalkingOapClient#version()}：同一个地址只返回 {@code unknown}，不抛；</li>
 *   <li>老版本 OAP 不认 {@code query { version }} 而返回空串时，<b>不算不可用</b>——
 *       {@code versionChecked()} 返回 {@code unknown} 而不是抛异常。</li>
 * </ol>
 * <p>
 * 第 3 条最容易写错：不能把「版本未知」当故障，否则 9.0/9.1 这类真实在跑的 OAP 会被误判下线。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
class SkywalkingOapAvailabilityTest {

    /**
     * 单次请求超时（毫秒）：本机失败要快，避免拖慢单测
     */
    private static final long FAST_TIMEOUT = 1_500L;

    @Test
    @DisplayName("不可达的地址：versionChecked 抛异常")
    void versionCheckedThrowsWhenUnreachable() throws IOException {
        int port = freePort();
        try (SkywalkingOapClient client = client("http://127.0.0.1:" + port)) {
            SkywalkingException e = assertThrows(SkywalkingException.class, client::versionChecked);
            assertTrue(e.getMessage().contains("不可达"),
                    "异常消息要能说明是连不上，实际: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("不可达的地址：容错的 version 返回 unknown 且不抛")
    void versionSwallowsFailureAsUnknown() throws IOException {
        int port = freePort();
        try (SkywalkingOapClient client = client("http://127.0.0.1:" + port)) {
            assertEquals("unknown", client.version());
        }
    }

    @Test
    @DisplayName("老 OAP 返回空版本：算可用，只是版本未知")
    void emptyVersionIsNotAnOutage() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            byte[] body = "{\"data\":{\"version\":\"\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try (SkywalkingOapClient client = client("http://127.0.0.1:" + port)) {
            assertEquals("unknown", client.versionChecked(),
                    "空版本不是故障，必须判为可用但版本未知");
            assertEquals("unknown", client.version());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("正常的 OAP：两个方法都给真实版本号")
    void returnsVersionWhenAvailable() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", exchange -> {
            byte[] body = "{\"data\":{\"version\":\"9.7.0-066457b\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try (SkywalkingOapClient client = client("http://127.0.0.1:" + port)) {
            assertEquals("9.7.0-066457b", client.versionChecked());
            assertEquals("9.7.0-066457b", client.version());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 造一个指向指定地址的客户端（不走进程级 {@link SkywalkingSettings}，避免污染其它用例）
     *
     * @param endpoint OAP 地址
     * @return 客户端
     */
    private static SkywalkingOapClient client(String endpoint) {
        return new SkywalkingOapClient(endpoint, null, null, FAST_TIMEOUT);
    }

    /**
     * 拿一个「刚被释放、此刻必然没人监听」的本机端口
     *
     * @return 端口号
     * @throws IOException 取不到端口
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
