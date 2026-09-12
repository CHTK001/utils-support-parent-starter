package com.chua.common.support.vector;

/**
* 向量比较算法接口，支持自定义特征值距离计算。
*
* <p>第三方厂商可以实现此接口提供私有的向量比较逻辑，
* 通过 {@link VectorStorageBuilder#algorithm(VectorCompareAlgorithm)} 注入。
* 内置三种基于 {@link VectorMath} 的算法：欧氏距离、余弦距离、点积。</p>
*
* @author CH
* @since 4.0.0.42
* @see VectorMath
* @see VectorStorageBuilder
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
    *
    * <p>返回值越大表示越相似（符合人类直觉）。</p>
    *
    * @param a 向量 a，不能为 空
    * @param b 向量 b，不能为 空，长度必须与 a 一致
    * @return 相似度，越大越相似
     */
    float compare(float[] a, float[] b);

    /**
    * 创建欧氏距离算法。
    *
    * <p>返回相似度 = 负欧氏距离，越大越相似；完全相同时为 0。</p>
    *
    * @return 欧氏距离算法实例
     */
    static VectorCompareAlgorithm euclidean() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() {
                return "EUCLIDEAN";
            }

            @Override
            public float compare(float[] a, float[] b) {
                return -VectorMath.euclidean(a, b);
            }
        };
    }

    /**
    * 创建余弦距离算法。
    *
    * <p>返回余弦相似度（1 - 余弦距离）∈ [-1, 1]，越大越相似；
    * 完全相同时为 1，方向相反时为 -1。</p>
    *
    * @return 余弦距离算法实例
     */
    static VectorCompareAlgorithm cosine() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() {
                return "COSINE";
            }

            @Override
            public float compare(float[] a, float[] b) {
                return 1f - VectorMath.cosine(a, b);
            }
        };
    }

    /**
    * 创建点积算法。
    *
    * <p>返回点积值，越大越相似（假设向量已归一化时等价于余弦相似度）。</p>
    *
    * @return 点积算法实例
     */
    static VectorCompareAlgorithm dotProduct() {
        return new VectorCompareAlgorithm() {
            @Override
            public String name() {
                return "DOT";
            }

            @Override
            public float compare(float[] a, float[] b) {
                return VectorMath.dot(a, b);
            }
        };
    }
}
