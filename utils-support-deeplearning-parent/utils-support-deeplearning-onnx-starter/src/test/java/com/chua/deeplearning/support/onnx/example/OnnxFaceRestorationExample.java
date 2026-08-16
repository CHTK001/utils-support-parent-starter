package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.image.ImageEnhancer;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ONNX 人脸链路验证：onnx-retinaface 检测(5点) → 子图外扩 → 5点对齐 →
 * onnx-gfpgan 修复 → onnx-parsenet 分割 → 逆仿射贴回原图。
 *
 * <p>全链路 ONNX 引擎（gfpgan 为重写 forward 结构版，规避 double 计算域，无偏色）。</p>
 *
 * <pre>{@code
 *   OnnxFaceRestorationExample G:\images\三个人.jpg
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OnnxFaceRestorationExample {

    /**
     * 输出目录。
     */
    private static final String OUT_DIR = "G:\\images\\output";

    private OnnxFaceRestorationExample() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\三个人.jpg";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        FaceDetector detector = FaceDetector.create("onnx-retinaface");
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detector.detect(img);
        System.out.println("[detect] 模型=onnx-retinaface 人脸数=" + boxes.size()
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

        Mat src = OpenCvImageUtils.decode(img);
        int iw = src.cols(), ih = src.rows();

        // 画框输出
        Mat draw = src.clone();
        for (PredictRectangle box : boxes) {
            org.opencv.core.Rect r = new org.opencv.core.Rect(
                    (int) box.x(), (int) box.y(), (int) box.width(), (int) box.height());
            org.opencv.imgproc.Imgproc.rectangle(draw, r, new Scalar(0, 200, 0), 2);
            if (box.keypoints() != null) {
                for (float[] kp : box.keypoints()) {
                    org.opencv.imgproc.Imgproc.circle(draw, new Point(kp[0], kp[1]), 2, new Scalar(0, 0, 255), -1);
                }
            }
        }
        Path detectOut = Path.of(OUT_DIR, "三人_detect_onnx.jpg");
        Files.write(detectOut, OpenCvImageUtils.encode(draw));
        System.out.println("[detect] 已输出: " + detectOut);
        draw.release();

        ImageEnhancer gfpgan = ImageEnhancer.create("onnx-gfpgan");
        ImageEnhancer parsenet = ImageEnhancer.create("onnx-parsenet");

        for (int i = 0; i < boxes.size(); i++) {
            PredictRectangle box = boxes.get(i);
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
            Mat affine = OpenCvImageUtils.estimateFaceAffine512(kps);
            Mat aligned = new Mat();
            org.opencv.imgproc.Imgproc.warpAffine(sub, aligned, affine,
                    new org.opencv.core.Size(512, 512),
                    org.opencv.imgproc.Imgproc.INTER_CUBIC, 0, new Scalar(135, 133, 132));
            Path alignOut = Path.of(OUT_DIR, "onnx_face" + i + "_align.png");
            Files.write(alignOut, OpenCvImageUtils.encode(aligned));
            System.out.println("[align] #" + i + " 已输出: " + alignOut);

            // onnx-gfpgan 修复（重写结构版，无偏色）
            byte[] face = OpenCvImageUtils.encode(aligned);
            long t1 = System.currentTimeMillis();
            byte[] restored = gfpgan.enhance(face);
            Path restoreOut = Path.of(OUT_DIR, "onnx_face" + i + "_restore.png");
            Files.write(restoreOut, restored);
            System.out.println("[gfpgan] #" + i + " 模型=onnx-gfpgan 耗时="
                    + (System.currentTimeMillis() - t1) + "ms 已输出: " + restoreOut);
            Mat restoredMat = OpenCvImageUtils.decode(restored);

            // onnx parsenet 分割
            long t2 = System.currentTimeMillis();
            byte[] maskBytes = parsenet.enhance(restored);
            Mat softMask = OpenCvImageUtils.decode(maskBytes);
            if (softMask.channels() > 1) {
                Mat g = new Mat();
                org.opencv.imgproc.Imgproc.cvtColor(softMask, g, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
                softMask.release();
                softMask = g;
            }
            Path maskOut = Path.of(OUT_DIR, "onnx_face" + i + "_mask.png");
            Files.write(maskOut, OpenCvImageUtils.encode(softMask));
            System.out.println("[parsenet] #" + i + " 模型=onnx-parsenet 耗时="
                    + (System.currentTimeMillis() - t2) + "ms 已输出: " + maskOut);

            // 贴回（修复后人脸 + mask 逆仿射融合）
            Mat pasted = OpenCvImageUtils.pasteFace(src, restoredMat, softMask, affine);
            Path pasteOut = Path.of(OUT_DIR, "onnx_face" + i + "_pasted.png");
            Files.write(pasteOut, OpenCvImageUtils.encode(pasted));
            System.out.println("[paste] #" + i + " 已输出: " + pasteOut);

            restoredMat.release();
            pasted.release();
            softMask.release();
            aligned.release();
            sub.release();
            affine.release();
        }
        src.release();
        System.out.println("[done] ONNX 链路完成，共 " + boxes.size() + " 张人脸");
    }
}
