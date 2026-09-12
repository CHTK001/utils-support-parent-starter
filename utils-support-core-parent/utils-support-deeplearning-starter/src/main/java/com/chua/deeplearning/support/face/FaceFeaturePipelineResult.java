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
}
