package com.chua.video.processor.support.example;

import com.chua.common.support.utils.CommandLine;
import com.chua.video.processor.support.bridge.VideoProcessorBridge;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HLS 转码示例 — 演示通过 VideoProcessorBridge 调用原生库将 MP4 转为 HLS。
 *
 * <h2>用法</h2>
 * <pre>
 *   java -cp ... HlsTranscodeExample -i /path/to/input.mp4
 *   java -cp ... HlsTranscodeExample --help
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HlsTranscodeExample {

    /** Main */
    public static void main(String[] args) throws Exception {
        System.out.println("===== HLS 转码示例 =====\n");

        CommandLine cli = CommandLine.parse(args)
                .program("HlsTranscodeExample")
                .register("input", "i", "输入的 MP4 文件路径")
                .register("output", "o", "HLS 输出目录（默认使用系统临时目录）")
                .register("help", "h", "显示帮助");

        if (cli.isHelp()) {
            cli.help();
            return;
        }

        String inputPath = cli.get("input");
        if (inputPath == null || inputPath.isBlank()) {
            System.out.println("[ERROR] 必须指定 --input 输入文件路径");
            cli.help();
            System.exit(1);
            return;
        }

        Path outputDir = cli.get("output") != null
                ? Path.of(cli.get("output"))
                : Files.createTempDirectory("hls-output");

        if (!VideoProcessorBridge.isLoaded()) {
            System.out.println("[ERROR] Native VideoProcessor 库未加载，请先编译动态库");
            System.exit(1);
            return;
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println("输入文件不存在: " + inputPath);
            System.exit(1);
            return;
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