package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
* 人脸检测链路结果（框 + 裁剪图 + 可选活体）。
*
* @param box       人脸框
* @param faceImage 裁剪后人脸图
* @param live      是否通过活体（未配置活体时为 true）
* @param liveScore 活体分数（未配置时为 1.0）
* @author CH
* @since 4.0.0.42
 */
public record FaceDetectionHit(
        PredictRectangle box,
        byte[] faceImage,
        boolean live,
        float liveScore) {
}
