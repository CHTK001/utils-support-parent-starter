package com.chua.deeplearning.support.face;

/**
* 人脸比对完整管线结果。
*
* <p>由 {@link FacePipeline#compareWithMeta(byte[], byte[])} 返回，两侧都跑
* {@code detect→crop→liveness→align→feature} 后做余弦比对。</p>
*
* @param score    余弦相似度（0~1，越高越相似）
* @param matched  是否达 匹配_阈值（仅 score 非空）
* @param a        图片 A 特征管线结果
* @param b        图片 B 特征管线结果
* @param elapsedMs 端到端耗时（毫秒）
* @author CH
* @since 4.0.0.42
 */
public record FaceCompareResult(
        double score,
        boolean matched,
        FaceFeaturePipelineResult a,
        FaceFeaturePipelineResult b,
        long elapsedMs) {
}
