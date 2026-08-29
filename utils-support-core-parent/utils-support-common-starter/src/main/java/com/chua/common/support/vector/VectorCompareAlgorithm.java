package com.chua.common.support.vector;


/**
 * 向量比较算法接口，支持自定义特征值距离计算。
 * <p>
 * 第三方厂商可以实现此接口提供私有的向量比较逻辑，
 * 通过 {@link VectorStorageBuilder#algorithm(VectorCompareAlgorithm)} 注入。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VectorCompareAlgorithm {

    /**
     * 获取算法名称。
     *
     * @return 算法名称
     */
    String name();

    /**
     * 比较两个向量的相似度。
     * <p>返回值越大表示越相似（符合人类直觉）。</p>
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 相似度，越大越相似
     */
    float compare(float[] a, float[] b);

    /**
     * 创建欧几里得距离算法。
     * <p>返回相似度 = -欧氏距离，越大越相似；完全相同时为 0。</p>
     */
    static VectorCompareAlgorithm euclidean() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() { return "EUCLIDEAN"; }
            @Override
            public float compare(float[] a, float[] b) {
                return -VectorMath.euclidean(a, b);
            }
        };
    }

    /**
     * 创建余弦相似度算法。
     * <p>返回 cos(θ) ∈ [-1,1]，越大越相似；完全相同时为 1。</p>
     */
    static VectorCompareAlgorithm cosine() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() { return "COSINE"; }
            @Override
            public float compare(float[] a, float[] b) {
                return 1f - VectorMath.cosine(a, b);
            }
        };
    }

    /**
     * 创建点积算法。
     * <p>返回点积，越大越相似。</p>
     */
    static VectorCompareAlgorithm dotProduct() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() { return "DOT"; }
            @Override
            public float compare(float[] a, float[] b) {
                return VectorMath.dot(a, b);
            }
        };
    }
}
