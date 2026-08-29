package com.chua.common.support.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector distance computation using Java Vector API (JEP 448).
 * Falls back to scalar when Vector API is unavailable.
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VectorMath {

    private static final VectorSpecies<Float> SPECIES = chooseSpecies();
    private static final boolean VECTORIZED = SPECIES != null;
    private static final int LANES = VECTORIZED ? SPECIES.length() : 1;
    private static final int BYTE_SIZE = VECTORIZED ? SPECIES.vectorByteSize() : 0;

    private VectorMath() {
    }

    public static float dot(float[] a, float[] b) {
        if (VECTORIZED && a.length >= SPECIES.length()) return dotVec(a, b);
        float sum = 0f;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    public static float euclidean(float[] a, float[] b) {
        if (VECTORIZED && a.length >= SPECIES.length()) return euclideanVec(a, b);
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    public static float cosine(float[] a, float[] b) {
        if (VECTORIZED && a.length >= SPECIES.length()) return cosineVec(a, b);
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

    // ==================== vectorized ====================

    private static float dotVec(float[] a, float[] b) {
        int n = SPECIES.loopBound(a.length);
        FloatVector acc = FloatVector.zero(SPECIES);
        for (int i = 0; i < n; i += LANES) {
            acc = acc.add(FloatVector.fromArray(SPECIES, a, i).mul(FloatVector.fromArray(SPECIES, b, i)));
        }
        float sum = acc.reduceLanes(VectorOperators.ADD);
        for (int i = n; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

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
        if (na == 0f || nb == 0f) return 1f;
        return 1f - dot / (na * nb);
    }

    // ==================== species selection ====================

    private static VectorSpecies<Float> chooseSpecies() {
        for (VectorShape shape : new VectorShape[]{
                VectorShape.S_512_BIT, VectorShape.S_256_BIT, VectorShape.S_128_BIT}) {
            try {
                VectorSpecies<Float> s = VectorSpecies.of(float.class, shape);
                if (s.length() > 0) return s;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static boolean isVectorized() { return VECTORIZED; }
    public static int vectorByteSize() { return BYTE_SIZE; }
    public static int lanes() { return LANES; }
}
