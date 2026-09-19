package com.chua.deeplearning.support.model;

/**
 * 人脸质量评估结果。
 *
 * @param faceCount     检测到的人脸数
 * @param faceAreaRatio 最大人脸占画面面积比 0~1
 * @param blurScore     模糊度分数
 * @param brightness    平均亮度 0~255
 * @param contrast      对比度
 * @param faceOk        是否检测到有效人脸
 * @param sizeOk        人脸尺寸是否足够
 * @param sharpnessOk   是否足够清晰
 * @param brightnessOk  亮度是否合适
 * @param overallScore  综合质量分 0~1
 * @param message       质量描述
 * @param face          最大人脸框，可为空
 * @author CH
 * @since 4.0.0.42
 */
public record FaceQualityInfo(
        int faceCount,
        float faceAreaRatio,
        double blurScore,
        double brightness,
        double contrast,
        boolean faceOk,
        boolean sizeOk,
        boolean sharpnessOk,
        boolean brightnessOk,
        float overallScore,
        String message,
        PredictRectangle face) {
}
