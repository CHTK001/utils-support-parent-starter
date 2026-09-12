package com.chua.deeplearning.support.model;

/**
* 图像质量评估结果。
*
* @param blurScore     模糊度分数（Laplacian 方差，越高越清晰）
* @param brightness    平均亮度 0~255
* @param contrast      对比度（灰度标准差）
* @param sharpnessOk   是否足够清晰
* @param brightnessOk  亮度是否合适
* @param overallScore  综合质量分 0~1
* @param message       质量描述
* @author CH
* @since 4.0.0.42
 */
public record ImageQualityInfo(
        double blurScore,
        double brightness,
        double contrast,
        boolean sharpnessOk,
        boolean brightnessOk,
        float overallScore,
        String message) {
}
