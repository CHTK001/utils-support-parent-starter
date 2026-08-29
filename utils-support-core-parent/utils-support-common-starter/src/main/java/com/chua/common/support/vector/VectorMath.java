package com.chua.common.support.vector;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 向量距离计算工具类。
 *
 * <p>优先使用 Java Vector API（JEP 448）进行 SIMD 加速；
 * 在 Vector API 不可用时自动回退到标量实现。</p>
 *
 * <p>Vector API 需要在编译和运行时通过 {@code --add-modules jdk.incubator.vector}
 * 启用，以获取最佳性能。不加该参数时仍可正常运行（标量路径）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VectorMath {

    private static final VectorHandler HANDLER = createHandler();

    private VectorMath() {
    }

    // ==================== public API ====================

    public static float dot(float[] a, float[] b) {
        return HANDLER.dot(a, b);
    }

    public static float euclidean(float[] a, float[] b) {
        return HANDLER.euclidean(a, b);
    }

    public static float cosine(float[] a, float[] b) {
        return HANDLER.cosine(a, b);
    }

    /** 是否启用了 Vector API 加速 */
    public static boolean isVectorized() {
        return HANDLER.name().equals("vector");
    }

    /** Vector API 使用的 SIMD 宽度（字节），标量时为 0 */
    public static int vectorByteSize() {
        return HANDLER.vectorByteSize();
    }

    /** 每个向量处理的 float 元素数，标量时为 1 */
    public static int lanes() {
        return HANDLER.lanes();
    }

    // ==================== handler selection ====================

    private static VectorHandler createHandler() {
        try {
            Class.forName("jdk.incubator.vector.VectorSpecies");
            return new VectorApiHandler();
        } catch (Throwable t) {
            return ScalarHandler.INSTANCE;
        }
    }

    // ==================== scalar fallback ====================

    private static final class ScalarHandler implements VectorHandler {
        static final ScalarHandler INSTANCE = new ScalarHandler();

        @Override public String name() { return "scalar"; }
        @Override public int vectorByteSize() { return 0; }
        @Override public int lanes() { return 1; }

        @Override
        public float dot(float[] a, float[] b) {
            float sum = 0f;
            for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
            return sum;
        }

        @Override
        public float euclidean(float[] a, float[] b) {
            float sum = 0f;
            for (int i = 0; i < a.length; i++) {
                float d = a[i] - b[i];
                sum += d * d;
            }
            return (float) Math.sqrt(sum);
        }

        @Override
        public float cosine(float[] a, float[] b) {
            float dot = 0f, na = 0f, nb = 0f;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            na = (float) Math.sqrt(na);
            nb = (float) Math.sqrt(nb);
            if (na == 0f || nb == 0f) return 1f;
            return 1f - dot / (na * nb);
        }
    }

    // ==================== Vector API implementation ====================

    private static final class VectorApiHandler implements VectorHandler {
        private final int lanes;
        private final int byteSize;
        // Reflection-based to avoid compile-time dependency on jdk.incubator.vector
        private final Method speciesOf;
        private final Method speciesLoopBound;
        private final Method speciesZero;
        private final Method speciesFromArray;
        private final Method vectorAdd;
        private final Method vectorMul;
        private final Method vectorSub;
        private final Method vectorReduce;

        VectorApiHandler() {
            try {
                Class<?> speciesClass = Class.forName("jdk.incubator.vector.VectorSpecies");
                Class<?> vectorClass = Class.forName("jdk.incubator.vector.Vector");
                Class<?> opClass = Class.forName("jdk.incubator.vector.VectorOperators");

                speciesOf = speciesClass.getMethod("of", Class.class, Class.forName("jdk.incubator.vector.VectorShape"));
                speciesLoopBound = speciesClass.getMethod("loopBound", int.class);
                speciesZero = speciesClass.getMethod("zero");
                speciesFromArray = vectorClass.getMethod("fromArray", speciesClass, float[].class, int.class);
                vectorAdd = vectorClass.getMethod("add", vectorClass);
                vectorMul = vectorClass.getMethod("mul", vectorClass);
                vectorSub = vectorClass.getMethod("sub", vectorClass);
                vectorReduce = vectorClass.getMethod("reduceLanes", opClass.getDeclaredField("ADD").get(null).getClass());
            } catch (Throwable t) {
                // Fallback to scalar if reflection fails
                throw new RuntimeException("Vector API not available", t);
            }
            this.lanes = 16;  // 512-bit / 4 bytes per float
            this.byteSize = 64;
        }

        @Override public String name() { return "vector"; }
        @Override public int vectorByteSize() { return byteSize; }
        @Override public int lanes() { return lanes; }

        private Object species() {
            try {
                Class<?> shapeClass = Class.forName("jdk.incubator.vector.VectorShape");
                Object shape = shapeClass.getField("S_512_BIT").get(null);
                return speciesOf.invoke(null, float.class, shape);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public float dot(float[] a, float[] b) {
            Object sp = species();
            if (sp == null) return ScalarHandler.INSTANCE.dot(a, b);
            int n = (int) speciesLoopBound.invoke(sp, a.length);
            Object acc = speciesZero.invoke(sp);
            int laneCount = lanes();
            for (int i = 0; i < n; i += laneCount) {
                Object va = speciesFromArray.invoke(null, sp, a, i);
                Object vb = speciesFromArray.invoke(null, sp, b, i);
                acc = vectorAdd.invoke(vectorMul.invoke(va, vb), acc);
            }
            float sum = (float) vectorReduce.invoke(acc, opAdd());
            for (int i = n; i < a.length; i++) sum += a[i] * b[i];
            return sum;
        }

        @Override
        public float euclidean(float[] a, float[] b) {
            Object sp = species();
            if (sp == null) return ScalarHandler.INSTANCE.euclidean(a, b);
            int n = (int) speciesLoopBound.invoke(sp, a.length);
            Object acc = speciesZero.invoke(sp);
            int laneCount = lanes();
            for (int i = 0; i < n; i += laneCount) {
                Object va = speciesFromArray.invoke(null, sp, a, i);
                Object vb = speciesFromArray.invoke(null, sp, b, i);
                Object diff = vectorSub.invoke(va, vb);
                acc = vectorAdd.invoke(vectorMul.invoke(diff, diff), acc);
            }
            float sum = (float) vectorReduce.invoke(acc, opAdd());
            for (int i = n; i < a.length; i++) {
                float d = a[i] - b[i];
                sum += d * d;
            }
            return (float) Math.sqrt(sum);
        }

        @Override
        public float cosine(float[] a, float[] b) {
            Object sp = species();
            if (sp == null) return ScalarHandler.INSTANCE.cosine(a, b);
            int n = (int) speciesLoopBound.invoke(sp, a.length);
            Object accDot = speciesZero.invoke(sp);
            Object accA = speciesZero.invoke(sp);
            Object accB = speciesZero.invoke(sp);
            int laneCount = lanes();
            for (int i = 0; i < n; i += laneCount) {
                Object va = speciesFromArray.invoke(null, sp, a, i);
                Object vb = speciesFromArray.invoke(null, sp, b, i);
                Object prod = vectorMul.invoke(va, vb);
                accDot = vectorAdd.invoke(prod, accDot);
                accA = vectorAdd.invoke(vectorMul.invoke(va, va), accA);
                accB = vectorAdd.invoke(vectorMul.invoke(vb, vb), accB);
            }
            float dot = (float) vectorReduce.invoke(accDot, opAdd());
            float na = (float) Math.sqrt(vectorReduce.invoke(accA, opAdd()));
            float nb = (float) Math.sqrt(vectorReduce.invoke(accB, opAdd()));
            for (int i = n; i < a.length; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            na = (float) Math.sqrt(na);
            nb = (float) Math.sqrt(nb);
            if (na == 0f || nb == 0f) return 1f;
            return 1f - dot / (na * nb);
        }

        private Object opAdd() {
            try {
                return Class.forName("jdk.incubator.vector.VectorOperators").getDeclaredField("ADD").get(null);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ==================== interface ====================

    private interface VectorHandler {
        String name();
        int vectorByteSize();
        int lanes();
        float dot(float[] a, float[] b);
        float euclidean(float[] a, float[] b);
        float cosine(float[] a, float[] b);
    }
}
