package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 全量识别文本统计：列出每张图全部识别文本，核对是否有明显错字/乱码。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OcrTextDump {

    private OcrTextDump() {
    }

    public static void main(String[] args) throws Exception {
        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("doc-orientation")
                .build();

        try (var stream = Files.list(Path.of("G:\\images"))) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
            }).sorted().forEach(p -> {
                try {
                    byte[] img = Files.readAllBytes(p);
                    List<OcrResult> results = ocr.recognizeDetail(img);
                    System.out.println("===== " + p.getFileName() + " (" + results.size() + "块) =====");
                    for (OcrResult r : results) {
                        System.out.printf("  [%.2f] %s%n", r.confidence(), r.text());
                    }
                } catch (Exception e) {
                    System.out.println("===== " + p.getFileName() + " 异常: " + e.getMessage());
                }
            });
        }
    }
}