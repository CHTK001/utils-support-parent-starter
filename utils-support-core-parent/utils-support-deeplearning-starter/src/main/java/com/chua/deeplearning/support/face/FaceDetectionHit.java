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

    /**
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。裁剪失败时组件可能为
     * {@code null}，保留其 {@code null} 语义。</p>
     *
     * @param faceImage 裁剪后人脸图
     */
    public FaceDetectionHit {
        faceImage = faceImage == null ? null : faceImage.clone();
    }

    /**
     * 访问器覆写：返回裁剪人脸图的副本。
     *
     * @return 人脸图副本；无则返回 {@code null}
     */
    @Override
    public byte[] faceImage() {
        return faceImage == null ? null : faceImage.clone();
    }
}
