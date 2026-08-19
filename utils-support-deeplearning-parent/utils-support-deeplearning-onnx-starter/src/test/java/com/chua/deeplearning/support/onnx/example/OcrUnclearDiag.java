package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.Mat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 诊断三张"很不清楚的文字图片"：方向模型分类 → correct 是否旋转 → 识别。
 *
 * @since 4.0.0.42
 */
public final class OcrUnclearDiag {

    private OcrUnclearDiag() {
    }

    public static void main(String[] args) throws Exception {
        String[] files = {
                "很不清楚的文字图片用于测试文字高清修复模型.png",
                "很不清楚的文字图片用于测试文字高清修复模型1.png",
                "很不清楚的文字图片用于测试文字高清修复模型2.png"
        };
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> dirT = (ITranslator<Object, Object>)
                com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance()
                        .get("doc-orientation", ITranslator.class);

        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("doc-orientation")
                .build();

        for (String name : files) {
            byte[] img = Files.readAllBytes(Path.of("G:\\images", name));
            System.out.println("===== " + name + " =====");
            Mat m0 = ImageUtils.decode(img);
            System.out.println("  原图尺寸=" + m0.cols() + "x" + m0.rows());

            Object dr = dirT.translate(img);
            String cls = String.valueOf(dr.getClass().getMethod("getName").invoke(dr));
            double prob = (double) dr.getClass().getMethod("getProbability").invoke(dr);
            System.out.println("  方向分类=" + cls + " prob=" + String.format("%.3f", prob));

            byte[] corrected = ocr.correct(img);
            boolean rotated = !java.util.Arrays.equals(img, corrected);
            Mat m1 = ImageUtils.decode(corrected);
            System.out.println("  矫正后=" + (rotated ? "已旋转" : "未旋转") + " 尺寸=" + m1.cols() + "x" + m1.rows());
            m0.release();
            m1.release();

            List<OcrResult> results = ocr.recognizeDetail(img);
            System.out.println("  识别块数=" + results.size());
            for (OcrResult r : results) {
                System.out.printf("    [%.2f] '%s'%n", r.confidence(), r.text());
            }
        }
    }
}