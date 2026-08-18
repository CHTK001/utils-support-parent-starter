package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 单图 OCR 诊断：输出检测框明细 + 每块识别文本/置信度 + 裁剪图，定位识别失败环节。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OcrSingleDiag {

    private OcrSingleDiag() {
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "G:\\images\\气象文字.png";
        System.out.println("[diag] 图片=" + path);

        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("doc-orientation")
                .build();

        byte[] img = Files.readAllBytes(Path.of(path));
        OcrPipeline.OcrRecognizeResult rr = ocr.recognizeDetailWithImage(img);

        byte[] corrected = rr.image();
        var cimg = com.chua.deeplearning.support.utils.ImageUtils.decode(corrected);
        System.out.println("[diag] 矫正后尺寸=" + cimg.cols() + "x" + cimg.rows());
        cimg.release();

        // 检测明细
        ImageDetector det = ocr.detector();
        List<DetectionInfo> boxes = det.detect(corrected);
        System.out.println("[diag] 检测框数=" + boxes.size());
        int i = 0;
        for (DetectionInfo b : boxes) {
            System.out.printf("  det[%02d] (%.0f,%.0f) %.0fx%.0f angle=%.1f conf=%.2f%n",
                    i++, b.x(), b.y(), b.width(), b.height(), b.angle(), b.confidence());
        }

        // 识别明细
        List<OcrResult> results = rr.results();
        System.out.println("[diag] 识别块数=" + results.size());
        i = 0;
        for (OcrResult r : results) {
            PredictRectangle b = r.boundingBox();
            String box = b == null ? "null"
                    : String.format("(%.0f,%.0f) %.0fx%.0f", b.x(), b.y(), b.width(), b.height());
            System.out.printf("  rec[%02d] conf=%.2f %-28s text='%s'%n",
                    i++, r.confidence(), box, r.text());
        }
    }
}