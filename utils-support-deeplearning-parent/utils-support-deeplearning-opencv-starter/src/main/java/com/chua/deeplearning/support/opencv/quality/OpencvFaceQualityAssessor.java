package com.chua.deeplearning.support.opencv.quality;

import com.chua.deeplearning.support.model.FaceQualityInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.opencv.OpencvModelTranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;

/**
 * 纯 打开cv 人脸质量评估翻译器。
 * <p>结合 Haar 人脸检测与模糊/亮度/尺寸指标，判断人脸是否可用于后续识别。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OpencvFaceQualityAssessor extends OpencvModelTranslator {

    /**
     * 人脸级联模型路径。
     */
    private final String modelPath;

    /**
     * 人脸级联分类器。
     */
    private final CascadeClassifier classifier;

    /**
     * 模糊阈值。
     */
    private final double blurThreshold;

    /**
     * 最小人脸面积比。
     */
    private final float minFaceRatio;

    /**
     * 最低亮度。
     */
    private final double minBrightness;

    /**
     * 最高亮度。
     */
    private final double maxBrightness;

    /**
     * 构造评估器。
     *
     * @param modelPath 人脸模型路径
     */
    public OpencvFaceQualityAssessor(String modelPath) {
        this(modelPath, 80.0, 0.05f, 40.0, 220.0);
    }

    /**
     * 构造评估器。
     *
     * @param modelPath      人脸模型路径
     * @param blurThreshold  模糊阈值
     * @param minFaceRatio   最小人脸面积比
     * @param minBrightness  最低亮度
     * @param maxBrightness  最高亮度
     */
    public OpencvFaceQualityAssessor(String modelPath,
                                     double blurThreshold,
                                     float minFaceRatio,
                                     double minBrightness,
                                     double maxBrightness) {
        super("opencv-face-quality");
        this.modelPath = modelPath;
        this.blurThreshold = blurThreshold;
        this.minFaceRatio = minFaceRatio;
        this.minBrightness = minBrightness;
        this.maxBrightness = maxBrightness;
        File modelFile = resolveModelPath(modelPath);
        this.classifier = new CascadeClassifier(modelFile.getAbsolutePath());
        if (classifier.empty()) {
            throw new IllegalStateException("加载人脸模型失败: " + modelFile.getAbsolutePath());
        }
        log.info("OpencvFaceQualityAssessor 初始化完成: {}", modelFile.getAbsolutePath());
    }

    @Override
    /**
     * 执行translate
    */
    protected Object doTranslate(Object input) {
        if (!(input instanceof byte[] imageBytes)) {
            throw new IllegalArgumentException("仅支持 byte[] 输入");
        }

        Mat src = bytesToMat(imageBytes);
        Mat gray = new Mat();
        Mat laplacian = new Mat();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.equalizeHist(gray, gray);

            MatOfRect faces = new MatOfRect();
            classifier.detectMultiScale(gray, faces, 1.1, 3, 0, new Size(30, 30), new Size());
            Rect[] rects = faces.toArray();

            PredictRectangle face = null;
            float faceAreaRatio = 0f;
            if (rects.length > 0) {
                Rect max = rects[0];
                for (Rect rect : rects) {
                    if (rect.area() > max.area()) {
                        max = rect;
                    }
                }
                face = new PredictRectangle(
                        (float) max.x, (float) max.y, (float) max.width, (float) max.height,
                        1.0f, 0, "face"
                );
                faceAreaRatio = (float) (max.area() / (src.cols() * (double) src.rows()));
            }

            MatOfDouble mean = new MatOfDouble();
            MatOfDouble stddev = new MatOfDouble();
            Core.meanStdDev(gray, mean, stddev);
            double brightness = mean.get(0, 0)[0];
            double contrast = stddev.get(0, 0)[0];

            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            MatOfDouble lapMean = new MatOfDouble();
            MatOfDouble lapStd = new MatOfDouble();
            Core.meanStdDev(laplacian, lapMean, lapStd);
            double blurScore = Math.pow(lapStd.get(0, 0)[0], 2);

            boolean faceOk = face != null;
            boolean sizeOk = faceAreaRatio >= minFaceRatio;
            boolean sharpnessOk = blurScore >= blurThreshold;
            boolean brightnessOk = brightness >= minBrightness && brightness <= maxBrightness;

            float score = 0f;
            if (faceOk) {
                score += 0.35f;
            }
            if (sizeOk) {
                score += 0.20f;
            }
            if (sharpnessOk) {
                score += 0.30f;
            }
            if (brightnessOk) {
                score += 0.15f;
            }

            String message;
            if (faceOk && sizeOk && sharpnessOk && brightnessOk) {
                message = "人脸质量合格";
            } else if (!faceOk) {
                message = "未检测到人脸";
            } else if (!sizeOk) {
                message = "人脸过小";
            } else if (!sharpnessOk) {
                message = "人脸模糊";
            } else {
                message = "人脸亮度异常";
            }

            FaceQualityInfo info = new FaceQualityInfo(
                    rects.length, faceAreaRatio, blurScore, brightness, contrast,
                    faceOk, sizeOk, sharpnessOk, brightnessOk, score, message, face
            );
            log.info("人脸质量评估: faces={}, ratio={}, score={}, msg={}",
                    rects.length, faceAreaRatio, score, message);
            return info;
        } finally {
            src.release();
            gray.release();
            laplacian.release();
        }
    }
}
