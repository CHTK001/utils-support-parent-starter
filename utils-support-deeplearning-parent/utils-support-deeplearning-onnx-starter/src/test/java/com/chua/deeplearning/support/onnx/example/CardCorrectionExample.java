package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 卡片矫正检测验证（读光票证检测矫正）。
 *
 * <pre>{@code
 *   CardCorrectionExample G:\images\card_test.jpg
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CardCorrectionExample extends ExampleBase {

    private CardCorrectionExample() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\card_test.jpg";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        ImageDetector detector = ImageDetector.create("card-correction-detector");
        long t0 = System.currentTimeMillis();
        List<DetectionInfo> corners = detector.detect(img);
        System.out.println("[card-correction] 图片=" + imagePath + " 角点数=" + corners.size()
                + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        for (DetectionInfo c : corners) {
            System.out.println(String.format("       角点[%s] conf=%.3f box=(%.0f,%.0f) %.0fx%.0f",
                    c.label(), c.confidence(), c.x(), c.y(), c.width(), c.height()));
        }
        // 透视矫正出图
        if (!corners.isEmpty()) {
            long t1 = System.currentTimeMillis();
            com.chua.deeplearning.support.onnx.classification.CardCorrectionTranslator translator =
                    com.chua.deeplearning.support.onnx.classification.CardCorrectionTranslator.shared();
            byte[] corrected = translator.correct(img);
            System.out.println("[card-correction] 矫正耗时=" + (System.currentTimeMillis() - t1) + "ms");
            if (corrected != null) {
                java.nio.file.Path out = java.nio.file.Path.of("G:\\images\\output\\card_corrected.png");
                java.nio.file.Files.write(out, corrected);
                System.out.println("[card-correction] 已输出矫正图: " + out);
            } else {
                System.out.println("[card-correction] 未检测到卡片，无矫正图");
            }
        }
        printResult("card-correction", "onnx", "card-correction-detector", t0);
    }
}
