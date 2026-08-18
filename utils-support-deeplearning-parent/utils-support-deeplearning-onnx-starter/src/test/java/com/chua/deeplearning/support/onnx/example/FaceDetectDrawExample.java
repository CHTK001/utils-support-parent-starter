package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.face.FaceDetectionHit;
import com.chua.deeplearning.support.face.FacePipeline;
import com.chua.deeplearning.support.utils.ImageUtils;
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

            // 2. 取最大人脸 → 关键点 5 点仿射对齐（AIAS 同款：子图外扩裁剪 + 5 点对齐到 512）
            FaceDetectionHit largest = hits.stream()
                    .max((a, b) -> {
                        var wa = a.box().width() * a.box().height();
                        var wb = b.box().width() * b.box().height();
                        return Float.compare(wa, wb);
                    })
                    .orElse(null);
            if (largest == null || largest.box().keypoints() == null || largest.box().keypoints().isEmpty()) {
                System.out.println("[对齐] 无最大人脸或关键点");
                continue;
            }
            byte[] face = alignCrop(img, largest);
            if (face == null) {
                System.out.println("[对齐] 对齐失败");
                continue;
            }
            Path alignOut = Path.of(OUTPUT_DIR, base + "_align.png");
            Files.write(alignOut, face);
            System.out.println("[对齐] 已输出: " + alignOut + " (" + face.length + "B, 子图外扩+5点对齐512)");

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
            var kps = box.keypoints();
            if (kps != null && !kps.isEmpty()) {
                StringBuilder sb = new StringBuilder("[检测]     kps=");
                for (float[] p : kps) {
                    sb.append(String.format("(%.0f,%.0f) ", p[0], p[1]));
                }
                System.out.println(sb.toString().trim());
            }
        }
        Mat src = ImageUtils.decode(img);
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

    /**
     * AIAS 同款：按检测框外扩 100% 裁剪人脸子图，再对子图内关键点做 5 点仿射对齐到 512。
     *
     * @param img  整图字节
     * @param hit  检测命中（含框与关键点）
     * @return 对齐后的 PNG 字节；失败返回 null
     */
    private static byte[] alignCrop(byte[] img, FaceDetectionHit hit) {
        try {
            var box = hit.box();
            int iw, ih;
            Mat tmp = ImageUtils.decode(img);
            iw = tmp.cols();
            ih = tmp.rows();
            tmp.release();

            // getSubImageRect(factor=1.0)：外扩 100%
            int x1 = (int) box.x(), y1 = (int) box.y();
            int x2 = x1 + (int) box.width(), y2 = y1 + (int) box.height();
            int newX1 = Math.max((int) (x1 + x1 * 1.0f / 2 - x2 * 1.0f / 2), 0);
            int newX2 = Math.min((int) (x2 + x2 * 1.0f / 2 - x1 * 1.0f / 2), iw - 1);
            int newY1 = Math.max((int) (y1 + y1 * 1.0f / 2 - y2 * 1.0f / 2), 0);
            int newY2 = Math.min((int) (y2 + y2 * 1.0f / 2 - y1 * 1.0f / 2), ih - 1);
            int cw = newX2 - newX1, ch = newY2 - newY1;
            if (cw <= 0 || ch <= 0) {
                return null;
            }
            Mat src = ImageUtils.decode(img);
            Mat sub = new Mat(src, new org.opencv.core.Rect(newX1, newY1, cw, ch));

            // 关键点换算到子图坐标系
            java.util.List<float[]> kps = new java.util.ArrayList<>();
            for (float[] p : box.keypoints()) {
                kps.add(new float[]{p[0] - newX1, p[1] - newY1});
            }
            Mat aligned = ImageUtils.alignFace(sub, kps, 512);
            byte[] out = ImageUtils.encode(aligned);
            src.release();
            sub.release();
            aligned.release();
            return out;
        } catch (Exception e) {
            System.out.println("[对齐] 异常: " + e.getMessage());
            return null;
        }
    }
}
