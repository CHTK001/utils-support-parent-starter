package com.chua.video.processor.support;

import com.chua.video.processor.support.bridge.VideoProcessorBridge;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class HlsTranscodeTest {

    /**
     * test mp4
     */
    private static final String TEST_MP4 = "test-video.mp4";

    @Test
    void testNativeLibraryLoaded() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                VideoProcessorBridge.isLoaded(),
                "Native VideoProcessor 库未加载，跳过测试");
    }

    @Test
    void testGetVersion() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                VideoProcessorBridge.isLoaded(),
                "Native VideoProcessor 库未加载，跳过测试");

        String version = VideoProcessorBridge.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        System.out.println("Native version: " + version);
    }

    @Test
    void testTranscodeToHls() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                VideoProcessorBridge.isLoaded(),
                "Native VideoProcessor 库未加载，跳过 HLS 转码真实测试");

        Path outputDir = Files.createTempDirectory("hls-test");
        File input = new File(getClass().getClassLoader().getResource(TEST_MP4).toURI());

        boolean result = VideoProcessorBridge.transcodeToHls(
                input.getAbsolutePath(), outputDir.toAbsolutePath().toString());
        assertTrue(result, "HLS 转码应成功");

        boolean hasM3U8 = Files.walk(outputDir)
                .anyMatch(p -> p.toString().endsWith(".m3u8"));
        assertTrue(hasM3U8, "输出目录应包含 .m3u8 播放列表");

        long tsCount = Files.walk(outputDir)
                .filter(p -> p.toString().endsWith(".ts"))
                .count();
        assertTrue(tsCount > 0, "输出目录应包含至少一个 .ts 分片");
    }

    @Test
    void testTranscodeToHlsMissingInput() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                VideoProcessorBridge.isLoaded(),
                "Native VideoProcessor 库未加载，跳过测试");

        boolean result = VideoProcessorBridge.transcodeToHls(
                "missing-input.mp4", "missing-output");
        assertFalse(result, "输入文件不存在时应返回 false");
    }
}
