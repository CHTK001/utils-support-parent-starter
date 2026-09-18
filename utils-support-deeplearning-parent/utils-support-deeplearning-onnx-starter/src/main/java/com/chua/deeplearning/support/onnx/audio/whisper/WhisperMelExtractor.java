package com.chua.deeplearning.support.onnx.audio.whisper;

/**
* Whisper mel spectrogram 提取器（纯 Java 实现，无外部依赖）。
* <p>
* 复现 huggingface transformers {@code WhisperFeatureExtractor} 行为：
* 1. pad/修剪 到 30 秒（480000 样本 @16khz）
* 2. STFT：n_fft=400, hop=160, hann 窗口
* 3. Power spectrogram → magnitude
* 4. 80 通道 Slaney-style mel 滤波器组
* 5. 日志10 + clamp(最大-8) + 缩放到 (val+4)/4
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class WhisperMelExtractor {

    /** 16khz 采样率 */
    /** 样本_rate */
    public static final int SAMPLE_RATE = 16000;
    /** 30 秒音频长度 */
    /** Chunk_长度 */
    public static final int CHUNK_LENGTH = 30;
    /** 480000 样本 */
    /** N_样本 */
    public static final int N_SAMPLES = SAMPLE_RATE * CHUNK_LENGTH;
    /** FFT 大小 */
    /** N_fft */
    public static final int N_FFT = 400;
    /** 跳步 */
    /** Hop_长度 */
    public static final int HOP_LENGTH = 160;
    /** Mel 通道数 */
    /** N_mels */
    public static final int N_MELS = 80;
    /**
    * STFT 帧数：1 + (480000 / 160) = 3001, 但用 center padding 后实际是 3000
    */
    /** N_帧 */
    public static final int N_FRAMES = 3000;

    /** 窗口向量 */
    /** 窗口 */
    private final float[] window;

    /** 创建 whispermelextractor 实例 */
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
    * @param audio float 数组，任意长度（会自动 pad/修剪 到 30s）
    * @return (80, 3000) float32 日志-mel
    */
    public float[][] extract(float[] audio) {
        // 1. 截断到 30s；中心零填充（与参考实现一致：pad N_FFT/2 零，不预补满 30s）
        int inputLen = Math.min(audio.length, N_SAMPLES);
        float[] padded = new float[inputLen + N_FFT];
        System.arraycopy(audio, 0, padded, N_FFT / 2, inputLen);

        // 2. STFT（精确 400 点 DFT，与 numpy.rfft 频率采样一致；仅有效帧）
        int nFreq = N_FFT / 2 + 1; // 201
        int nValidFrames = Math.max(1, (inputLen + N_FFT / 2) / HOP_LENGTH);
        float[][] mag = new float[nFreq][nValidFrames];
        double[] cosTab = new double[N_FFT];
        double[] sinTab = new double[N_FFT];
        for (int j = 0; j < N_FFT; j++) {
            cosTab[j] = Math.cos(-2.0 * Math.PI * j / N_FFT);
            sinTab[j] = Math.sin(-2.0 * Math.PI * j / N_FFT);
        }

        for (int i = 0; i < nValidFrames; i++) {
            int start = i * HOP_LENGTH;
            for (int k = 0; k < nFreq; k++) {
                double re = 0;
                double im = 0;
                int idx = k;
                for (int j = 0; j < N_FFT; j++) {
                    float sample = (start + j >= 0 && start + j < padded.length
                            ? padded[start + j] : 0F) * window[j];
                    re += sample * cosTab[idx];
                    im += sample * sinTab[idx];
                    idx += k;
                    if (idx >= N_FFT) {
                        idx -= N_FFT;
                    }
                }
                mag[k][i] = (float) (re * re + im * im);
            }
        }

        // 3. mel filterbank
        float[][] mel = applyMelFilterbank(mag);

 // 4. 日志10（复用步骤 2 声明的有效帧数）
        for (int m = 0; m < N_MELS; m++) {
            for (int f = 0; f < nValidFrames; f++) {
                mel[m][f] = (float) Math.log10(Math.max(mel[m][f], 1e-10));
            }
        }

        // 5. normalize
        float maxV = Float.NEGATIVE_INFINITY;
        for (int m = 0; m < N_MELS; m++) {
            for (int f = 0; f < nValidFrames; f++) {
                if (mel[m][f] > maxV) {
                    maxV = mel[m][f];
                }
            }
        }
        float floor = maxV - 8.0f;
        for (int m = 0; m < N_MELS; m++) {
            for (int f = 0; f < nValidFrames; f++) {
                float v = mel[m][f];
                if (v < floor) {
                    v = floor;
                }
                mel[m][f] = (v + 4.0f) / 4.0f;
            }
        }
        // 步骤 5b：尾部静音帧补零（与参考实现一致：归一化后补零，而非参与统计）
        float[][] out = new float[N_MELS][N_FRAMES];
        for (int m = 0; m < N_MELS; m++) {
            System.arraycopy(mel[m], 0, out[m], 0, nValidFrames);
        }
        return out;
    }

    /**
    * 应用melfilterbank
    *
    * @param mag mag
    * @return applyMelFilterbank的结果
    */
    private float[][] applyMelFilterbank(float[][] mag) {
        float[][] mel = new float[N_MELS][mag[0].length];
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
            // 与参考实现一致的三角形滤波器：左坡 [left,center)、右坡 [center,right)
            // 零宽坡自然跳过，避免除零且保留中心峰值
            for (int k = left; k < center && k < nFreq; k++) {
                if (center > left) {
                    float weight = (float) (k - left) / (center - left);
                    for (int f = 0; f < mag[0].length; f++) {
                        mel[m][f] += weight * mag[k][f];
                    }
                }
            }
            for (int k = center; k < right && k < nFreq; k++) {
                if (right > center) {
                    float weight = (float) (right - k) / (right - center);
                    for (int f = 0; f < mag[0].length; f++) {
                        mel[m][f] += weight * mag[k][f];
                    }
                }
            }
        }
        return mel;
    }

    /**
    * hz转为mel
    *
    * @param hz hz
    * @return hz转为mel的结果
    */
    private static float hzToMel(float hz) {
        return (float) (2595.0 * Math.log10(1.0 + hz / 700.0));
    }

    /**
    * mel转为hz
    *
    * @param mel mel
    * @return mel转为hz的结果
    */
    private static float melToHz(float mel) {
        return (float) (700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0));
    }
}
