package com.chua.deeplearning.support.onnx.audio.paraformer;

/**
 * 基 2 迭代 FFT（实数输入，复数输出），长度必须为 2 的幂。
 * <p>
 * 采用位反转重排 + 蝶形迭代的经典 radix-2 Cooley-Tukey 算法，
 * 供 kaldi fbank power 谱计算使用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class FftRadix2 {

    /** FFT 长度 */
    /** N */
    private final int n;

    /** 位反转索引表 */
    /** Rev */
    private final int[] rev;

    /** 预计算旋转因子实部 */
    /** Wr */
    private final float[] wr;

    /** 预计算旋转因子虚部 */
    /** Wi */
    private final float[] wi;

    /**
     * 构造指定长度的 FFT。
     *
     * @param n 长度，必须为 2 的幂
     */
    FftRadix2(int n) {
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("n must be a power of two: " + n);
        }
        this.n = n;
        this.rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        this.wr = new float[n / 2];
        this.wi = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double angle = -2.0 * Math.PI * i / n;
            wr[i] = (float) Math.cos(angle);
            wi[i] = (float) Math.sin(angle);
        }
    }

    /**
     * 就地执行 FFT。
     *
     * @param real 输入实部（长度 n），返回实部
     * @param imag 输出虚部（长度 n）
     */
    void transform(float[] real, float[] imag) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float tr = real[i];
                real[i] = real[j];
                real[j] = tr;
                float ti = imag[i];
                imag[i] = imag[j];
                imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; j++) {
                    int idx = j * n / len;
                    float wrj = wr[idx];
                    float wij = wi[idx];
                    int u = i + j;
                    int v = u + half;
                    float er = real[v];
                    float ei = imag[v];
                    float t = wrj * er - wij * ei;
                    float s = wrj * ei + wij * er;
                    real[v] = real[u] - t;
                    imag[v] = imag[u] - s;
                    real[u] += t;
                    imag[u] += s;
                }
            }
        }
    }
}
