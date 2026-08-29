package com.chua.common.support.vector;

import java.lang.reflect.Method;

/**
 * Vector distance computation tool.
 * Uses Java Vector API (JEP 448) for SIMD acceleration when available;
 * falls back to scalar implementation otherwise.
 * Vector API requires --add-modules jdk.incubator.vector at runtime for best performance.
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VectorMath {

    private static final VectorHandler HANDLER;

    static {
        VectorHandler h = null;
        try {
            Class.forName("jdk.incubator.vector.VectorSpecies");
            h = new VectorApiHandler();
        } catch (Throwable ignored) {
        }
        HANDLER = h != null ? h : new ScalarHandler();
    }

    private VectorMath() {
    }

    public static float dot(float[] a, float[] b) {
        return HANDLER.dot(a, b);
    }

    public static float euclidean(float[] a, float[] b) {
        return HANDLER.euclidean(a, b);
    }

    public static float cosine(float[] a, float[] b) {
        return HANDLER.cosine(a, b);
    }

    public static boolean isVectorized() {
        return HANDLER.name().equals("vector");
    }

    public static int vectorByteSize() {
        return HANDLER.vectorByteSize();
    }

    public static int lanes() {
        return HANDLER.lanes();
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
        private final Object shape512;

        VectorApiHandler() {
            try {
                Class<?> sc = Class.forName("jdk.incubator.vector.VectorSpecies");
                Class<?> vc = Class.forName("jdk.incubator.vector.Vector");
                Class<?> oc = Class.forName("jdk.incubator.vector.VectorOperators");
                Class<?> sshape = Class.forName("jdk.incubator.vector.VectorShape");
                speciesOf = sc.getMethod("of", Class.class, sshape);
                speciesLoopBound = sc.getMethod("loopBound", int.class);
                speciesZero = sc.getMethod("zero");
                speciesFromArray = vc.getMethod("fromArray", sc, float[].class, int.class);
                vectorAdd = vc.getMethod("add", vc);
                vectorMul = vc.getMethod("mul", vc);
                vectorSub = vc.getMethod("sub", vc);
                vectorReduce = vc.getMethod("reduceLanes", oc.getDeclaredField("ADD").getType());
                opAdd = oc.getDeclaredField("ADD").get(null);
                shape512 = sshape.getField("S_512_BIT").get(null);
            } catch (Throwable t) {
                throw new RuntimeException("Vector API not available", t);
            }
        }

        @Override public String name() { return "vector"; }
        @Override public int vectorByteSize() { return BYTE_SIZE; }
        @Override public int lanes() { return LANES; }

        private Object species() {
            try {
                return speciesOf.invoke(null, float.class, shape512);
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

        private Object invokeSafe(Method m, Object... args) {
            try { return m.invoke(null == args[0] ? null : args[0], java.util.Arrays.copyOfRange(args, 1, args.length)); }
            catch (Throwable t) { throw new RuntimeException(t); }
        }

        @Override
        public float dot(float[] a, float[] b) {
            Object sp = species();
            if (sp == null) return ScalarHandler.INSTANCE.dot(a, b);
            int n = (int) invokeSafe(speciesLoopBound, sp, a.length);
            Object acc = invokeSafe(speciesZero, sp);
            for (int i = 0; i < n; i += LANES) {
                Object va = invokeSafe(speciesFromArray, sp, a, i);
                Object vb = invokeSafe(speciesFromArray, sp, b, i);
                acc = invokeSafe(vectorAdd, invokeSafe(vectorMul, va, vb), acc);
            }
            float sum = reduce(acc);
            for (int i = n; i < a.length; i++) sum += a[i] * b[i];
            return sum;
        }

        @Override
        public float euclidean(float[] a, float[] b) {
            Object sp = species();
            if (sp == null) return ScalarHandler.INSTANCE.euclidean(a, b);
            int n = (int) invokeSafe(speciesLoopBound, sp, a.length);
            Object acc = invokeSafe(speciesZero, sp);
            for (int i = 0; i < n; i += LANES) {
                Object va = invokeSafe(speciesFromArray, sp, a, i);
                Object vb = invokeSafe(speciesFromArray, sp, b, i);
                Object diff = invokeSafe(vectorSub, va, vb);
                acc = invokeSafe(vectorAdd, invokeSafe(vectorMul, diff, diff), acc);
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
            int n = (int) invokeSafe(speciesLoopBound, sp, a.length);
            Object accDot = invokeSafe(speciesZero, sp);
            Object accA = invokeSafe(speciesZero, sp);
            Object accB = invokeSafe(speciesZero, sp);
            for (int i = 0; i < n; i += LANES) {
                Object va = invokeSafe(speciesFromArray, sp, a, i);
                Object vb = invokeSafe(speciesFromArray, sp, b, i);
                Object prod = invokeSafe(vectorMul, va, vb);
                accDot = invokeSafe(vectorAdd, prod, accDot);
                accA = invokeSafe(vectorAdd, invokeSafe(vectorMul, va, va), accA);
                accB = invokeSafe(vectorAdd, invokeSafe(vectorMul, vb, vb), accB);
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

    private interface VectorHandler {
        String name();
        int vectorByteSize();
        int lanes();
        float dot(float[] a, float[] b);
        float euclidean(float[] a, float[] b);
        float cosine(float[] a, float[] b);
    }
}
