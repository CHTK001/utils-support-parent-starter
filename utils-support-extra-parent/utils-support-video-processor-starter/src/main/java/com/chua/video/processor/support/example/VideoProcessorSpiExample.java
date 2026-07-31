package com.chua.video.processor.support.example;

import com.chua.common.support.media.ffmpeg.FFmpegProcessor;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.video.processor.support.bridge.VideoProcessorBridge;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 视频处理 SPI 示例 — 演示通过 ServiceProvider 发现并调用 FFmpegProcessor 真实实现。
 *
 * <p>对应实现：{@code com.chua.ffmpeg.rust.support.processor.RustFFmpegProcessor}</p>
 *
 * <p>用法：<pre>
 *   java -cp ... VideoProcessorSpiExample /path/to/input.mp4 [outputDir]
 * </pre></p>
 *
 * @author CH
 */
public class VideoProcessorSpiExample {

    private static final String PROVIDER_TYPE = System.getProperty("video.type", "rust");

    public static void main(String[] args) throws Exception {
        System.out.println("===== 视频处理 SPI 示例 (provider=" + PROVIDER_TYPE + ") =====\n");

        if (args.length < 1) {
            System.out.println("用法: VideoProcessorSpiExample <input.mp4> [outputDir]");
            System.exit(1);
        }

        String inputPath = args[0];
        Path outputDir = args.length >= 2
                ? Path.of(args[1])
                : Files.createTempDirectory("video-output");

        ServiceProvider<FFmpegProcessor> provider = ServiceProvider.of(FFmpegProcessor.class);

        System.out.println("--- 1. SPI 发现 ---");
        List<FFmpegProcessor> all = provider.collect();
        System.out.println("  实现数量: " + all.size());
        for (FFmpegProcessor impl : all) {
            System.out.println("    " + impl.getClass().getName() + " | available=" + impl.isAvailable());
        }

        System.out.println("\n--- 2. 获取指定实现 ---");
        FFmpegProcessor processor = provider.getNewExtension(PROVIDER_TYPE);
        if (processor == null || !processor.isAvailable()) {
            processor = all.stream().findFirst().orElse(null);
        }
        if (processor == null) {
            System.out.println("  [ERROR] 未找到可用的 FFmpegProcessor 实现");
            System.exit(1);
        }
        System.out.println("  实现类: " + processor.getClass().getSimpleName());
        System.out.println("  可用: " + processor.isAvailable());
        System.out.println("  版本: " + processor.getVersion());

        System.out.println("\n--- 3. 获取媒体信息 ---");
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println("  输入文件不存在: " + inputPath);
            System.exit(1);
        }
        var info = processor.getMediaInfo(inputFile);
        System.out.println("  格式: " + info.getFormatName());
        System.out.println("  时长: " + info.getDuration() + "s");
        if (info.getVideoStream() != null) {
            System.out.println("  视频: " + info.getVideoStream().getWidth() + "x" + info.getVideoStream().getHeight());
        }

        System.out.println("\n--- 4. 视频转 HLS ---");
        File hlsOutput = new File(outputDir.toFile(), "output.m3u8");
        if (VideoProcessorBridge.isLoaded()) {
            System.out.println("  使用 VideoProcessorBridge.transcodeToHls");
            boolean ok = VideoProcessorBridge.transcodeToHls(inputPath, outputDir.toString());
            if (!ok) {
                throw new RuntimeException("HLS 转码失败");
            }
        } else {
            System.out.println("  [INFO] Native VideoProcessor 未加载，回退到 FFmpegProcessor.convertVideo");
            processor.convertVideo(inputFile, hlsOutput, "hls");
        }

        System.out.println("  输出目录: " + outputDir);
        try (var stream = Files.list(outputDir)) {
            stream.forEach(p -> System.out.println("    " + p.getFileName()));
        }

        System.out.println("\n===== 示例结束 =====");
    }
}
