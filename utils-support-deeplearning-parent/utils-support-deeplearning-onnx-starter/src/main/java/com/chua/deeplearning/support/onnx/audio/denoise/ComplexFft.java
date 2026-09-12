package com.chua.deeplearning.support.onnx.audio.denoise;

/**
* 通用复数 FFT / IFFT（支持任意长度）。
* <p>
* 长度若为 2 的幂则使用经典 radix-2 Cooley-Tukey；否则使用 Bluestein 算法
* （chirp z-转换，正 chirp + 取模防溢出）折叠为 2 的幂长度卷积。
* IFFT 使用共轭技巧实现。供 DFSMN fbank power 谱、STFT / ISTFT 使用。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
class ComplexFft {

    /**
    * 前向 FFT：X[k] = sum_n x[n] * exp(-2πikn/N)。
    *
    * @param real 输入实部（长度 n），返回实部
    * @param imag 输入虚部（长度 n），返回虚部
     */
    static void fft(float[] real, float[] imag) {
        int n = real.length;
        if (isPowerOfTwo(n)) {
            radix2(real, imag, n, false);
        } else {
            bluestein(real, imag);
        }
    }

    /**
    * 逆 FFT：x[n] = (1/N) * sum_k X[k] * exp(+2πikn/N)。
    * <p>共轭技巧：ifft(x) = conj(fft(conj(x))) / N。</p>
    *
    * @param real 输入实部（长度 n），返回实部
    * @param imag 输入虚部（长度 n），返回虚部
     */
    static void ifft(float[] real, float[] imag) {
        int n = real.length;
        for (int i = 0; i < n; i++) {
            imag[i] = -imag[i];
        }
        fft(real, imag);
        for (int i = 0; i < n; i++) {
            imag[i] = -imag[i];
            real[i] /= n;
            imag[i] /= n;
        }
    }

    /**
    * 实数信号 rfft：取前 n/2+1 个复数频点（dc ~ nyquist）。
    *
    * @param signal 实数信号（长度 n）
    * @return [n/2+1][2] 频谱，每行 {real, imag}
     */
    static float[][] rfft(float[] signal) {
        int n = signal.length;
        float[] real = signal.clone();
        float[] imag = new float[n];
        fft(real, imag);
        int bins = n / 2 + 1;
        float[][] out = new float[bins][2];
        for (int i = 0; i < bins; i++) {
            out[i][0] = real[i];
            out[i][1] = imag[i];
        }
        return out;
    }

    /**
    * 频谱 irfft：补全共轭后逆 FFT，返回实数信号（长度 n）。
    *
    * @param spectrum [n/2+1][2] 频谱，每行 {real, imag}
    * @param n        输出信号长度（与原 STFT 的 n_fft 一致）
    * @return 实数信号（长度 n）
     */
    static float[] irfft(float[][] spectrum, int n) {
        float[] real = new float[n];
        float[] imag = new float[n];
        int bins = spectrum.length;
        for (int i = 0; i < bins && i < n; i++) {
            real[i] = spectrum[i][0];
            imag[i] = spectrum[i][1];
        }
        for (int i = bins; i < n; i++) {
            int k = n - i;
            real[i] = k < bins ? spectrum[k][0] : 0f;
            imag[i] = k < bins ? -spectrum[k][1] : 0f;
        }
        ifft(real, imag);
        return real;
    }

    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    /**
    * radix-2 就地 FFT / IFFT。
    *
    * @param real 实部
    * @param imag 虚部
    * @param n    长度（2 的幂）
    * @param inv  inverse 时输出除以 N
     */
    private static void radix2(float[] real, float[] imag, int n, boolean inv) {
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - bits);
            if (j > i) {
                float tr = real[i]; real[i] = real[j]; real[j] = tr;
                float ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            double ang = inv ? 2.0 * Math.PI / len : -2.0 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int k = 0; k < n; k += len) {
                double wR = 1.0, wI = 0.0;
                for (int j = 0; j < half; j++) {
                    int u = k + j;
                    int v = u + half;
                    float er = real[v];
                    float ei = imag[v];
                    float t = (float) (wR * er - wI * ei);
                    float s = (float) (wR * ei + wI * er);
                    real[v] = real[u] - t;
                    imag[v] = imag[u] - s;
                    real[u] += t;
                    imag[u] += s;
                    double nwR = wR * wr - wI * wi;
                    double nwI = wR * wi + wI * wr;
                    wR = nwR;
                    wI = nwI;
                }
            }
        }
        if (inv) {
            for (int i = 0; i < n; i++) {
                real[i] /= n;
                imag[i] /= n;
            }
        }
    }

    /**
    * Bluestein 算法（chirp z-转换）任意长度前向 FFT。
    * <p>
    * 正 chirp：w[i] = exp(+jπi²/n)，其中 i² 以 2n 取模防止三角精度劣化；
    * 卷积核 b 在 b[i] 与 b[m-i] 两处放置 w[i] 以表示偶对称的 w[-(n-1)..n-1]。
    * </p>
    *
    * @param real 实部
    * @param imag 虚部
     */
    private static void bluestein(float[] real, float[] imag) {
        int n = real.length;
        int m = 1;
        while (m < 2 * (n - 1) + 1) {
            m <<= 1;
        }
        // trig：j = (i*i) mod 2n，w = exp(+jπj/n)
        float[] cosT = new float[n];
        float[] sinT = new float[n];
        for (int i = 0; i < n; i++) {
            long j = (long) i * (long) i % (2L * n);
            double ang = Math.PI * j / n;
            cosT[i] = (float) Math.cos(ang);
            sinT[i] = (float) Math.sin(ang);
        }
        // a = x * conj(w)
        float[] areal = new float[m];
        float[] aimag = new float[m];
        for (int i = 0; i < n; i++) {
            areal[i] = real[i] * cosT[i] + imag[i] * sinT[i];
            aimag[i] = -real[i] * sinT[i] + imag[i] * cosT[i];
        }
        // b：b[0]=w[0], b[i]=w[i], b[m-i]=w[i]
        float[] breal = new float[m];
        float[] bimag = new float[m];
        breal[0] = cosT[0];
        bimag[0] = sinT[0];
        for (int i = 1; i < n; i++) {
            breal[i] = cosT[i];
            bimag[i] = sinT[i];
            breal[m - i] = cosT[i];
            bimag[m - i] = sinT[i];
        }
        convolve(areal, aimag, breal, bimag);
        // postprocess：x[k] = c[k] * conj(w[k])
        for (int i = 0; i < n; i++) {
            float cRe = areal[i];
            float cIm = aimag[i];
            real[i] = cRe * cosT[i] + cIm * sinT[i];
            imag[i] = -cRe * sinT[i] + cIm * cosT[i];
        }
    }

    /**
    * 循环卷积（长度均为 m 的 2 的幂）：fft(a) × fft(b) → ifft → /m。
    *
    * @param areal 实部，返回卷积结果
    * @param aimag 虚部
    * @param breal 实部
    * @param bimag 虚部
     */
    private static void convolve(float[] areal, float[] aimag, float[] breal, float[] bimag) {
        int m = areal.length;
        radix2(areal, aimag, m, false);
        radix2(breal, bimag, m, false);
        for (int i = 0; i < m; i++) {
            float r = areal[i], im = aimag[i];
            areal[i] = r * breal[i] - im * bimag[i];
            aimag[i] = r * bimag[i] + im * breal[i];
        }
        radix2(areal, aimag, m, true);
    }
}