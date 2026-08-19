package com.chua.common.support.vector;


/**
 * 向量比较算法接口，支持自定义特征值距离计算。
 * <p>
 * 第三方厂商可以实现此接口提供私有的向量比较逻辑，
 * 通过 {@link VectorStorageBuilder#algorithm(VectorCompareAlgorithm)} 注入。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
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
     * 创建欧几里得距离算法。
     *
     * @return 欧几里得距离算法实例
     */
    static VectorCompareAlgorithm euclidean() {
        return new VectorCompareAlgorithm() {
            @Override
            /** Name */
            public String name() {
                return "EUCLIDEAN";
            }

            @Override
            /** 比较 */
            public float compare(float[] a, float[] b) {
                float sum = 0;
                for (int i = 0; i < a.length; i++) {
                    float d = a[i] - b[i];
                    sum += d * d;
                }
                return (float) Math.sqrt(sum);
            }
        };
    }

    /**
     * 创建余弦相似度算法。
     * <p>返回 1 - cos(θ)，值越接近 0 表示越相似。</p>
     *
     * @return 余弦相似度算法实例
     */
    static VectorCompareAlgorithm cosine() {
        return new VectorCompareAlgorithm() {
            @Override
            /** Name */
            public String name() {
                return "COSINE";
            }

            @Override
            /** 比较 */
            public float compare(float[] a, float[] b) {
                float dot = 0;
                float na = 0;
                float nb = 0;
                for (int i = 0; i < a.length; i++) {
                    dot += a[i] * b[i];
                    na += a[i] * a[i];
                    nb += b[i] * b[i];
                }
                return 1 - dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
            }
        };
    }

    /**
     * 创建点积距离算法。
     * <p>返回负点积，值越小表示越相似。</p>
     *
     * @return 点积距离算法实例
     */
    static VectorCompareAlgorithm dotProduct() {
        return new VectorCompareAlgorithm() {
            @Override
            /** Name */
            public String name() {
                return "DOT";
            }

            @Override
            /** 比较 */
            public float compare(float[] a, float[] b) {
                float dot = 0;
                for (int i = 0; i < a.length; i++) {
                    dot += a[i] * b[i];
                }
                return -dot;
            }
        };
    }
}
