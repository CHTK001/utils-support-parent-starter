package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 大角度矫正验证：用原图旋转 35° 生成倾斜图，对比"轴对齐裁剪直接 rec"与
 * "旋转矩形扶正裁剪 cropRotated" 的识别效果。
 *
 * @since 4.0.0.42
 */
public final class OcrBigAngleDiag {

    /** 创建 OcrBigAngleDiag 实例 */
    private OcrBigAngleDiag() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        byte[] img0 = Files.readAllBytes(Path.of("G:\\images\\有文字图片.png"));
        Mat src = ImageUtils.decode(img0);
        Mat rotated = new Mat();
        Point center = new Point(src.cols() / 2.0, src.rows() / 2.0);
        Mat rot = Imgproc.getRotationMatrix2D(center, 35, 1.0);
        // 扩大画布避免旋转裁边
        Size outSize = new Size(src.cols() * 1.7, src.rows() * 1.7);
        Mat rotBig = new Mat();
        Imgproc.warpAffine(src, rotBig, rot, outSize, Imgproc.INTER_CUBIC,
                org.opencv.core.Core.BORDER_CONSTANT, new Scalar(255, 255, 255));
        // 平移矩阵让内容居中
        double tx = (outSize.width - src.cols()) / 2.0;
        double ty = (outSize.height - src.rows()) / 2.0;
        rot.put(0, 2, rot.get(0, 2)[0] + tx);
        rot.put(1, 2, rot.get(1, 2)[0] + ty);
        Mat finalMat = new Mat();
        Imgproc.warpAffine(src, finalMat, rot, outSize, Imgproc.INTER_CUBIC,
                org.opencv.core.Core.BORDER_CONSTANT, new Scalar(255, 255, 255));
        byte[] bigImg = ImageUtils.encode(finalMat);
        String bigPath = "G:\\images\\output\\big_angle_35.png";
        Files.write(Path.of(bigPath), bigImg);
        System.out.println("[diag] 已生成 35° 倾斜图: " + bigPath);

        // 方案 A：直接 rec（轴对齐裁剪）
        OcrPipeline rawOcr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .cropRotateThreshold(-1f)
                .build();
        System.out.println("[A] 禁用大角度矫正: ");
        for (OcrResult r : rawOcr.recognizeDetail(bigImg)) {
            System.out.printf("      [%.2f] angle=%.1f '%s'%n", r.confidence(), r.angle(), r.text());
        }

        // 方案 B：大角度矫正
        OcrPipeline rotOcr = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .cropRotateThreshold(25f)
                .build();
        System.out.println("[B] 大角度矫正(25°): ");
        for (OcrResult r : rotOcr.recognizeDetail(bigImg)) {
            System.out.printf("      [%.2f] angle=%.1f '%s'%n", r.confidence(), r.angle(), r.text());
        }

        // 打印检测框角度，确认确实 >25°
        ImageDetector det = rawOcr.detector();
        List<DetectionInfo> boxes = det.detect(ImageUtils.decode(bigImg) == null ? bigImg : bigImg);
        System.out.println("[diag] 检测框角度: ");
        for (DetectionInfo b : boxes) {
            System.out.printf("      angle=%.1f rw=%.0f rh=%.0f%n", b.angle(), b.rw(), b.rh());
        }
        finalMat.release();
        rot.release();
        src.release();
    }
}
