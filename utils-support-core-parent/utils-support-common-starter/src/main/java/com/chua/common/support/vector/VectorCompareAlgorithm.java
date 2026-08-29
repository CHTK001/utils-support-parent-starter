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
     * 比较两个向量的距离或相似度。
     * <p>返回值越小表示越相似（距离越小）。</p>
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 距离值，越小越相似
     */
    float compare(float[] a, float[] b);

    /**
     * 创建欧几里得距离算法（向量化）。
     * <p>在支持 AVX-512 / AVX2 / NEON 的硬件上使用 Java Vector API 加速。</p>
     */
    static VectorCompareAlgorithm euclidean() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() { return "EUCLIDEAN"; }
            @Override
            public float compare(float[] a, float[] b) {
                return VectorMath.euclidean(a, b);
            }
        };
    }

    /**
     * 创建余弦相似度算法（向量化）。
     * <p>返回 1 - cos(θ)，值越接近 0 表示越相似。</p>
     */
    static VectorCompareAlgorithm cosine() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() { return "COSINE"; }
            @Override
            public float compare(float[] a, float[] b) {
                return VectorMath.cosine(a, b);
            }
        };
    }

    /**
     * 创建点积距离算法（向量化）。
     * <p>返回负点积，值越小表示越相似。</p>
     */
    static VectorCompareAlgorithm dotProduct() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() { return "DOT"; }
            @Override
            public float compare(float[] a, float[] b) {
                return -VectorMath.dot(a, b);
            }
        };
    }
}
