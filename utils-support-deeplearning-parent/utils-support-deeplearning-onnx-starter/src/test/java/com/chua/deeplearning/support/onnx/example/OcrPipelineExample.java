package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrEngine;
import com.chua.deeplearning.support.ocr.OcrResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OCR 完整管线示例（检测 → 矫正 → 修复 → 识别）。
 *
 * <p>验证 {@link OcrEngine} 的完整能力编排：
 * 检测（paddleocrv6-det）→ 方向矫正（pp-word-rotate）→ 文字修复（text-bsr）→ 识别（paddleocrv6-rec）。</p>
 *
 * <pre>{@code
 *   OcrPipelineExample
 *   OcrPipelineExample G:\images\车票.png
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OcrPipelineExample extends ExampleBase {

    private OcrPipelineExample() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\车票.png";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        // 完整链路：检测 + 方向矫正 + 识别（medium 精度更高）。
        // 注：文字修复（restorerModel="text-bsr"）为可选环节，对超长文本块 CPU 推理极慢，
        // 适合整页/大字模糊场景，逐块实时识别时建议不启用。
        OcrEngine ocr = OcrEngine.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .directionModel("pp-word-rotate")
                .build();

        long t0 = System.currentTimeMillis();
        List<OcrResult> results = ocr.recognizeDetail(img);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("[ocr-pipeline] 图片: " + imagePath);
        System.out.println("       文本块数: " + results.size() + " 耗时=" + elapsed + "ms");
        for (OcrResult r : results) {
            System.out.println("       text='" + r.text() + "' conf=" + String.format("%.2f", r.confidence())
                    + " box=" + (r.boundingBox() == null ? "null" : String.format("(%.0f,%.0f) %.0fx%.0f",
                    r.boundingBox().x(), r.boundingBox().y(),
                    r.boundingBox().width(), r.boundingBox().height())));
        }
        System.out.println();
    }
}
