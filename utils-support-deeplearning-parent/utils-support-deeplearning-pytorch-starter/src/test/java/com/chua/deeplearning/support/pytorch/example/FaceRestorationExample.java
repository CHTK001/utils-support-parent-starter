package com.chua.deeplearning.support.pytorch.example;

import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.image.ImageEnhancer;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AIAS face_restoration_sdk 完整复刻（多人脸版）：retinaface 检测(5点) → 子图外扩 → 5点对齐 →
 * GFPGAN 修复 → parsenet 分割 → 逆仿射贴回原图。
 *
 * <p>检测/修复/分割引擎通过模型 ID 配置，支持混合引擎（如检测 pytorch-retinaface + 修复 pytorch-gfpgan）。</p>
 *
 * <pre>{@code
 *   FaceRestorationExample G:\images\三个人.jpg
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class FaceRestorationExample {

    /**
     * 输出目录。
     */
    private static final String OUT_DIR = "G:\\images\\output";

    /** 创建 FaceRestorationExample 实例 */
    private FaceRestorationExample() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\三个人.jpg";
        String detectorId = args.length > 1 ? args[1] : "pytorch-retinaface";
        String gfpganId = args.length > 2 ? args[2] : "pytorch-gfpgan";
        String parsenetId = args.length > 3 ? args[3] : "pytorch-parsenet";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        // 检测引擎
        FaceDetector detector = FaceDetector.create(detectorId);
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detector.detect(img);
        System.out.println("[detect] 模型=" + detectorId + " 人脸数=" + boxes.size()
                + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        if (boxes.isEmpty()) {
            System.out.println("[detect] 未检测到人脸");
            return;
        }
        for (int i = 0; i < boxes.size(); i++) {
            PredictRectangle box = boxes.get(i);
            System.out.println(String.format("[detect] #%d box=(%.0f,%.0f) %.0fx%.0f conf=%.2f kps=%d",
                    i, box.x(), box.y(), box.width(), box.height(), box.confidence(),
                    box.keypoints() == null ? 0 : box.keypoints().size()));
            if (box.keypoints() != null) {
                for (float[] kp : box.keypoints()) {
                    System.out.println("  kp=(" + String.format("%.1f", kp[0]) + "," + String.format("%.1f", kp[1]) + ")");
                }
            }
        }

        Mat src = ImageUtils.decode(img);
        int iw = src.cols(), ih = src.rows();

        // 画框输出
        Mat draw = src.clone();
        for (PredictRectangle box : boxes) {
            org.opencv.core.Rect r = new org.opencv.core.Rect(
                    (int) box.x(), (int) box.y(), (int) box.width(), (int) box.height());
            org.opencv.imgproc.Imgproc.rectangle(draw, r,
                    new Scalar(0, 200, 0), 2);
            if (box.keypoints() != null) {
                for (float[] kp : box.keypoints()) {
                    org.opencv.imgproc.Imgproc.circle(draw,
                            new Point(kp[0], kp[1]), 2, new Scalar(0, 0, 255), -1);
                }
            }
        }
        Path detectOut = Path.of(OUT_DIR, "三人_detect_pt.jpg");
        Files.write(detectOut, ImageUtils.encode(draw));
        System.out.println("[detect] 已输出: " + detectOut);
        draw.release();

        // 修复/分割引擎（混合引擎：各阶段独立配置）
        ImageEnhancer gfpgan = ImageEnhancer.create(gfpganId);
        ImageEnhancer parsenet = ImageEnhancer.create(parsenetId);

        for (int i = 0; i < boxes.size(); i++) {
            PredictRectangle box = boxes.get(i);
            // 子图外扩 100%（AIAS 同款）
            int x1 = (int) box.x(), y1 = (int) box.y();
            int x2 = x1 + (int) box.width(), y2 = y1 + (int) box.height();
            int newX1 = Math.max((int) (x1 + x1 * 0.5f - x2 * 0.5f), 0);
            int newX2 = Math.min((int) (x2 + x2 * 0.5f - x1 * 0.5f), iw - 1);
            int newY1 = Math.max((int) (y1 + y1 * 0.5f - y2 * 0.5f), 0);
            int newY2 = Math.min((int) (y2 + y2 * 0.5f - y1 * 0.5f), ih - 1);
            int cw = newX2 - newX1, ch = newY2 - newY1;
            if (cw <= 0 || ch <= 0) {
                System.out.println("[align] #" + i + " 子图越界跳过");
                continue;
            }
            Mat sub = new Mat(src, new Rect(newX1, newY1, cw, ch));

            List<float[]> kps = new ArrayList<>();
            if (box.keypoints() != null) {
                for (float[] p : box.keypoints()) {
                    kps.add(new float[]{p[0] - newX1, p[1] - newY1});
                }
            }
            if (kps.size() < 5) {
                System.out.println("[align] #" + i + " 关键点不足(" + kps.size() + ")跳过");
                sub.release();
                continue;
            }

            // 5 点 SVD 仿射对齐（保留矩阵供贴回）
            Mat affine = ImageUtils.estimateFaceAffine512(kps);
            Mat aligned = new Mat();
            org.opencv.imgproc.Imgproc.warpAffine(sub, aligned, affine,
                    new org.opencv.core.Size(512, 512),
                    org.opencv.imgproc.Imgproc.INTER_CUBIC, 0, new Scalar(135, 133, 132));
            Path alignOut = Path.of(OUT_DIR, "face" + i + "_align.png");
            Files.write(alignOut, ImageUtils.encode(aligned));
            System.out.println("[align] #" + i + " 已输出: " + alignOut);

            // 修复
            byte[] face = ImageUtils.encode(aligned);
            long t1 = System.currentTimeMillis();
            byte[] restored = gfpgan.enhance(face);
            Path restoreOut = Path.of(OUT_DIR, "face" + i + "_restore.png");
            Files.write(restoreOut, restored);
            System.out.println("[gfpgan] #" + i + " 模型=" + gfpganId + " 耗时="
                    + (System.currentTimeMillis() - t1) + "ms 已输出: " + restoreOut);

            // 分割软 mask
            long t2 = System.currentTimeMillis();
            byte[] maskBytes = parsenet.enhance(restored);
            Mat softMask = ImageUtils.decode(maskBytes);
            if (softMask.channels() > 1) {
                Mat g = new Mat();
                org.opencv.imgproc.Imgproc.cvtColor(softMask, g, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
                softMask.release();
                softMask = g;
            }
            Path maskOut = Path.of(OUT_DIR, "face" + i + "_mask.png");
            Files.write(maskOut, ImageUtils.encode(softMask));
            System.out.println("[parsenet] #" + i + " 模型=" + parsenetId + " 耗时="
                    + (System.currentTimeMillis() - t2) + "ms 已输出: " + maskOut);

            // 逆仿射贴回原图 + mask 融合
            Mat restoredMat = ImageUtils.decode(restored);
            Mat pasted = ImageUtils.pasteFace(src, restoredMat, softMask, affine);
            Path pasteOut = Path.of(OUT_DIR, "face" + i + "_pasted.png");
            Files.write(pasteOut, ImageUtils.encode(pasted));
            System.out.println("[paste] #" + i + " 已输出: " + pasteOut);

            pasted.release();
            restoredMat.release();
            softMask.release();
            aligned.release();
            sub.release();
            affine.release();
        }
        src.release();
        System.out.println("[done] 完成，共 " + boxes.size() + " 张人脸");
    }
}
