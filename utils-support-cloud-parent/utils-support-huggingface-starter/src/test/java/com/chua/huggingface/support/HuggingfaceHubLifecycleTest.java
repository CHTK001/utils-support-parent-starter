package com.chua.huggingface.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HuggingfaceHubClient} 完整生命周期集成测试（真实写操作，走官方站）。
 *
 * <p>仅当提供 {@code -Dhf.token=hf_xxx}（或环境变量 {@code HF_TOKEN}）时运行，
 * 否则全部跳过。流程：whoami → 建仓库 → 上传 → 列表 → 下载校验 → 删文件 → 删仓库 → 确认 404。</p>
 *
 * <p>注意：本环境 JVM 直连 huggingface.co 需 truststore 指向代理 CA
 * （{@code -Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=changeit}）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HuggingfaceHubLifecycleTest {

    private static String token() {
        String t = System.getProperty("hf.token");
        if (t == null || t.isBlank()) {
            t = System.getenv("HF_TOKEN");
        }
        return (t == null || t.isBlank()) ? null : t;
    }

    private boolean enabled() {
        return token() != null;
    }

    private final String repoId = "chtk/hf-lifecycle-test-" + System.currentTimeMillis();
    private final String hello = "hello from HuggingfaceHubClient lifecycle test " + System.currentTimeMillis();

    private HuggingfaceHubClient hub() {
        return new HuggingfaceHubClient(token());
    }

    private void assumeEnabled() {
        Assumptions.assumeTrue(enabled(), "未提供 hf.token，跳过写路径测试");
    }

    @Test
    @Order(1)
    void shouldAuthenticate() {
        assumeEnabled();
        Map<String, Object> account = hub().whoami();
        assertNotNull(account.get("name"), "whoami 应返回用户名");
        assertEquals("user", account.get("type"), "token 类型应为 user");
        // 顺带验证公开仓库元信息可读
        Map<String, Object> info = hub().getRepoInfo("segmind/tiny-sd");
        assertEquals("segmind/tiny-sd", info.get("id"));
    }

    @Test
    @Order(2)
    void shouldCreateRepo() {
        assumeEnabled();
        hub().createRepo(repoId, "model", true);
        Map<String, Object> info = hub().getRepoInfo(repoId);
        assertEquals(repoId, info.get("id"), "仓库创建后应可查询");
        assertEquals(true, info.get("private"), "应为私有仓库");
    }

    @Test
    @Order(3)
    void shouldUploadFile() throws Exception {
        assumeEnabled();
        Path local = Files.createTempFile("hf-lifecycle", ".txt");
        Files.writeString(local, hello);
        try {
            hub().uploadFile(repoId, "hello.txt", local);
        } finally {
            Files.deleteIfExists(local);
        }
        List<String> files = hub().listFiles(repoId);
        assertTrue(files.contains("hello.txt"), "上传后文件应出现在清单: " + files);
    }

    @Test
    @Order(4)
    void shouldDownloadUploadedFile() throws Exception {
        assumeEnabled();
        Path target = Files.createTempDirectory("hf-lifecycle").resolve("hello.txt");
        hub().downloadFile(repoId, "hello.txt", target);
        String content = Files.readString(target);
        assertEquals(hello, content, "下载内容应与上传一致");
        Files.deleteIfExists(target);
    }

    @Test
    @Order(5)
    void shouldDeleteFile() {
        assumeEnabled();
        hub().deleteFile(repoId, "hello.txt");
        List<String> files = hub().listFiles(repoId);
        assertFalse(files.contains("hello.txt"), "删除后文件不应在清单: " + files);
    }

    @Test
    @Order(6)
    void shouldUploadLfsLargeFile() throws Exception {
        assumeEnabled();
        // 10MB 随机文件：超过 HF 默认 LFS 阈值（1MB），走 preupload→batch→PUT→commit 全链路
        byte[] big = new byte[10 * 1024 * 1024];
        new java.util.Random(42).nextBytes(big);
        Path local = Files.createTempFile("hf-lifecycle-lfs", ".bin");
        Files.write(local, big);
        try {
            hub().uploadFile(repoId, "big.bin", local);
            List<String> files = hub().listFiles(repoId);
            assertTrue(files.contains("big.bin"), "LFS 文件应出现在清单: " + files);
            // 下载回验（resolve 302 跳 LFS 存储）
            Path target = Files.createTempDirectory("hf-lfs").resolve("big.bin");
            hub().downloadFile(repoId, "big.bin", target);
            assertEquals(big.length, Files.size(target), "下载大小应与上传一致");
            Files.deleteIfExists(target);
        } finally {
            Files.deleteIfExists(local);
        }
    }

    @Test
    @Order(7)
    void shouldDeleteLfsFile() {
        assumeEnabled();
        hub().deleteFile(repoId, "big.bin");
        List<String> files = hub().listFiles(repoId);
        assertFalse(files.contains("big.bin"), "删除后 LFS 文件不应在清单: " + files);
    }

    @AfterAll
    void shouldDeleteRepo() {
        if (!enabled()) {
            return;
        }
        HuggingfaceHubClient hub = hub();
        try {
            hub.deleteRepo(repoId);
        } catch (Exception e) {
            System.err.println("[lifecycle] 仓库删除异常（请手动清理）: " + e.getMessage());
            return;
        }
        RuntimeException ex = assertThrows(RuntimeException.class, () -> hub.getRepoInfo(repoId),
                "仓库删除后查询应抛 404 异常");
        assertTrue(ex.getMessage().contains("404"), "删除后应为 404: " + ex.getMessage());
    }
}
