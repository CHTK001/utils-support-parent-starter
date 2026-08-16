package com.chua.deeplearning.support.pytorch.example;

import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.image.ImageEnhancer;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;
import org.opencv.core.Mat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * AIAS face_restoration_sdk 完整复刻：retinaface 检测(5点) → 子图外扩 → 5点对齐 → GFPGAN 修复/超分。
 *
 * <pre>{@code
 *   FaceRestorationExample G:\images\黑白人物.jpg
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FaceRestorationExample {

    private FaceRestorationExample() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\黑白人物.jpg";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        @SuppressWarnings("unchecked")
        FaceDetector detector = FaceDetector.create("pytorch-retinaface");
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detector.detect(img);
        System.out.println("[retinaface] 人脸数=" + boxes.size() + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        for (int i = 0; i < boxes.size(); i++) {
            PredictRectangle box = boxes.get(i);
            System.out.println(String.format("[retinaface] #%d box=(%.0f,%.0f) %.0fx%.0f conf=%.2f kps=%d",
                    i, box.x(), box.y(), box.width(), box.height(), box.confidence(),
                    box.keypoints() == null ? 0 : box.keypoints().size()));
            if (box.keypoints() != null) {
                for (float[] kp : box.keypoints()) {
                    System.out.println("  kp=(" + String.format("%.1f", kp[0]) + "," + String.format("%.1f", kp[1]) + ")");
                }
            }
        }
        if (boxes.isEmpty()) {
            return;
        }
        PredictRectangle largest = boxes.get(0);
        for (PredictRectangle b : boxes) {
            if (b.width() * b.height() > largest.width() * largest.height()) {
                largest = b;
            }
        }

        // 子图外扩 + 5 点对齐
        Mat src = OpenCvImageUtils.decode(img);
        int iw = src.cols(), ih = src.rows();
        int x1 = (int) largest.x(), y1 = (int) largest.y();
        int x2 = x1 + (int) largest.width(), y2 = y1 + (int) largest.height();
        int newX1 = Math.max((int) (x1 + x1 * 0.5f - x2 * 0.5f), 0);
        int newX2 = Math.min((int) (x2 + x2 * 0.5f - x1 * 0.5f), iw - 1);
        int newY1 = Math.max((int) (y1 + y1 * 0.5f - y2 * 0.5f), 0);
        int newY2 = Math.min((int) (y2 + y2 * 0.5f - y1 * 0.5f), ih - 1);
        int cw = newX2 - newX1, ch = newY2 - newY1;
        Mat sub = new Mat(src, new org.opencv.core.Rect(newX1, newY1, cw, ch));

        java.util.List<float[]> kps = new java.util.ArrayList<>();
        for (float[] p : largest.keypoints()) {
            kps.add(new float[]{p[0] - newX1, p[1] - newY1});
        }
        Mat aligned = OpenCvImageUtils.alignFace(sub, kps, 512);
        Path alignOut = Path.of("G:\\images\\output\\restore_align.png");
        Files.write(alignOut, OpenCvImageUtils.encode(aligned));
        System.out.println("[align] 已输出: " + alignOut);
        src.release();
        sub.release();

        // GFPGAN 修复/超分
        ImageEnhancer gfpgan = ImageEnhancer.create("pytorch-gfpgan");
        byte[] face = OpenCvImageUtils.encode(aligned);
        aligned.release();
        long t1 = System.currentTimeMillis();
        byte[] restored = gfpgan.enhance(face);
        System.out.println("[gfpgan] 修复耗时=" + (System.currentTimeMillis() - t1) + "ms size=" + restored.length);
        Files.write(Path.of("G:\\images\\output\\restore_gfpgan.png"), restored);
        System.out.println("[gfpgan] 已输出: G:\\images\\output\\restore_gfpgan.png");
    }
}
