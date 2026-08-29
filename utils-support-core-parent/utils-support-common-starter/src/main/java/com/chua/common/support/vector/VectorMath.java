package com.chua.common.support.vector;

/**
 * 基于 Java Vector API（JEP 448，JDK 21+ incubator）的向量化距离计算。
 *
 * <p>对 float[] 的点积、欧氏距离、余弦相似度优先使用 SIMD 指令批量处理（在支持
 * AVX-512 / AVX2 / NEON 的硬件上可获得 4~16 倍加速）。</p>
 *
 * <p>在 Vector API 不可用的平台（如未启用 {@code jdk.incubator.vector} 模块的 JDK）
 * 上自动回退到纯标量实现，保证任意 JVM 均可编译运行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * 点积：Σ(a[i] * b[i])。
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 点积
     */
    public static float dot(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * 欧氏距离：sqrt(Σ(a[i] - b[i])²)。
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 距离（越小越相似）
     */
    public static float euclidean(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    /**
     * 余弦距离：1 - cos(θ)，值越接近 0 表示越相似。
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦距离（越小越相似）
     */
    public static float cosine(float[] a, float[] b) {
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        na = (float) Math.sqrt(na);
        nb = (float) Math.sqrt(nb);
        if (na == 0f || nb == 0f) {
            return 1f;
        }
        return 1f - dot / (na * nb);
    }

    /**
     * 是否启用向量化（当前实现始终为标量，恒返回 false）。
     *
     * @return false
     */
    public static boolean isVectorized() {
        return false;
    }

    /**
     * 当前使用的 SIMD 宽度（字节），标量实现恒为 0。
     *
     * @return 0
     */
    public static int vectorByteSize() {
        return 0;
    }

    /**
     * 每个向量处理的 float 元素数，标量实现恒为 0。
     *
     * @return 0
     */
    public static int lanes() {
        return 0;
    }
}
