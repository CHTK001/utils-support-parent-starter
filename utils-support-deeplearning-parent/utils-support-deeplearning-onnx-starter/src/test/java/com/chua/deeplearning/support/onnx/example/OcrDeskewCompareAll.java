package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 全局 deskew 影响对比：对每张图的 deskew 触发框，对比 raw / +angle / -angle 识别，
 * 判定 deskew 是否有害以及方向。
 *
 * @since 4.0.0.42
 */
public final class OcrDeskewCompareAll {

    private OcrDeskewCompareAll() {
    }

    public static void main(String[] args) throws Exception {
        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .build();
        ImageDetector det = ocr.detector();
        OcrRecognizer rec = ocr.recognizer();

        try (var stream = Files.list(Path.of("G:\\images"))) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
            }).sorted().forEach(p -> {
                try {
                    byte[] img = Files.readAllBytes(p);
                    byte[] corrected = ocr.correct(img);
                    List<DetectionInfo> boxes = det.detect(corrected);
                    if (boxes == null || boxes.isEmpty()) return;
                    int trig = 0;
                    StringBuilder sb = new StringBuilder();
                    for (DetectionInfo b : boxes) {
                        double a = Math.abs(b.angle());
                        if (!(a > 1f && a < 30f)) continue;
                        trig++;
                        int px = 2;
                        byte[] crop = ImageCropUtils.crop(corrected,
                                (int) b.x() - px, (int) b.y() - px,
                                (int) b.width() + px * 2, (int) b.height() + px * 2);
                        String raw = rec.recognize(crop);
                        String plus = rec.recognize(ImageUtils.deskew(crop, (float) -b.angle()));
                        String minus = rec.recognize(ImageUtils.deskew(crop, (float) b.angle()));
                        sb.append(String.format("      angle=%.1f  raw='%s'  deskew(-ang)='%s'  deskew(+ang)='%s'%n",
                                b.angle(), raw, plus, minus));
                    }
                    if (trig > 0) {
                        System.out.println("[cmp] " + p.getFileName() + " 触发deskew框=" + trig);
                        System.out.print(sb);
                    }
                } catch (Exception e) {
                    System.out.println("[cmp] " + p.getFileName() + " 异常: " + e.getMessage());
                }
            });
        }
    }
}
