package com.chua.video.processor.support;

import com.chua.video.processor.support.bridge.VideoProcessorBridge;
/**
 * @author CH
 * @since 4.0.0
 */

public class VideoProcessor {

    /**
     * transcode转为hls
     *
     * @param inputPath 输入路径
     * @param outputDir 输出dir
     * @return transcode转为hls的结果
     */
    public static boolean transcodeToHls(String inputPath, String outputDir) {
        VideoProcessorBridge.ensureLoaded();
        return VideoProcessorBridge.transcodeToHls(inputPath, outputDir);
    }

    /**
     * 获取版本
     *
     * @return 获取版本的结果
     */
    public static String getVersion() {
        VideoProcessorBridge.ensureLoaded();
        return VideoProcessorBridge.getVersion();
    }

    /**
     * 是否可用
     *
     * @return 是否可用的结果
     */
    public static boolean isAvailable() {
        return VideoProcessorBridge.isLoaded();
    }

    /**
     * Main
     *
     * @param args 参数
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java VideoProcessor <input> <output_dir>");
            System.exit(1);
        }
        String input = args[0];
        String output = args[1];
        boolean success = transcodeToHls(input, output);
        System.out.println(success ? "Success" : "Failure");
        System.exit(success ? 0 : 1);
    }
}
