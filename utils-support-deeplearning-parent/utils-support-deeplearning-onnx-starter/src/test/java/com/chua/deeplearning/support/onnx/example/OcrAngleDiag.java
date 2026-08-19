package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.translator.ITranslator;
import org.opencv.core.Mat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OCR 旋转诊断：验证车票各旋转角度的方向分类 → 整图矫正 → 检测框角度 → 识别。
 *
 * @since 4.0.0.42
 */
public final class OcrAngleDiag {

    private OcrAngleDiag() {
    }

    public static void main(String[] args) throws Exception {
        String dir = "G:\\images";
        String[] files = {"车票.png", "车票ticket_90.png", "车票ticket_180.png", "车票ticket_270.png"};

        String directionModel = args.length > 0 ? args[0] : "doc-orientation";
        System.out.println("[diag] 方向模型=" + directionModel);

        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction(directionModel)
                .build();

        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> dirT = (ITranslator<Object, Object>)
                com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance()
                        .get(directionModel, ITranslator.class);

        for (String name : files) {
            byte[] img = Files.readAllBytes(Path.of(dir, name));
            System.out.println("===== " + name + " =====");

            Object dr = dirT.translate(img);
            System.out.println("  方向分类: " + describeDirection(dr));

            byte[] corrected = ocr.correct(img);
            boolean rotated = !java.util.Arrays.equals(img, corrected);
            System.out.println("  整图矫正: " + (rotated ? "已旋转" : "未旋转(跳过或0°)"));
            Mat correctedMat = com.chua.deeplearning.support.utils.ImageUtils.decode(corrected);
            System.out.println("  矫正后尺寸: " + correctedMat.cols() + "x" + correctedMat.rows());
            correctedMat.release();

            ImageDetector det = ocr.detector();
            List<DetectionInfo> boxes = det.detect(corrected);
            System.out.println("  检测框数: " + boxes.size());
            int shown = 0;
            for (DetectionInfo b : boxes) {
                if (shown++ >= 3) break;
                System.out.printf("    box: (%.0f,%.0f) %.0fx%.0f angle=%.1f conf=%.2f%n",
                        b.x(), b.y(), b.width(), b.height(), b.angle(), b.confidence());
            }

            List<OcrResult> results = ocr.recognizeDetail(img);
            System.out.println("  识别结果(前3): ");
            int n = 0;
            for (OcrResult r : results) {
                if (n++ >= 3) break;
                System.out.printf("    text='%s' conf=%.2f%n", r.text(), r.confidence());
            }
            System.out.println();
        }
    }

    private static String describeDirection(Object dr) {
        try {
            java.lang.reflect.Method gm = dr.getClass().getMethod("getName");
            java.lang.reflect.Method pm = dr.getClass().getMethod("getProbability");
            return gm.invoke(dr) + " prob=" + String.format("%.3f", pm.invoke(dr));
        } catch (Exception e) {
            return String.valueOf(dr);
        }
    }
}