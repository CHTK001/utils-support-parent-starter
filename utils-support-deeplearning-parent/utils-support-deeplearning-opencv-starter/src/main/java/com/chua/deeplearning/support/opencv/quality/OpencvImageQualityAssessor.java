package com.chua.deeplearning.support.opencv.quality;

import com.chua.deeplearning.support.model.ImageQualityInfo;
import com.chua.deeplearning.support.opencv.OpencvModelTranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.imgproc.Imgproc;

/**
 * 纯 OpenCV 图像质量评估翻译器。
 * <p>基于 Laplacian 方差评估清晰度，基于灰度均值/标准差评估亮度与对比度。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OpencvImageQualityAssessor extends OpencvModelTranslator {

    /**
     * 模糊阈值，低于该值视为模糊。
     */
    private final double blurThreshold;

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
     */
    public OpencvImageQualityAssessor() {
        this(100.0, 40.0, 220.0);
    }

    /**
     * 构造评估器。
     *
     * @param blurThreshold  模糊阈值
     * @param minBrightness  最低亮度
     * @param maxBrightness  最高亮度
     */
    public OpencvImageQualityAssessor(double blurThreshold, double minBrightness, double maxBrightness) {
        super("opencv-image-quality");
        this.blurThreshold = blurThreshold;
        this.minBrightness = minBrightness;
        this.maxBrightness = maxBrightness;
    }

    @Override
    /** DoTranslate */
    protected Object doTranslate(Object input) {
        if (!(input instanceof byte[] imageBytes)) {
            throw new IllegalArgumentException("仅支持 byte[] 输入");
        }

        Mat src = bytesToMat(imageBytes);
        Mat gray = new Mat();
        Mat laplacian = new Mat();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

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

            boolean sharpnessOk = blurScore >= blurThreshold;
            boolean brightnessOk = brightness >= minBrightness && brightness <= maxBrightness;

            float sharpnessScore = (float) Math.min(1.0, blurScore / (blurThreshold * 2.0));
            float brightnessScore = brightnessOk ? 1.0f : 0.4f;
            float overall = (sharpnessScore * 0.7f) + (brightnessScore * 0.3f);

            String message;
            if (sharpnessOk && brightnessOk) {
                message = "图像质量合格";
            } else if (!sharpnessOk && !brightnessOk) {
                message = "图像模糊且亮度异常";
            } else if (!sharpnessOk) {
                message = "图像模糊";
            } else {
                message = "图像亮度异常";
            }

            ImageQualityInfo info = new ImageQualityInfo(
                    blurScore, brightness, contrast,
                    sharpnessOk, brightnessOk, overall, message
            );
            log.info("图像质量评估: blur={}, brightness={}, score={}", blurScore, brightness, overall);
            return info;
        } finally {
            src.release();
            gray.release();
            laplacian.release();
        }
    }
}
