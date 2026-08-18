package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OCR 完整管线示例（检测 → 矫正 → 修复 → 识别）。
 *
 * <p>验证 {@link OcrPipeline} 的完整能力编排：
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
        // 注：文字修复（restorerModel="text-bsr"）为可选环节，对裁剪文本块执行 4x 超分，
        // CPU 逐块推理较慢（21 块约 4 分钟），适合模糊/低清文字场景，实时识别建议不启用。
        OcrPipeline ocr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("doc-orientation")
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
