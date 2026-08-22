package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.plate.PlateResult;
import com.chua.deeplearning.support.recognition.PlateNumberPipeline;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PlatePipelineVerify {
    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        ImageUtils.load();

        String[] images = {"D:/images/car plate1.webp", "D:/images/car plate2.webp", "D:/images/car plate3.webp", "D:/images/more car plate.webp"};
        boolean allOk = true;

        for (String imgPath : images) {
            System.out.println("=== " + Path.of(imgPath).getFileName() + " ===");
            byte[] img = Files.readAllBytes(Path.of(imgPath));
            long t0 = System.currentTimeMillis();
            List<PlateResult> results = PlateNumberPipeline.builder()
                    .model("yolov5-plate-recognize")
                    .detector("yolov5-plate-detect")
                    .build()
                    .recognize(img);
            long cost = System.currentTimeMillis() - t0;
            if (results.isEmpty()) {
                System.out.println("  未检测到车牌  耗时=" + cost + "ms");
                allOk = false;
            } else {
                for (PlateResult r : results) {
                    System.out.println("  车牌: " + r.plateNo() + "  颜色: " + r.plateColor() + "  耗时=" + cost + "ms");
                }
            }
        }
        System.out.println(allOk ? "[PlatePipelineVerify] ALL PASS" : "[PlatePipelineVerify] 部分未识别");
        if (!allOk) { System.exit(1); }
    }
}