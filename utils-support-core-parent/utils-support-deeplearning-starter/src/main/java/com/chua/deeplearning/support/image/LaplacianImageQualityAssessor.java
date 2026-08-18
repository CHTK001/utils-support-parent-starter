package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import com.chua.deeplearning.support.utils.ImageUtils;

/**
 * 基于 Laplacian 方差的图像质量评估器。
 *
 * <p>纯 OpenCV 实现，无需加载任何模型：灰度图 Laplacian 二阶差分方差评估清晰度
 * （越大越清晰，阈值经验值 100），灰度均值/标准差评估亮度与对比度。
 * 由 {@link ImageUtils#blurScore(byte[])} 统一承载像素计算。</p>
 *
 * <pre>{@code
 * ImageQualityInfo info = new LaplacianImageQualityAssessor()
 *         .blurThreshold(100)
 *         .assess(imageBytes);
 * boolean ok = info.sharpnessOk() && info.brightnessOk();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("laplacian")
public class LaplacianImageQualityAssessor implements ImageQualityAssessor {

    /**
     * 模糊阈值，低于该值视为模糊。
     */
    private double blurThreshold = 100.0;

    /**
     * 最低亮度。
     */
    private double minBrightness = 40.0;

    /**
     * 最高亮度。
     */
    private double maxBrightness = 220.0;

    /**
     * 构造评估器（默认阈值：模糊 100、亮度 40~220）。
     */
    public LaplacianImageQualityAssessor() {
    }

    /**
     * 构造评估器。
     *
     * @param blurThreshold 模糊阈值
     * @param minBrightness 最低亮度
     * @param maxBrightness 最高亮度
     */
    public LaplacianImageQualityAssessor(double blurThreshold, double minBrightness, double maxBrightness) {
        this.blurThreshold = blurThreshold;
        this.minBrightness = minBrightness;
        this.maxBrightness = maxBrightness;
    }

    @Override
    public ImageQualityAssessor blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    public ImageQualityAssessor modelPath(String path) {
        return this;
    }

    @Override
    public ImageQualityAssessor device(String device) {
        return this;
    }

    @Override
    public ImageQualityInfo assess(byte[] imageData) {
        double blurScore = ImageUtils.blurScore(imageData);
        double brightness = ImageUtils.meanGray(imageData);
        double contrast = ImageUtils.stdDevGray(imageData);

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

        return new ImageQualityInfo(
                blurScore, brightness, contrast,
                sharpnessOk, brightnessOk, overall, message);
    }
}