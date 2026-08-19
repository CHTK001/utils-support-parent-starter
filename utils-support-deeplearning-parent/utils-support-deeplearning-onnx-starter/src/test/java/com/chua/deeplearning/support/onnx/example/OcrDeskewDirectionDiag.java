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
 * OCR 裁剪块 deskew 方向实验：对每个检测块分别用 +angle / -angle 旋转扶正后送 rec，
 * 对比识别结果判断正确旋转方向，用于修正 OcrPipeline 裁剪块 deskew。
 *
 * @since 4.0.0.42
 */
public final class OcrDeskewDirectionDiag {

    /** 创建 OcrDeskewDirectionDiag 实例 */
    private OcrDeskewDirectionDiag() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "G:\\images\\倾斜角的文字.jpg";
        byte[] img = Files.readAllBytes(Path.of(src));

        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .build();
        byte[] corrected = ocr.correct(img);

        ImageDetector det = ocr.detector();
        OcrRecognizer rec = ocr.recognizer();
        List<DetectionInfo> boxes = det.detect(corrected);
        System.out.println("[diag] 检测框数=" + boxes.size());
        int i = 0;
        for (DetectionInfo b : boxes) {
            int px = 2;
            byte[] crop = ImageCropUtils.crop(corrected,
                    (int) b.x() - px, (int) b.y() - px,
                    (int) b.width() + px * 2, (int) b.height() + px * 2);
            System.out.printf("  det[%02d] (%.0f,%.0f) %.0fx%.0f angle=%.1f conf=%.2f%n",
                    i, b.x(), b.y(), b.width(), b.height(), b.angle(), b.confidence());
            double a = b.angle();
            String raw = rec.recognize(crop);
            String plus = rec.recognize(ImageUtils.deskew(crop, (float) a));
            String minus = rec.recognize(ImageUtils.deskew(crop, (float) -a));
            System.out.println("      raw  = '" + raw + "'");
            System.out.println("      deskew(+" + String.format("%.1f", a) + ") = '" + plus + "'");
            System.out.println("      deskew(-" + String.format("%.1f", a) + ") = '" + minus + "'");
            i++;
        }
    }
}
