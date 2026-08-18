package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 质量门控对比：三张"不清楚"图，对比门控关 vs 门控开 + text-bsr 修复。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OcrQualityGateDiag {

    private OcrQualityGateDiag() {
    }

    public static void main(String[] args) throws Exception {
        String[] files = {
                "很不清楚的文字图片用于测试文字高清修复模型.png",
                "很不清楚的文字图片用于测试文字高清修复模型1.png",
                "很不清楚的文字图片用于测试文字高清修复模型2.png"
        };
        OcrPipeline off = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("doc-orientation")
                .build();
        OcrPipeline on = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("doc-orientation")
                .enhancer("text-bsr")
                .qualityGate(true)
                .build();

        for (String name : files) {
            byte[] img = Files.readAllBytes(Path.of("G:\\images", name));
            System.out.println("===== " + name + "  blurScore=" + String.format("%.1f", ImageUtils.blurScore(img)) + " =====");
            List<OcrResult> rOff = off.recognizeDetail(img);
            OcrPipeline.OcrRecognizeResult rOn = on.recognizeDetailWithImage(img);
            System.out.println("  [门控关] 块数=" + rOff.size());
            for (OcrResult r : rOff) {
                System.out.printf("      [%.2f] '%s'%n", r.confidence(), r.text());
            }
            System.out.println("  [门控开] 块数=" + rOn.results().size());
            for (OcrResult r : rOn.results()) {
                System.out.printf("      [%.2f] '%s'%n", r.confidence(), r.text());
            }
            String outName = "G:\\images\\output\\gated_" + name.replace('.', '_') + ".png";
            Files.write(Path.of(outName), draw(rOn));
            System.out.println("  已输出=" + outName);
        }
    }

    /**
     * 在矫正后的图上绘制识别框与文本。
     *
     * @param rr 识别结果
     * @return 图像字节
     */
    private static byte[] draw(OcrPipeline.OcrRecognizeResult rr) {
        List<com.chua.deeplearning.support.model.DetectionInfo> boxes = new java.util.ArrayList<>();
        List<String> labels = new java.util.ArrayList<>();
        for (OcrResult r : rr.results()) {
            com.chua.deeplearning.support.model.PredictRectangle b = r.boundingBox();
            if (b == null) {
                continue;
            }
            boxes.add(new com.chua.deeplearning.support.model.DetectionInfo(
                    String.format("%.2f", r.confidence()), r.confidence(),
                    b.x(), b.y(), b.width(), b.height(), r.angle()));
            labels.add(r.text());
        }
        return ImageUtils.drawDetectionsWithLabels(rr.image(), boxes, labels);
    }
}