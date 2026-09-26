package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * 人脸特征提取管线结果。
 *
 * <p>由 {@link FacePipeline#extractFeatureWithMeta(byte[])} 返回，对场景图跑
 * {@code detect → crop → liveness → align → feature} 完整管线，返回最大人脸的
 * 检测框、裁剪图、对齐图、活体结果、特征向量与耗时。</p>
 *
 * @param box         人脸框
 * @param faceImage   裁剪后的人脸图（未对齐）
 * @param alignedFace 5 点仿射旋转后的对齐人脸图（无关键点模型时退化为 face镜像）
 * @param live        活体通过（未配置时为 true）
 * @param liveScore   活体分数
 * @param feature     512 维特征向量（活体失败可能为 空）
 * @param dim         特征维度
 * @param elapsedMs   端到端耗时（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record FaceFeaturePipelineResult(
        PredictRectangle box,
        byte[] faceImage,
        byte[] alignedFace,
        boolean live,
        float liveScore,
        float[] feature,
        int dim,
        long elapsedMs) {

    /**
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。无人脸 / 未配置关键点模型 /
     * 未配置特征模型时组件可能为 {@code null}，保留其 {@code null} 语义。</p>
     *
     * @param faceImage   裁剪后的人脸图（未对齐）
     * @param alignedFace 对齐人脸图
     * @param feature     特征向量
     */
    public FaceFeaturePipelineResult {
        faceImage = faceImage == null ? null : faceImage.clone();
        alignedFace = alignedFace == null ? null : alignedFace.clone();
        feature = feature == null ? null : feature.clone();
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

    /**
     * 访问器覆写：返回对齐人脸图的副本。
     *
     * @return 对齐人脸图副本；无则返回 {@code null}
     */
    @Override
    public byte[] alignedFace() {
        return alignedFace == null ? null : alignedFace.clone();
    }

    /**
     * 访问器覆写：返回内部特征向量的副本。
     *
     * @return 特征向量副本；活体失败未取特征时返回 {@code null}
     */
    @Override
    public float[] feature() {
        return feature == null ? null : feature.clone();
    }
}
