package com.chua.deeplearning.support.feature;

/**
 * 特征相似度计算接口。
 * <p>比较两个特征向量的相似度。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FeatureSimilarity {

    /**
     * 计算两个特征向量的相似度。
     *
     * @param feature1 特征向量 1
     * @param feature2 特征向量 2
     * @return 相似度分数 0~1
     */
    float compare(float[] feature1, float[] feature2);
}
