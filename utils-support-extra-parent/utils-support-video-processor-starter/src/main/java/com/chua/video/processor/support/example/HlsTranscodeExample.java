package com.chua.video.processor.support.example;

import com.chua.video.processor.support.bridge.VideoProcessorBridge;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HLS 转码真实示例 — 演示通过 VideoProcessorBridge 调用 Rust 原生库将 MP4 转为 HLS。
 *
 * <p>用法：<pre>
 *   java -cp ... HlsTranscodeExample /path/to/input.mp4 [outputDir]
 * </pre></p>
 *
 * @author CH
 */
public class HlsTranscodeExample {

    public static void main(String[] args) throws Exception {
        System.out.println("===== HLS 转码示例 =====\n");

        if (args.length < 1) {
            System.out.println("用法: HlsTranscodeExample <input.mp4> [outputDir]");
            System.exit(1);
        }

        String inputPath = args[0];
        Path outputDir = args.length >= 2
                ? Path.of(args[1])
                : Files.createTempDirectory("hls-output");

        if (!VideoProcessorBridge.isLoaded()) {
            System.out.println("[ERROR] Native VideoProcessor 库未加载，请先编译 Rust 动态库");
            System.exit(1);
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println("输入文件不存在: " + inputPath);
            System.exit(1);
        }

        System.out.println("--- 1. 获取 native 版本 ---");
        System.out.println("  版本: " + VideoProcessorBridge.getVersion());

        System.out.println("\n--- 2. HLS 转码 ---");
        boolean ok = VideoProcessorBridge.transcodeToHls(inputPath, outputDir.toString());
        if (!ok) {
            throw new RuntimeException("HLS 转码失败");
        }

        System.out.println("  输出目录: " + outputDir);
        try (var stream = Files.list(outputDir)) {
            stream.forEach(p -> System.out.println("    " + p.getFileName()));
        }

        System.out.println("\n===== 示例结束 =====");
    }
}
