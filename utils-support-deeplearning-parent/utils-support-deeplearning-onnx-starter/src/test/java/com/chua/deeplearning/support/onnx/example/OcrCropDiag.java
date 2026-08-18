package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OCR 裁剪块 deskew 诊断：保存每个检测框裁剪前后与 deskew 后的图像，肉眼核对倾斜矫正方向。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OcrCropDiag {

    private OcrCropDiag() {
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "G:\\images\\倾斜角的文字.jpg";
        byte[] img = Files.readAllBytes(Path.of(src));
        String outDir = "G:\\images\\output\\cropdiag";
        Files.createDirectories(Path.of(outDir));

        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .build();
        byte[] corrected = ocr.correct(img);
        System.out.println("[diag] 矫正后尺寸=" + ImageUtils.decode(corrected).cols() + "x"
                + ImageUtils.decode(corrected).rows());

        ImageDetector det = ocr.detector();
        List<DetectionInfo> boxes = det.detect(corrected);
        System.out.println("[diag] 检测框数=" + boxes.size());
        int i = 0;
        for (DetectionInfo b : boxes) {
            int px = 2;
            byte[] crop = ImageCropUtils.crop(corrected,
                    (int) b.x() - px, (int) b.y() - px,
                    (int) b.width() + px * 2, (int) b.height() + px * 2);
            String tag = String.format("%02d_a_raw_%.1fdeg", i, b.angle());
            Files.write(Path.of(outDir, tag + ".png"), crop);

            byte[] deskewed = crop;
            if (Math.abs(b.angle()) > 1f && Math.abs(b.angle()) < 30f) {
                deskewed = ImageUtils.deskew(crop, -b.angle());
            }
            String tag2 = String.format("%02d_b_deskew_%.1fdeg", i, b.angle());
            Files.write(Path.of(outDir, tag2 + ".png"), deskewed);
            System.out.printf("  det[%02d] (%.0f,%.0f) %.0fx%.0f angle=%.1f conf=%.2f%n",
                    i, b.x(), b.y(), b.width(), b.height(), b.angle(), b.confidence());
            i++;
        }

        List<OcrResult> results = ocr.recognizeDetail(img);
        System.out.println("[diag] 识别块数=" + results.size());
        for (OcrResult r : results) {
            System.out.printf("  rec conf=%.2f angle=%.1f text='%s'%n",
                    r.confidence(), r.angle(), r.text());
        }
        System.out.println("[diag] 裁剪图已保存: " + outDir);
    }
}
