package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.face.FaceDetectionHit;
import com.chua.deeplearning.support.face.FacePipeline;
import com.chua.deeplearning.support.onnx.utils.OpenCvImageUtils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 人脸模型综合实测：检测 → 对裁剪人脸做对齐/修复/超分。
 *
 * <p>对 {@code G:\images} 人脸图逐张执行：scrfd 检测（画框输出 detect.jpg）；
 * 对检测出的最大人脸裁剪（对齐），并对裁剪图做 CodeFormer 修复、GFPGAN 超分。
 * 人群图（很多人小脸.jpg）只做检测。</p>
 *
 * <p>各结果独立输出到 {@code G:\images\output}，不修改原图。</p>
 *
 * <pre>{@code
 *   FaceDetectDrawExample
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FaceDetectDrawExample extends ExampleBase {

    /**
     * 输入图片目录
     */
    private static final String INPUT_DIR = "G:\\images";

    /**
     * 输出目录
     */
    private static final String OUTPUT_DIR = "G:\\images\\output";

    /**
     * 测试图片（G:\images 下的人脸图）
     */
    private static final String[] TEST_IMAGES = {
            "三个人.jpg",
            "黑白人物.jpg"
    };

    /**
     * 仅检测的图（人群图，不做对齐/修复/超分）
     */
    private static final String[] DETECT_ONLY_IMAGES = {
            "很多人小脸.jpg"
    };

    private FaceDetectDrawExample() {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(OUTPUT_DIR);
        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }

        FacePipeline pipeline = FacePipeline.builder()
                .detector("scrfd-face-detector")
                .restore("codeformer")
                .superResolution("gfpgan-face-super-resolution")
                .build();

        // 只检测的图
        for (String name : DETECT_ONLY_IMAGES) {
            Path in = Path.of(INPUT_DIR, name);
            if (!Files.exists(in)) {
                continue;
            }
            byte[] img = Files.readAllBytes(in);
            String base = name.substring(0, name.lastIndexOf('.'));
            System.out.println("===== " + name + " (仅检测) =====");
            detectAndDraw(pipeline, base, img);
            System.out.println();
        }

        // 完整链路：检测 → 裁剪(对齐) → 修复 → 超分
        for (String name : TEST_IMAGES) {
            Path in = Path.of(INPUT_DIR, name);
            if (!Files.exists(in)) {
                System.out.println("[face-ops] 跳过（不存在）: " + name);
                continue;
            }
            byte[] img = Files.readAllBytes(in);
            String base = name.substring(0, name.lastIndexOf('.'));
            System.out.println("===== " + name + " =====");

            // 1. 检测 + 画框
            List<FaceDetectionHit> hits = detectAndDraw(pipeline, base, img);

            // 2. 取最大人脸 → 裁剪(对齐)
            FaceDetectionHit largest = hits.stream()
                    .max((a, b) -> {
                        var wa = a.box().width() * a.box().height();
                        var wb = b.box().width() * b.box().height();
                        return Float.compare(wa, wb);
                    })
                    .orElse(null);
            if (largest == null || largest.faceImage() == null || largest.faceImage().length == 0) {
                System.out.println("[对齐] 无最大人脸");
                continue;
            }
            byte[] face = largest.faceImage();
            Path alignOut = Path.of(OUTPUT_DIR, base + "_align.png");
            Files.write(alignOut, face);
            System.out.println("[对齐] 已输出: " + alignOut + " (" + face.length + "B)");

            // 3. 修复（对裁剪人脸，非整图）
            long t2 = System.currentTimeMillis();
            byte[] restored = pipeline.restore(face);
            long tRestore = System.currentTimeMillis() - t2;
            if (restored != null && restored.length > 0) {
                Path restoreOut = Path.of(OUTPUT_DIR, base + "_restore.png");
                Files.write(restoreOut, restored);
                System.out.println("[修复] 已输出: " + restoreOut + " (" + restored.length + "B) 耗时=" + tRestore + "ms");
            } else {
                System.out.println("[修复] 无结果 耗时=" + tRestore + "ms");
            }

            // 4. 超分（对裁剪人脸，非整图）
            long t3 = System.currentTimeMillis();
            byte[] upscaled = pipeline.superResolution(face);
            long tSuper = System.currentTimeMillis() - t3;
            if (upscaled != null && upscaled.length > 0) {
                Path superOut = Path.of(OUTPUT_DIR, base + "_super.png");
                Files.write(superOut, upscaled);
                System.out.println("[超分] 已输出: " + superOut + " (" + upscaled.length + "B) 耗时=" + tSuper + "ms");
            } else {
                System.out.println("[超分] 无结果 耗时=" + tSuper + "ms");
            }
            System.out.println();
        }
        printResult("face-ops", "onnx", "scrfd+codeformer+gfpgan", 0);
    }

    /**
     * 检测并画框输出。
     *
     * @param pipeline 管线
     * @param base     文件名前缀
     * @param img      图片字节
     * @return 检测命中
     */
    private static List<FaceDetectionHit> detectAndDraw(FacePipeline pipeline, String base, byte[] img) throws Exception {
        long t0 = System.currentTimeMillis();
        List<FaceDetectionHit> hits = pipeline.detectPipeline(img);
        long tDetect = System.currentTimeMillis() - t0;
        System.out.println("[检测] 人脸数=" + hits.size() + " 耗时=" + tDetect + "ms");
        for (int i = 0; i < hits.size(); i++) {
            var box = hits.get(i).box();
            System.out.println(String.format("[检测]   #%d box=(%.0f,%.0f) %.0fx%.0f conf=%.2f",
                    i, box.x(), box.y(), box.width(), box.height(), box.confidence()));
        }
        Mat src = OpenCvImageUtils.decode(img);
        if (src != null) {
            for (int i = 0; i < hits.size(); i++) {
                var box = hits.get(i).box();
                Imgproc.rectangle(src, new Point(box.x(), box.y()),
                        new Point(box.x() + box.width(), box.y() + box.height()),
                        new Scalar(0, 255, 0), 2);
                Imgproc.putText(src, "face-" + i + " " + String.format("%.2f", box.confidence()),
                        new Point(box.x(), box.y() - 6),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(0, 255, 0), 2);
            }
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".jpg", src, mob);
            Path detectOut = Path.of(OUTPUT_DIR, base + "_detect.jpg");
            Files.write(detectOut, mob.toArray());
            src.release();
            mob.release();
            System.out.println("[检测] 已输出: " + detectOut);
        }
        return hits;
    }
}
