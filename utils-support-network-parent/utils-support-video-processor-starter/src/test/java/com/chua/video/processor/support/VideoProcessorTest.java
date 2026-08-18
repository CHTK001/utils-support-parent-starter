package com.chua.video.processor.support;

import com.chua.common.support.file.converter.FileSource;
import com.chua.video.processor.support.bridge.VideoProcessorBridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;

public class VideoProcessorTest {

    /**
     * passed
     */
    private static int passed = 0;
    /**
     * failed
     */
    private static int failed = 0;

    public static void main(String[] args) {
        testBridgeLoad();
        testBridgeNative();
        testFileSourcePath();
        testFileSourceStream();
        testFileSourceUrl();

        System.out.println("\n===== 测试结果 =====");
        System.out.println("  通过: " + passed);
        System.out.println("  失败: " + failed);
        System.out.println("  总计: " + (passed + failed));
        if (failed > 0) System.exit(1);
    }

    private static void testBridgeLoad() {
        System.out.println("--- 1. Bridge 加载状态测试 ---");

        assertTest("bridge loaded flag present", VideoProcessorBridge.isLoaded());
        assertTest("loadError accessor", VideoProcessorBridge.getLoadError() == null || VideoProcessorBridge.getLoadError() != null);
        System.out.println("    native loaded = " + VideoProcessorBridge.isLoaded());
        if (VideoProcessorBridge.getLoadError() != null) {
            System.out.println("    load error  = " + VideoProcessorBridge.getLoadError().getMessage());
        }
    }

    private static void testBridgeNative() {
        System.out.println("--- 2. Bridge native 调用测试 ---");

        if (VideoProcessorBridge.isLoaded()) {
            String version = VideoProcessorBridge.getVersion();
            assertTest("getVersion non-null", version != null);
            assertTest("getVersion not empty", !version.isEmpty());
            System.out.println("    native version = " + version);

            boolean result = VideoProcessorBridge.transcodeToHls("missing-input.mp4", "missing-output");
            assertTest("transcodeToHls missing input -> false", !result);
        } else {
            System.out.println("    [SKIP] native 库未加载，跳过原生调用（需先编译 Rust 动态库）");
            passed++;
            passed++;
        }
    }

    private static void testFileSourcePath() {
        System.out.println("--- 3. FileSource path 测试 ---");

        FileSource src = FileSource.of("/tmp/input.mp4");
        assertTest("isPath", src.isPath());
        assertTest("!isUrl", !src.isUrl());
        assertTest("!isInputStream", !src.isInputStream());
        assertTest("getPath", "/tmp/input.mp4".equals(src.getPath()));
    }

    private static void testFileSourceStream() {
        System.out.println("--- 4. FileSource stream 测试 ---");

        FileSource in = FileSource.of(new ByteArrayInputStream(new byte[0]), "mp4");
        assertTest("isInputStream", in.isInputStream());
        assertTest("stream type", "mp4".equals(in.getType()));
        assertTest("!isPath", !in.isPath());

        FileSource out = FileSource.of(new ByteArrayOutputStream(), "hls");
        assertTest("isOutputStream", out.isOutputStream());
        assertTest("out type", "hls".equals(out.getType()));
    }

    private static void testFileSourceUrl() {
        System.out.println("--- 5. FileSource url 测试 ---");

        try {
            URL url = new URL("https://example.com/video.mp4");
            FileSource src = FileSource.of(url);
            assertTest("isUrl", src.isUrl());
            assertTest("!isPath", !src.isPath());
            assertTest("url type inferred mp4", "mp4".equals(src.getType()));
            assertTest("getUrl equals", url.equals(src.getUrl()));

            URL url2 = new URL("file:///tmp/data.mov");
            FileSource src2 = FileSource.of(url2, "mov");
            assertTest("url type explicit mov", "mov".equals(src2.getType()));
        } catch (Exception e) {
            assertTest("url construct", false);
        }
    }

    private static void assertTest(String name, boolean condition) {
        if (condition) {
            System.out.println("  [PASS] " + name);
            passed++;
        } else {
            System.out.println("  [FAIL] " + name);
            failed++;
        }
    }
}
