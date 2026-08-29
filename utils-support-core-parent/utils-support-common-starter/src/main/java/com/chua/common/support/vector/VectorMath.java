package com.chua.common.support.vector;

import java.lang.reflect.Method;

/**
 * Vector distance computation tool.
 *
 * <p>Uses Java Vector API (JEP 448) for SIMD acceleration when available;
 * falls back to scalar implementation otherwise.</p>
 *
 * <p>The Vector API requires --add-modules jdk.incubator.vector at compile and runtime
 * for best performance. Without it, the scalar path runs automatically.</p>
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

    /** Whether Vector API acceleration is active */
    public static boolean isVectorized() {
        return HANDLER.name().equals("vector");
    }

    /** SIMD width in bytes; 0 for scalar */
    public static int vectorByteSize() {
        return HANDLER.vectorByteSize();
    }

    /** Lanes per vector; 1 for scalar */
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

    // ==================== Vector API via reflection ====================

    private static final class VectorApiHandler implements VectorHandler {
        private static final int LANES = 16;
        private static final int BYTE_SIZE = 64;

        private final Method speciesOf;
        private final Method speciesLoopBound;
        private final Method speciesZero;
        private final Method speciesFromArray;
        private final Method vectorAdd;
        private final Method vectorMul;
        private final Method vectorSub;
        private final Method vectorReduce;
        private final Object opAdd;

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
                vectorReduce = vectorClass.getMethod("reduceLanes", Class.forName("jdk.incubator.vector.VectorOperators$Associative"));
                opAdd = opClass.getDeclaredField("ADD").get(null);
            } catch (Throwable t) {
                throw new RuntimeException("Vector API not available", t);
            }
        }

        @Override public String name() { return "vector"; }
        @Override public int vectorByteSize() { return BYTE_SIZE; }
        @Override public int lanes() { return LANES; }

        private Object species() {
            try {
                Class<?> shapeClass = Class.forName("jdk.incubator.vector.VectorShape");
                Object shape = shapeClass.getField("S_512_BIT").get(null);
                return speciesOf.invoke(null, float.class, shape);
            } catch (Throwable t) {
                return null;
            }
        }

        private float reduce(Object acc) {
            try {
                return ((Number) vectorReduce.invoke(acc, opAdd)).floatValue();
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        @Override
        public float dot(float[] a, float[] b) {
            Object sp = species();
            if (sp == null) return ScalarHandler.INSTANCE.dot(a, b);
            int n = (int) speciesLoopBound.invoke(sp, a.length);
            Object acc = speciesZero.invoke(sp);
            for (int i = 0; i < n; i += LANES) {
                acc = vectorAdd.invoke(vectorMul.invoke(speciesFromArray.invoke(null, sp, a, i),
                                                        speciesFromArray.invoke(null, sp, b, i)), acc);
            }
            float sum = reduce(acc);
            for (int i = n; i < a.length; i++) sum += a[i] * b[i];
            return sum;
        }

        @Override
        public float euclidean(float[] a, float[] b) {
            Object sp = species();
            if (sp == null) return ScalarHandler.INSTANCE.euclidean(a, b);
            int n = (int) speciesLoopBound.invoke(sp, a.length);
            Object acc = speciesZero.invoke(sp);
            for (int i = 0; i < n; i += LANES) {
                Object va = speciesFromArray.invoke(null, sp, a, i);
                Object vb = speciesFromArray.invoke(null, sp, b, i);
                Object diff = vectorSub.invoke(va, vb);
                acc = vectorAdd.invoke(vectorMul.invoke(diff, diff), acc);
            }
            float sum = reduce(acc);
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
            for (int i = 0; i < n; i += LANES) {
                Object va = speciesFromArray.invoke(null, sp, a, i);
                Object vb = speciesFromArray.invoke(null, sp, b, i);
                Object prod = vectorMul.invoke(va, vb);
                accDot = vectorAdd.invoke(prod, accDot);
                accA = vectorAdd.invoke(vectorMul.invoke(va, va), accA);
                accB = vectorAdd.invoke(vectorMul.invoke(vb, vb), accB);
            }
            float dot = reduce(accDot);
            float na = (float) Math.sqrt(reduce(accA));
            float nb = (float) Math.sqrt(reduce(accB));
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
