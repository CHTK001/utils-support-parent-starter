package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.face.FacePipeline;
import com.chua.deeplearning.support.model.PredictRectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FLRGB 活体链路验证：onnx-retinaface 检测 → FLRGB 活体判定。
 *
 * <pre>{@code
 *   LivenessPipelineExample G:\images\三个人.jpg
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class LivenessPipelineExample extends ExampleBase {

    private LivenessPipelineExample() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\三个人.jpg";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        FacePipeline pipeline = FacePipeline.builder()
                .detector("onnx-retinaface")
                .liveness("face-liveness-flrgb")
                .requireLive(true)
                .build();

        long t0 = System.currentTimeMillis();
        var boxes = pipeline.detectBoxes(img);
        System.out.println("[liveness-pipeline] 检测框数=" + boxes.size());
        if (!boxes.isEmpty()) {
            var box = boxes.get(0);
            byte[] face = com.chua.deeplearning.support.utils.ImageCropUtils.crop(img, box);
            System.out.println("[liveness-pipeline] 裁剪人脸 bytes=" + (face == null ? "null" : face.length));
            com.chua.deeplearning.support.liveness.LivenessDetector ld =
                    com.chua.deeplearning.support.liveness.LivenessDetector.create("face-liveness-flrgb");
            try {
                System.out.println("[liveness-pipeline] isLive=" + ld.isLive(face));
            } catch (Exception e) {
                System.out.println("[liveness-pipeline] isLive ERR: " + e.getMessage());
            }
            try {
                System.out.println("[liveness-pipeline] liveScore=" + ld.liveScore(face));
            } catch (Exception e) {
                System.out.println("[liveness-pipeline] liveScore ERR: " + e.getMessage());
            }
        }
        var hits = pipeline.detectPipeline(img);
        System.out.println("[liveness-pipeline] 图片=" + imagePath + " 检测耗时=" + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("       人脸数: " + hits.size());
        for (var hit : hits) {
            PredictRectangle box = hit.box();
            System.out.println(String.format("       box=(%.0f,%.0f) %.0fx%.0f 活体=%s 分数=%.3f",
                    box.x(), box.y(), box.width(), box.height(),
                    hit.live() ? "Y" : "N", hit.liveScore()));
        }
        printResult("liveness-pipeline", "onnx", "face-liveness-flrgb", t0);
    }
}
