package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * 人脸对齐管线结果。
 *
 * <p>由 {@link FacePipeline#align(byte[])} 返回，对场景图跑
 * {@code detect → crop → liveness → align} 节点，返回最大人脸的对齐图与元数据。</p>
 *
 * @param box         人脸框
 * @param faceImage   裁剪后的人脸图（未对齐）
 * @param alignedFace 5 点仿射旋转后的对齐人脸图（无关键点模型时退化为 face镜像）
 * @param live        活体通过（未配置时为 true）
 * @param liveScore   活体分数（未配置时为 1.0）
 * @param elapsedMs   端到端耗时（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record FaceAlignResult(
        PredictRectangle box,
        byte[] faceImage,
        byte[] alignedFace,
        boolean live,
        float liveScore,
        long elapsedMs) {

    /**
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。无人脸 / 未配置关键点模型时
     * 组件可能为 {@code null}，保留其 {@code null} 语义。</p>
     *
     * @param faceImage   裁剪后的人脸图（未对齐）
     * @param alignedFace 对齐人脸图
     */
    public FaceAlignResult {
        faceImage = faceImage == null ? null : faceImage.clone();
        alignedFace = alignedFace == null ? null : alignedFace.clone();
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
}
