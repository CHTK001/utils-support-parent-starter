package com.chua.common.support.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

import java.util.Objects;

/**
 * 基于 Java 向量 API（JEP 448，jdk.incubator.vector）的向量数学工具类。
 *
 * <p>提供点积（dot）、欧氏距离（euclidean）、余弦距离（cosine）三种常用向量距离计算，
 * 当运行时 Vector API 可用时自动走 SIMD 向量化路径，否则回退到纯标量循环。
 * 本类为纯静态工具类，禁止实例化。</p>
 *
 * <p>向量化通过 VectorSpecies 的 lane 并行完成，可用宽度在类加载时探测
 * （512 → 256 → 128 位依次尝试）。探测会真正执行一次向量运算并与标量结果比对，
 * 任何不可用情形（Vector API 未加入模块、被 classpath 上的同名垫片类遮蔽、平台不支持该宽度）
 * 都会使本类永久降级为标量路径，两条路径数值结果一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see com.chua.common.support.vector.VectorCompareAlgorithm
 */
public final class VectorMath {

    /**
     * 零向量的余弦距离兜底值：零向量无方向，余弦相似度无定义，按"正交"处理（相似度 0）
     */
    private static final float COSINE_ZERO_FALLBACK = 1f;

    /**
     * 候选向量位宽，从宽到窄依次探测；全部不可用则走标量路径
     */
    private static final int[] CANDIDATE_BITS = {512, 256, 128};

    /**
     * 运行时 SIMD 能力，探测失败时为标量回退
     */
    private static final Capability CAPABILITY = detect();

    /**
     * 私有构造函数，禁止实例化
     */
    private VectorMath() {
    }

    /**
     * 计算两个向量点积。
     *
     * @param a 向量 a，不能为 null
     * @param b 向量 b，不能为 null 且长度必须与 a 一致
     * @return 点积之和，标量与向量化路径结果一致
     * @throws NullPointerException     a 或 b 为 null
     * @throws IllegalArgumentException a 与 b 长度不一致
     */
    public static float dot(float[] a, float[] b) {
        check(a, b);
        return CAPABILITY.usable(a.length) ? Simd.dot(a, b) : dotScalar(a, b);
    }

    /**
     * 计算两个向量的欧氏距离（L2 距离）。
     *
     * @param a 向量 a，不能为 null
     * @param b 向量 b，不能为 null 且长度必须与 a 一致
     * @return 欧氏距离，标量与向量化路径结果一致
     * @throws NullPointerException     a 或 b 为 null
     * @throws IllegalArgumentException a 与 b 长度不一致
     */
    public static float euclidean(float[] a, float[] b) {
        check(a, b);
        return CAPABILITY.usable(a.length) ? Simd.euclidean(a, b) : euclideanScalar(a, b);
    }

    /**
     * 计算两个向量的余弦距离（1 - 余弦相似度），取值范围 [0, 2]。
     *
     * @param a 向量 a，不能为 null
     * @param b 向量 b，不能为 null 且长度必须与 a 一致
     * @return 余弦距离；当任一向量为零向量时返回 {@link #COSINE_ZERO_FALLBACK}
     * @throws NullPointerException     a 或 b 为 null
     * @throws IllegalArgumentException a 与 b 长度不一致
     */
    public static float cosine(float[] a, float[] b) {
        check(a, b);
        return CAPABILITY.usable(a.length) ? Simd.cosine(a, b) : cosineScalar(a, b);
    }

    /**
     * 是否启用向量化路径。
     *
     * @return Vector API 可用且探测到有效向量宽度时为 true，否则为 false
     */
    public static boolean isVectorized() {
        return CAPABILITY.vectorized();
    }

    /**
     * 向量化下单个向量占用的字节数。
     *
     * @return 向量化路径下的向量字节大小，标量回退时为 0
     */
    public static int vectorByteSize() {
        return CAPABILITY.byteSize();
    }

    /**
     * 向量化路径每轮循环处理的元素个数（lane 数）。
     *
     * @return 向量宽度的 lane 数，标量回退时为 1
     */
    public static int lanes() {
        return CAPABILITY.lanes();
    }

    // ==================== 入参校验 ====================

    /**
     * 校验两个向量非空且长度一致。
     *
     * <p>长度不一致时若只按 a 的长度循环，会得到静默错误的结果（b 的尾部被忽略），
     * 故在此显式拒绝。</p>
     *
     * @param a 向量 a
     * @param b 向量 b
     */
    private static void check(float[] a, float[] b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        if (a.length != b.length) {
            throw new IllegalArgumentException("a.length (" + a.length + ") must equal b.length (" + b.length + ")");
        }
    }

    // ==================== 标量路径 ====================

    /**
     * 点积的标量实现，同时作为向量化探测的比对基准。
     *
     * @param a 向量 a
     * @param b 向量 b，长度必须与 a 一致
     * @return 点积之和
     */
    private static float dotScalar(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * 欧氏距离的标量实现，同时作为向量化探测的比对基准。
     *
     * @param a 向量 a
     * @param b 向量 b，长度必须与 a 一致
     * @return 欧氏距离
     */
    private static float euclideanScalar(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    /**
     * 余弦距离的标量实现，同时作为向量化探测的比对基准。
     *
     * @param a 向量 a
     * @param b 向量 b，长度必须与 a 一致
     * @return 余弦距离；当任一向量为零向量时返回 {@link #COSINE_ZERO_FALLBACK}
     */
    private static float cosineScalar(float[] a, float[] b) {
        float dot = 0f;
        float sa = 0f;
        float sb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            sa += a[i] * a[i];
            sb += b[i] * b[i];
        }
        return cosineFrom(dot, sa, sb);
    }

    /**
     * 由点积与两个模长平方合成余弦距离，标量与向量化路径共用，保证两条路径口径一致。
     *
     * @param dot 点积
     * @param sa  向量 a 的模长平方
     * @param sb  向量 b 的模长平方
     * @return 余弦距离；模长平方为 0 时返回 {@link #COSINE_ZERO_FALLBACK}
     */
    private static float cosineFrom(float dot, float sa, float sb) {
        float na = (float) Math.sqrt(sa);
        float nb = (float) Math.sqrt(sb);
        if (na == 0f || nb == 0f) {
            return COSINE_ZERO_FALLBACK;
        }
        return 1f - dot / (na * nb);
    }

    // ==================== SIMD 能力探测 ====================

    /**
     * 按 512 → 256 → 128 位依次探测，取第一个"能真正算对"的宽度。
     *
     * @return 探测到的能力；全部不可用时为标量回退
     */
    private static Capability detect() {
        for (int bits : CANDIDATE_BITS) {
            try {
                if (Simd.enable(bits) > 0 && simdAgreesWithScalar(Simd.lanes())) {
                    return new Capability(true, Simd.lanes(), Simd.byteSize());
                }
            } catch (Throwable ignored) {
                // Vector API 不可用（缺模块、被垫片遮蔽、平台不支持该宽度），继续尝试更窄宽度
            }
        }
        return new Capability(false, 1, 0);
    }

    /**
     * 用长度非 lane 整数倍的样本实测一次向量运算，验证 SIMD 结果与标量一致。
     *
     * <p>探测必须真正执行向量指令：Vector API 的失败多在首次运算时才抛出，
     * 仅调用 VectorSpecies.of 不足以判定可用。样本取 lane + 1 个元素，
     * 同时覆盖向量化主体与循环尾。</p>
     *
     * @param lanes 待验证宽度的 lane 数
     * @return 三项运算均与标量结果吻合时为 true
     */
    private static boolean simdAgreesWithScalar(int lanes) {
        int n = lanes + 1;
        float[] a = new float[n];
        float[] b = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = i + 1;
            b[i] = n - i;
        }
        return closeTo(Simd.dot(a, b), dotScalar(a, b))
                && closeTo(Simd.euclidean(a, b), euclideanScalar(a, b))
                && closeTo(Simd.cosine(a, b), cosineScalar(a, b));
    }

    /**
     * 判断浮点结果是否吻合，允许 lane 并行求和带来的最后一位舍入差异。
     *
     * @param actual   向量化结果
     * @param expected 标量结果
     * @return 相等或在容差内时为 true
     */
    private static boolean closeTo(float actual, float expected) {
        if (Float.isNaN(actual)) {
            return false;
        }
        if (actual == expected) {
            return true;
        }
        return Math.abs(actual - expected) <= 1e-4f * Math.max(1f, Math.abs(expected));
    }

    /**
     * SIMD 探测结论。
     *
     * @param vectorized 是否启用向量化路径
     * @param lanes      每轮并行处理的元素个数，标量回退时为 1
     * @param byteSize   单个向量占用的字节数，标量回退时为 0
     */
    private record Capability(boolean vectorized, int lanes, int byteSize) {

        /**
         * 判断给定长度的运算是否走向量化路径。
         *
         * @param length 向量长度
         * @return SIMD 可用且长度不小于一个 lane 时为 true
         */
        boolean usable(int length) {
            return vectorized && length >= lanes;
        }
    }

    /**
     * SIMD 向量化实现，集中持有全部 jdk.incubator.vector 引用。
     *
     * <p>独立成内部类的原因：Vector API 是 incubator 模块，运行时未加入模块时本类链接即抛
     * NoClassDefFoundError，被 classpath 上的同名垫片类遮蔽时则在首次运算抛 IllegalAccessError。
     * 两者都由 {@link #detect()} 捕获，不影响 VectorMath 自身加载与标量路径。</p>
     */
    private static final class Simd {

        /**
         * 探测选中的向量类型，enable 成功前为 null
         */
        private static VectorSpecies<Float> species;

        /**
         * 当前宽度的 lane 数
         */
        private static int lanes;

        /**
         * 当前宽度单个向量占用的字节数
         */
        private static int byteSize;

        /**
         * 启用指定比特的向量宽度。
         *
         * @param bits 向量位宽
         * @return 该宽度的 lane 数，宽度不受支持时为 0
         */
        static int enable(int bits) {
            VectorSpecies<Float> chosen = VectorSpecies.of(float.class, shapeOf(bits));
            int length = chosen.length();
            if (length <= 0) {
                return 0;
            }
            species = chosen;
            lanes = length;
            byteSize = chosen.vectorByteSize();
            return length;
        }

        /**
         * 点积的向量化实现：按 lane 累加 SIMD 乘积，循环尾（不足一个 lane）走标量补齐。
         *
         * @param a 向量 a，长度至少为一个 lane
         * @param b 向量 b，长度必须与 a 一致
         * @return 点积之和
         */
        static float dot(float[] a, float[] b) {
            int n = species.loopBound(a.length);
            FloatVector acc = FloatVector.zero(species);
            for (int i = 0; i < n; i += lanes) {
                FloatVector va = FloatVector.fromArray(species, a, i);
                FloatVector vb = FloatVector.fromArray(species, b, i);
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
         * @param a 向量 a，长度至少为一个 lane
         * @param b 向量 b，长度必须与 a 一致
         * @return 欧氏距离
         */
        static float euclidean(float[] a, float[] b) {
            int n = species.loopBound(a.length);
            FloatVector acc = FloatVector.zero(species);
            for (int i = 0; i < n; i += lanes) {
                FloatVector va = FloatVector.fromArray(species, a, i);
                FloatVector vb = FloatVector.fromArray(species, b, i);
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
         * 余弦距离的向量化实现：SIMD 并行累加点积与两个模长平方，循环尾标量补齐后再开方。
         *
         * @param a 向量 a，长度至少为一个 lane
         * @param b 向量 b，长度必须与 a 一致
         * @return 余弦距离；当任一向量为零向量时返回 {@link #COSINE_ZERO_FALLBACK}
         */
        static float cosine(float[] a, float[] b) {
            int n = species.loopBound(a.length);
            FloatVector accDot = FloatVector.zero(species);
            FloatVector accA = FloatVector.zero(species);
            FloatVector accB = FloatVector.zero(species);
            for (int i = 0; i < n; i += lanes) {
                FloatVector va = FloatVector.fromArray(species, a, i);
                FloatVector vb = FloatVector.fromArray(species, b, i);
                accDot = accDot.add(va.mul(vb));
                accA = accA.add(va.mul(va));
                accB = accB.add(vb.mul(vb));
            }
            float dot = accDot.reduceLanes(VectorOperators.ADD);
            float sa = accA.reduceLanes(VectorOperators.ADD);
            float sb = accB.reduceLanes(VectorOperators.ADD);
            for (int i = n; i < a.length; i++) {
                dot += a[i] * b[i];
                sa += a[i] * a[i];
                sb += b[i] * b[i];
            }
            return cosineFrom(dot, sa, sb);
        }

        /**
         * 当前宽度的 lane 数。
         *
         * @return lane 数
         */
        static int lanes() {
            return lanes;
        }

        /**
         * 当前宽度单个向量占用的字节数。
         *
         * @return 字节数
         */
        static int byteSize() {
            return byteSize;
        }

        /**
         * 按比特数取向量形状常量。
         *
         * @param bits 向量位宽
         * @return 对应的向量形状
         */
        private static VectorShape shapeOf(int bits) {
            if (bits >= 512) {
                return VectorShape.S_512_BIT;
            }
            if (bits >= 256) {
                return VectorShape.S_256_BIT;
            }
            return VectorShape.S_128_BIT;
        }
    }
}
