package com.chua.huggingface.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HuggingfaceHubClient} 集成测试（走 hf-mirror.com 镜像，免鉴权只读）。
 *
 * <p>覆盖：仓库元信息、文件清单、单文件下载（resolve 302 跳转）、URL 拼接。</p>
 */
class HuggingfaceHubClientTest {

    private final HuggingfaceHubClient hub = new HuggingfaceHubClient().mirror();

    @Test
    void shouldUseMirrorBaseUrl() {
        assertEquals(HuggingfaceConstants.MIRROR_HUB_BASE_URL, hub.getBaseUrl());
        assertTrue(hub instanceof HuggingfaceHubClient);
        assertEquals("https://huggingface.co",
                new HuggingfaceHubClient().getBaseUrl());
    }

    @Test
    void shouldGetRepoInfo() {
        Map<String, Object> info = hub.getRepoInfo("segmind/tiny-sd");
        assertNotNull(info.get("id"), "仓库元信息应包含 id");
        assertEquals("segmind/tiny-sd", info.get("id"));
    }

    @Test
    void shouldListFiles() {
        List<String> files = hub.listFiles("segmind/tiny-sd");
        assertFalse(files.isEmpty(), "文件清单不应为空");
        assertTrue(files.contains("model_index.json"), "应包含 model_index.json，实际: " + files);
        assertTrue(files.stream().anyMatch(f -> f.endsWith(".bin")),
                "应包含权重文件（.bin），实际: " + files);
    }

    @Test
    void shouldDownloadFile() throws Exception {
        Path target = Files.createTempDirectory("hf-test").resolve("model_index.json");
        hub.downloadFile("segmind/tiny-sd", "model_index.json", target);
        assertTrue(Files.exists(target), "文件应已落盘");
        long size = Files.size(target);
        assertTrue(size > 0 && size < 100_000, "model_index.json 大小应在合理范围: " + size);
        String content = Files.readString(target);
        assertTrue(content.contains("unet"), "内容应引用 unet 组件");
        Files.deleteIfExists(target);
    }
}
