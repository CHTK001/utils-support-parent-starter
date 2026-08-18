package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 统计各图片检测框角度分布：确认 deskew 在哪些图会被触发。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OcrAngleStatsDiag {

    private OcrAngleStatsDiag() {
    }

    public static void main(String[] args) throws Exception {
        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .build();
        ImageDetector det = ocr.detector();

        try (var stream = Files.list(Path.of("G:\\images"))) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
            }).sorted().forEach(p -> {
                try {
                    byte[] img = Files.readAllBytes(p);
                    byte[] corrected = ocr.correct(img);
                    List<DetectionInfo> boxes = det.detect(corrected);
                    if (boxes == null || boxes.isEmpty()) {
                        System.out.printf("[stat] %-24s 无检测框%n", p.getFileName());
                        return;
                    }
                    double maxA = 0;
                    int trig = 0;
                    for (DetectionInfo b : boxes) {
                        double a = Math.abs(b.angle());
                        maxA = Math.max(maxA, a);
                        if (a > 1f && a < 30f) trig++;
                    }
                    System.out.printf("[stat] %-24s 框数=%2d 最大|angle|=%.1f° deskew触发框数=%d%n",
                            p.getFileName(), boxes.size(), maxA, trig);
                } catch (Exception e) {
                    System.out.printf("[stat] %-24s 异常: %s%n", p.getFileName(), e.getMessage());
                }
            });
        }
    }
}
