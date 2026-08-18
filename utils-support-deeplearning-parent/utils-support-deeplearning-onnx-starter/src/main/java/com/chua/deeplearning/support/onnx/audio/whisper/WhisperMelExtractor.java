package com.chua.deeplearning.support.onnx.audio.whisper;

/**
 * Whisper mel spectrogram 提取器（纯 Java 实现，无外部依赖）。
 * <p>
 * 复现 HuggingFace transformers {@code WhisperFeatureExtractor} 行为：
 * 1. pad/trim 到 30 秒（480000 samples @16kHz）
 * 2. STFT：n_fft=400, hop=160, hann window
 * 3. Power spectrogram → magnitude
 * 4. 80 通道 Slaney-style mel 滤波器组
 * 5. log10 + clamp(max-8) + 缩放到 (val+4)/4
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WhisperMelExtractor {

    /** 16kHz 采样率 */
    /** Sample_rate */
    public static final int SAMPLE_RATE = 16000;
    /** 30 秒音频长度 */
    /** Chunk_length */
    public static final int CHUNK_LENGTH = 30;
    /** 480000 samples */
    /** N_samples */
    public static final int N_SAMPLES = SAMPLE_RATE * CHUNK_LENGTH;
    /** FFT 大小 */
    /** N_fft */
    public static final int N_FFT = 400;
    /** 跳步 */
    /** Hop_length */
    public static final int HOP_LENGTH = 160;
    /** Mel 通道数 */
    /** N_mels */
    public static final int N_MELS = 80;
    /** STFT 帧数：1 + (480000 / 160) = 3001, 但用 center padding 后实际是 3000 */
    /** N_frames */
    public static final int N_FRAMES = 3000;

    /** 窗口向量 */
    /** Window */
    private final float[] window;

    public WhisperMelExtractor() {
        // Hann window (periodic, length n_fft)
        this.window = new float[N_FFT];
        for (int i = 0; i < N_FFT; i++) {
            this.window[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / N_FFT)));
        }
    }

    /**
     * 从音频样本提取 mel 频谱。
     *
     * @param audio float 数组，任意长度（会自动 pad/trim 到 30s）
     * @return (80, 3000) float32 log-mel
     */
    public float[][] extract(float[] audio) {
        // 1. pad/trim to N_SAMPLES
        float[] padded = new float[N_SAMPLES];
        if (audio.length >= N_SAMPLES) {
            System.arraycopy(audio, 0, padded, 0, N_SAMPLES);
        } else {
            System.arraycopy(audio, 0, padded, 0, audio.length);
        }

        // 2. STFT
        int nFreq = N_FFT / 2 + 1; // 201
        float[][] mag = new float[nFreq][N_FRAMES];

        for (int i = 0; i < N_FRAMES; i++) {
            int start = i * HOP_LENGTH;
            float[] frame = new float[N_FFT];
            for (int j = 0; j < N_FFT; j++) {
                int srcIdx = start + j;
                float sample;
                if (srcIdx < 0) {
                    sample = padded[-srcIdx];
                } else if (srcIdx >= N_SAMPLES) {
                    int reflected = 2 * N_SAMPLES - srcIdx - 2;
                    if (reflected < 0) reflected = 0;
                    if (reflected >= N_SAMPLES) reflected = N_SAMPLES - 1;
                    sample = padded[reflected];
                } else {
                    sample = padded[srcIdx];
                }
                frame[j] = sample * window[j];
            }
            float[] real = new float[nFreq];
            float[] imag = new float[nFreq];
            fft(frame, real, imag);
            for (int k = 0; k < nFreq; k++) {
                float r = real[k];
                float im = imag[k];
                mag[k][i] = (float) Math.sqrt(r * r + im * im);
            }
        }

        // 3. mel filterbank
        float[][] mel = applyMelFilterbank(mag);

        // 4. log10
        for (int m = 0; m < N_MELS; m++) {
            for (int f = 0; f < N_FRAMES; f++) {
                mel[m][f] = (float) Math.log10(Math.max(mel[m][f], 1e-10));
            }
        }

        // 5. normalize
        float maxV = Float.NEGATIVE_INFINITY;
        for (int m = 0; m < N_MELS; m++) {
            for (int f = 0; f < N_FRAMES; f++) {
                if (mel[m][f] > maxV) maxV = mel[m][f];
            }
        }
        float floor = maxV - 8.0f;
        for (int m = 0; m < N_MELS; m++) {
            for (int f = 0; f < N_FRAMES; f++) {
                float v = mel[m][f];
                if (v < floor) v = floor;
                mel[m][f] = (v + 4.0f) / 4.0f;
            }
        }
        return mel;
    }

    private float[][] applyMelFilterbank(float[][] mag) {
        float[][] mel = new float[N_MELS][N_FRAMES];
        int nFreq = mag.length;

        float lowFreqMel = hzToMel(0.0f);
        float highFreqMel = hzToMel((float) SAMPLE_RATE / 2);
        float[] melPoints = new float[N_MELS + 2];
        for (int i = 0; i < N_MELS + 2; i++) {
            melPoints[i] = lowFreqMel + (highFreqMel - lowFreqMel) * i / (N_MELS + 1);
        }
        int[] binPoints = new int[N_MELS + 2];
        for (int i = 0; i < N_MELS + 2; i++) {
            float hz = melToHz(melPoints[i]);
            binPoints[i] = (int) Math.floor((N_FFT + 1) * hz / SAMPLE_RATE);
        }
        for (int m = 0; m < N_MELS; m++) {
            int left = binPoints[m];
            int center = binPoints[m + 1];
            int right = binPoints[m + 2];
            for (int k = left; k < right && k < nFreq; k++) {
                float weight;
                if (k <= center) {
                    weight = (float) (k - left) / (center - left);
                } else {
                    weight = (float) (right - k) / (right - center);
                }
                if (weight < 0) weight = 0;
                for (int f = 0; f < N_FRAMES; f++) {
                    mel[m][f] += weight * mag[k][f];
                }
            }
        }
        return mel;
    }

    private static float hzToMel(float hz) {
        return (float) (2595.0 * Math.log10(1.0 + hz / 700.0));
    }

    private static float melToHz(float mel) {
        return (float) (700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0));
    }

    private static void fft(float[] in, float[] realOut, float[] imagOut) {
        // pad to next power of 2 for radix-2 FFT
        int nIn = in.length;
        int n = 1;
        while (n < nIn) n <<= 1;
        float[] padded = new float[n];
        System.arraycopy(in, 0, padded, 0, nIn);
        int[] bits = new int[n];
        for (int i = 0; i < n; i++) bits[i] = i;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; j >= bit; bit >>= 1) j -= bit;
            j += bit;
            int tmp = bits[i];
            bits[i] = bits[j];
            bits[j] = tmp;
        }
        float[] real = new float[n];
        float[] imag = new float[n];
        for (int i = 0; i < n; i++) {
            real[i] = padded[bits[i]];
            imag[i] = 0.0f;
        }
        for (int len = 2; len <= n; len <<= 1) {
            float angle = (float) (-2.0 * Math.PI / len);
            float wlenR = (float) Math.cos(angle);
            float wlenI = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float wR = 1.0f, wI = 0.0f;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    float tR = wR * real[v] - wI * imag[v];
                    float tI = wR * imag[v] + wI * real[v];
                    real[v] = real[u] - tR;
                    imag[v] = imag[u] - tI;
                    real[u] += tR;
                    real[u] += tI;
                    float nR = wR * wlenR - wI * wlenI;
                    float nI = wR * wlenI + wI * wlenR;
                    wR = nR;
                    wI = nI;
                }
            }
        }
        int outLen = realOut.length;
        System.arraycopy(real, 0, realOut, 0, outLen);
        System.arraycopy(imag, 0, imagOut, 0, outLen);
    }
}