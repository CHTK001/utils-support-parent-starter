package com.chua.crypto.support;

import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.crypto.support.license.FileLicenseRegistry;
import com.chua.crypto.support.license.LicenseRegistry;
import com.chua.crypto.support.license.LicenseServerFilter;
import com.chua.crypto.support.launch.LicenseKeyClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验服务器组件测试：注册表持久化 + ServerFilter 体系集成 + 签名协议
 *
 * @author CH
 * @since 2026-08-26
 */
class LicenseServerFilterTest {

    /**
     * 临时目录
     */
    @TempDir
    Path tempDir;

    /**
     * 注册表：注册/查询/吊销/持久化往返（FileLicenseRegistry）
     */
    @Test
    void registryRoundTrip() throws Exception {
        Path file = tempDir.resolve("licenses.txt");
        FileLicenseRegistry registry = FileLicenseRegistry.load(file);
        byte[] blob = {1, 2, 3, 4, 5};

        registry.register("fp-aaa", blob);
        assertTrue(registry.contains("fp-aaa"));

        // 重载验证持久化
        FileLicenseRegistry reloaded = FileLicenseRegistry.load(file);
        assertArrayEquals(blob, reloaded.lookup("fp-aaa"));

        assertTrue(reloaded.revoke("fp-aaa"));
        assertFalse(reloaded.contains("fp-aaa"));
        assertNull(reloaded.lookup("fp-aaa"));
    }

    /**
     * ServerFilter 体系集成：无签名模式 —— 已注册下发 / 未注册 403 / 非 POST 405
     */
    @Test
    void filterUnsignedMode() throws Exception {
        byte[] blob = "fake-blob-bytes".getBytes();
        Server server = startServer(blob, null);
        int port = server.getPort();
        try {
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<byte[]> ok = client.send(post(port, "f".repeat(64)),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, ok.statusCode());
            assertArrayEquals(blob, ok.body());

            assertEquals(403, client.send(post(port, "unknown"),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode());

            HttpResponse<String> wrongMethod = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/license"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, wrongMethod.statusCode());
        } finally {
            server.stop();
        }
    }

    /**
     * 签名模式：服务端 v1.blob.hmac 下发，客户端以相同 secret 校验通过；
     * secret 不匹配时客户端拒绝
     */
    @Test
    void filterSignedMode() throws Exception {
        byte[] blob = "signed-blob-bytes".getBytes();
        char[] serverSecret = "prod-secret".toCharArray();
        Server server = startServer(blob, serverSecret);
        int port = server.getPort();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<byte[]> resp = client.send(post(port, "f".repeat(64)),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, resp.statusCode());

            String text = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.startsWith("v1."), "签名模式响应应以 v1. 开头");

            // 正确 secret：解出原始封装块
            assertArrayEquals(blob, LicenseKeyClient.parseResponse(resp.body(), serverSecret));

            // 错误 secret：拒绝
            assertThrows(IllegalStateException.class,
                    () -> LicenseKeyClient.parseResponse(resp.body(), "wrong".toCharArray()));
        } finally {
            server.stop();
        }
    }

    /**
     * 启动测试用校验服务
     *
     * @param blob   注册的私钥块
     * @param secret 响应签名密钥（可空）
     * @return 已启动的 Server
     * @throws Exception 启动失败
     */
    private Server startServer(byte[] blob, char[] secret) throws Exception {
        Path file = tempDir.resolve("lic-" + System.nanoTime() + ".txt");
        LicenseRegistry registry = FileLicenseRegistry.load(file);
        registry.register("f".repeat(64), blob);

        Server server = ServerBuilder.create()
                .type("jdk")
                .host("127.0.0.1")
                .port(0)
                .build();
        server.addFilter(new LicenseServerFilter(registry, secret));
        server.start();
        return server;
    }

    /**
     * 构造 POST 请求
     */
    private HttpRequest post(int port, String fingerprint) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/license"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"appId\":\"demo\",\"fingerprint\":\"" + fingerprint + "\"}"))
                .build();
    }
}
