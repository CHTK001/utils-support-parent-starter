package com.chua.video.processor.support;

import com.chua.video.processor.support.bridge.VideoProcessorBridge;
/**
 * @author CH
 */

public class VideoProcessor {

    public static boolean transcodeToHls(String inputPath, String outputDir) {
        VideoProcessorBridge.ensureLoaded();
        return VideoProcessorBridge.transcodeToHls(inputPath, outputDir);
    }

    public static String getVersion() {
        VideoProcessorBridge.ensureLoaded();
        return VideoProcessorBridge.getVersion();
    }

    public static boolean isAvailable() {
        return VideoProcessorBridge.isLoaded();
    }

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
