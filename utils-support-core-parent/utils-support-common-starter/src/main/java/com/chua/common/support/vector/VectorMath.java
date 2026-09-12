package com.chua.common.support.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import jdk.incubator.vector.VectorShape;

import java.util.Objects;

/**
* 基于 Java 向量 API（JEP 448，jdk.incubator.向量）的向量数学工具类。
*
* <p>提供点积（dot）、欧氏距离（euclidean）、余弦相似度（cosine）三种常用向量距离计算，
* 当运行时可用 向量species 时自动走 SIMD 向量化路径，否则回退到纯标量循环。
* 本类为纯静态工具类，禁止实例化。</p>
*
* <p>向量化通过 {@link VectorSpecies} 的 lane 并行完成，lane 数在类加载时探测
* （512 → 256 → 128 位依次尝试），不可用时走标量分支，两种路径数值结果一致。</p>
*
* @author CH
* @since 4.0.0.42
* @see jdk.incubator.vector.FloatVector
* @see com.chua.common.support.vector.VectorCompareAlgorithm
 */
public final class VectorMath {

    /**
    * 余弦相似度为零向量时的兜底返回值，表示"无差异"（1f 对应距离 0）
     */
    private static final float COSINE_ZERO_FALLBACK = 1f;

    /**
    * 运行时探测到的最大可用 向量species；为 空 表示 向量 API 不可用，走标量回退
     */
    private static final VectorSpecies<Float> SPECIES = chooseSpecies();

    /**
    * 是否启用向量化路径（SPECIES 非 空 时为 true）
     */
    private static final boolean VECTORIZED = SPECIES != null;

    /**
    * 每轮循环处理的元素个数（向量species 的 lane 数），标量回退时为 1
     */
    private static final int LANES = VECTORIZED ? SPECIES.length() : 1;

    /**
    * 向量化下单个向量占用的字节数，标量回退时为 0
     */
    private static final int BYTE_SIZE = VECTORIZED ? SPECIES.vectorByteSize() : 0;

    /**
    * 私有构造函数，禁止实例化
     */
    private VectorMath() {
    }

    /**
    * 计算两个向量点积。
    *
    * @param a 向量 a，不能为 空，长度决定循环上限
    * @param b 向量 b，不能为 空，长度必须与 a 一致
    * @return 点积之和，标量与向量化路径结果一致
     */
    public static float dot(float[] a, float[] b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        if (VECTORIZED && a.length >= SPECIES.length()) {
            return dotVec(a, b);
        }
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
    * 计算两个向量的欧氏距离（L2 距离）。
    *
    * @param a 向量 a，不能为 空，长度决定循环上限
    * @param b 向量 b，不能为 空，长度必须与 a 一致
    * @return 欧氏距离，标量与向量化路径结果一致
     */
    public static float euclidean(float[] a, float[] b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        if (VECTORIZED && a.length >= SPECIES.length()) {
            return euclideanVec(a, b);
        }
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    /**
    * 计算两个向量的余弦距离（1 - 余弦相似度）。
    *
    * @param a 向量 a，不能为 空，长度决定循环上限
    * @param b 向量 b，不能为 空，长度必须与 a 一致
    * @return 余弦距离；当任一向量为零向量时返回 {@link #COSINE_ZERO_FALLBACK}
     */
    public static float cosine(float[] a, float[] b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        if (VECTORIZED && a.length >= SPECIES.length()) {
            return cosineVec(a, b);
        }
        float dot = 0f;
        float na = 0f;
        float nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        na = (float) Math.sqrt(na);
        nb = (float) Math.sqrt(nb);
        if (na == 0f || nb == 0f) {
            return COSINE_ZERO_FALLBACK;
        }
        return 1f - dot / (na * nb);
    }

    // ==================== 向量化路径 ====================

    /**
    * 点积的向量化实现：按 lane 累加 SIMD 乘积，循环尾（不足一个 lane）走标量补齐。
    *
    * @param a 向量 a，长度至少为 1
    * @param b 向量 b，长度必须与 a 一致
    * @return 点积之和
     */
    private static float dotVec(float[] a, float[] b) {
        int n = SPECIES.loopBound(a.length);
        FloatVector acc = FloatVector.zero(SPECIES);
        for (int i = 0; i < n; i += LANES) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            acc = acc.add(va.mul(vb));
        }
        float sum = acc.reduceLanes(VectorOperators.ADD);
        for (int i = n; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
    * 欧氏距离的向量化实现：SIMD 逐 lane 求平方差累加，循环尾标量补齐。
    *
    * @param a 向量 a，长度至少为 1
    * @param b 向量 b，长度必须与 a 一致
    * @return 欧氏距离
     */
    private static float euclideanVec(float[] a, float[] b) {
        int n = SPECIES.loopBound(a.length);
        FloatVector acc = FloatVector.zero(SPECIES);
        for (int i = 0; i < n; i += LANES) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            FloatVector diff = va.sub(vb);
            acc = acc.add(diff.mul(diff));
        }
        float sum = acc.reduceLanes(VectorOperators.ADD);
        for (int i = n; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    /**
    * 余弦距离的向量化实现：SIMD 并行累加点积与两个模长平方，循环尾标量补齐。
    *
    * @param a 向量 a，长度至少为 1
    * @param b 向量 b，长度必须与 a 一致
    * @return 余弦距离；当任一向量为零向量时返回 {@link #COSINE_ZERO_FALLBACK}
     */
    private static float cosineVec(float[] a, float[] b) {
        int n = SPECIES.loopBound(a.length);
        FloatVector accDot = FloatVector.zero(SPECIES);
        FloatVector accA = FloatVector.zero(SPECIES);
        FloatVector accB = FloatVector.zero(SPECIES);
        for (int i = 0; i < n; i += LANES) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            accDot = accDot.add(va.mul(vb));
            accA = accA.add(va.mul(va));
            accB = accB.add(vb.mul(vb));
        }
        float dot = accDot.reduceLanes(VectorOperators.ADD);
        float na = (float) Math.sqrt(accA.reduceLanes(VectorOperators.ADD));
        float nb = (float) Math.sqrt(accB.reduceLanes(VectorOperators.ADD));
        for (int i = n; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        na = (float) Math.sqrt(na);
        nb = (float) Math.sqrt(nb);
        if (na == 0f || nb == 0f) {
            return COSINE_ZERO_FALLBACK;
        }
        return 1f - dot / (na * nb);
    }

    // ==================== VectorSpecies 探测 ====================

    /**
    * 探测当前 JVM 可用的最大 向量species：按 512 → 256 → 128 位依次尝试。
    *
    * <p>优先更大的向量宽度（lane 数更多、吞吐更高）；全部失败（如不支持 Vector API）
    * 时返回 空，调用方走标量回退。</p>
    *
    * @return 可用的 向量species，全部不可用时为 空
     */
    private static VectorSpecies<Float> chooseSpecies() {
        for (VectorShape shape : new VectorShape[]{
                VectorShape.S_512_BIT, VectorShape.S_256_BIT, VectorShape.S_128_BIT}) {
            try {
                VectorSpecies<Float> s = VectorSpecies.of(float.class, shape);
                if (s.length() > 0) {
                    return s;
                }
            } catch (Throwable ignored) {
                // 该宽度当前平台不支持，继续尝试更窄宽度
            }
        }
        return null;
    }

    /**
    * 是否启用向量化路径。
    *
    * @return Vector API 可用且探测到有效 向量species 时为 true，否则为 false
     */
    public static boolean isVectorized() {
        return VECTORIZED;
    }

    /**
    * 向量化下单个向量占用的字节数。
    *
    * @return 向量化路径下的向量字节大小，标量回退时为 0
     */
    public static int vectorByteSize() {
        return BYTE_SIZE;
    }

    /**
    * 向量化路径每轮循环处理的元素个数（lane 数）。
    *
    * @return VectorSpecies 的 lane 数，标量回退时为 1
     */
    public static int lanes() {
        return LANES;
    }
}
