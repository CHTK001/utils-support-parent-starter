package com.chua.crypto.support;

import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.crypto.support.license.LicenseRegistry;
import com.chua.crypto.support.license.LicenseServerFilter;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验服务器组件测试：注册表持久化 + LicenseServerFilter 接入 ServerFilter 体系后的下发/拒绝
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
     * 注册表：注册/查询/吊销/持久化往返
     */
    @Test
    void registryRoundTrip() throws Exception {
        Path file = tempDir.resolve("licenses.txt");
        LicenseRegistry registry = LicenseRegistry.load(file);
        byte[] blob = {1, 2, 3, 4, 5};

        registry.register("fp-aaa", blob);
        assertTrue(registry.contains("fp-aaa"));

        // 重载验证持久化
        LicenseRegistry reloaded = LicenseRegistry.load(file);
        assertArrayEquals(blob, reloaded.lookup("fp-aaa"));

        assertTrue(reloaded.revoke("fp-aaa"));
        assertFalse(reloaded.contains("fp-aaa"));
        assertNull(reloaded.lookup("fp-aaa"));
    }

    /**
     * ServerFilter 体系集成：真实 HTTP 服务 + 已注册下发 / 未注册 403
     */
    @Test
    void filterServesRegisteredAndRejectsUnknown() throws Exception {
        Path file = tempDir.resolve("lic.txt");
        LicenseRegistry registry = LicenseRegistry.load(file);
        byte[] blob = "fake-blob-bytes".getBytes();
        registry.register("fingerprint-ok", blob);

        Server server = ServerBuilder.create()
                .type("jdk")
                .host("127.0.0.1")
                .port(0)
                .build();
        server.addFilter(new LicenseServerFilter(registry));
        server.start();
        int port = server.getPort();
        try {
            HttpClient client = HttpClient.newHttpClient();

            // 已注册 → 200 + 私钥块
            HttpResponse<byte[]> ok = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/license"))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"appId\":\"demo\",\"fingerprint\":\"fingerprint-ok\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, ok.statusCode());
            assertArrayEquals(blob, ok.body());

            // 未注册 → 403
            HttpResponse<byte[]> denied = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/license"))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"appId\":\"demo\",\"fingerprint\":\"unknown\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(403, denied.statusCode());
        } finally {
            server.stop();
        }
    }
}
