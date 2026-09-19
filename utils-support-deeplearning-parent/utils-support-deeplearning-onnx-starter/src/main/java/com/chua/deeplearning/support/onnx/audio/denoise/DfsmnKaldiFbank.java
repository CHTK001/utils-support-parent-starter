package com.chua.deeplearning.support.onnx.audio.denoise;

import java.util.Random;

/**
 * DFSMN ANS kaldi fbank 特征提取器（纯 Java，复刻 torchaudio.compliance.kaldi.fbank）。
 * <p>
 * 参数与 模型scope DFSMN 语音降噪 pipeline 一致：48khz、帧长 40ms(1920)、帧移 20ms(960)、
 * FFT 补零到 2048、120 维 mel 滤波器组（20 ~ 23600Hz，htk 公式）、dither=1.0、hamming 窗。
 * </p>
 * <p>
 * 处理链路：样本(±32768 级) → dither → DC 去除 → pre-emphasis 0.97 → hamming 窗 → FFT power 谱
 * → mel 滤波器组加权 → 日志 能量，输出 (num_帧, 120)。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class DfsmnKaldiFbank {

    /** 采样率 */
    private static final float SAMPLE_RATE = 48000.0f;

    /** 帧长采样数（40ms） */
    private static final int WINDOW_SIZE = 1920;

    /** 帧移采样数（20ms） */
    private static final int WINDOW_SHIFT = 960;

    /** FFT 长度（1920 向上取整到 2 的幂） */
    private static final int PADDED_WINDOW_SIZE = 2048;

    /** 谱 bin 数（2048/2+1） */
    private static final int N_FFT_BINS = PADDED_WINDOW_SIZE / 2 + 1;

    /** mel 滤波器组数量 */
    private static final int N_MELS = 120;

    /** mel 低频截止（Hz） */
    private static final float LOW_FREQ = 20.0f;

    /**
     * mel 高频截止（Hz，torchaudio 默认 high_freq=0 → Nyquist）
     */
    private static final float HIGH_FREQ = SAMPLE_RATE / 2.0f;

    /** pre-emphasis 系数 */
    private static final float PREEMPH_COEFF = 0.97f;

    /** dither 系数 */
    private static final float DITHER = 1.0f;

    /** 日志 下限（避免 日志(0)） */
    private static final float EPSILON = Float.MIN_NORMAL;

    /** hamming 窗系数 */
    private final float[] window;

    /** mel 滤波器组：[N_MELS][N_FFT_BINS]，matmul 用 */
    private final float[][] melBanks;

    /** dither 系数（0 表示关闭，便于测试确定性对比） */
    private final float dither;

    /** 固定随机种子（dither 可复现） */
    private final Random random = new Random(0);

    /** 创建 dfsmnkaldifbank 实例（dither=1.0） */
    DfsmnKaldiFbank() {
        this(DITHER);
    }

    /**
     * 创建 dfsmnkaldifbank 实例。
     *
     * @param dither dither 系数
     */
    DfsmnKaldiFbank(float dither) {
        this.window = buildHammingWindow();
        this.melBanks = buildMelBanks();
        this.dither = dither;
    }

    /**
     * 提取 fbank 特征。
     *
     * @param samples 48khz 单声道样本（幅值约 ±32768）
     * @return (num_frames, 120) 扁平数组
     */
    float[] extract(float[] samples) {
        int numFrames = numFrames(samples.length);
        float[] features = new float[numFrames * N_MELS];
        for (int frame = 0; frame < numFrames; frame++) {
            int start = frame * WINDOW_SHIFT;
            float[] buf = new float[WINDOW_SIZE];
            System.arraycopy(samples, start, buf, 0, WINDOW_SIZE);

            // dither
            if (dither != 0f) {
                for (int i = 0; i < WINDOW_SIZE; i++) {
                    buf[i] += dither * (float) random.nextGaussian();
                }
            }

            // DC 去除（逐行减均值）
            float mean = 0f;
            for (float v : buf) {
                mean += v;
            }
            mean /= buf.length;
            for (int i = 0; i < buf.length; i++) {
                buf[i] -= mean;
            }

            // pre-emphasis：x[j] -= 0.97 * x[max(0, j-1)]（replicate pad，j=0 用 x[0]）
            for (int j = WINDOW_SIZE - 1; j > 0; j--) {
                buf[j] -= PREEMPH_COEFF * buf[j - 1];
            }
            buf[0] -= PREEMPH_COEFF * buf[0];

            // 加窗
            for (int i = 0; i < WINDOW_SIZE; i++) {
                buf[i] *= window[i];
            }

            // 补零到 2048 并做 FFT，取 power 谱（1025 bins）
            float[] padded = new float[PADDED_WINDOW_SIZE];
            System.arraycopy(buf, 0, padded, 0, WINDOW_SIZE);
            float[][] spectrum = ComplexFft.rfft(padded);
            float[] power = new float[N_FFT_BINS];
            for (int k = 0; k < N_FFT_BINS; k++) {
                power[k] = spectrum[k][0] * spectrum[k][0] + spectrum[k][1] * spectrum[k][1];
            }

            // mel 滤波器组加权（含额外补的一列 0）
            int base = frame * N_MELS;
            for (int m = 0; m < N_MELS; m++) {
                float energy = 0f;
                float[] bank = melBanks[m];
                for (int k = 0; k < N_FFT_BINS; k++) {
                    energy += bank[k] * power[k];
                }
                features[base + m] = (float) Math.log(Math.max(energy, EPSILON));
            }
        }
        return features;
    }

    /**
     * 计算可提取帧数（snip_edges=true）。
     *
     * @param numSamples 样本总数
     * @return 帧数
     */
    static int numFrames(int numSamples) {
        if (numSamples < WINDOW_SIZE) {
            return 0;
        }
        return 1 + (numSamples - WINDOW_SIZE) / WINDOW_SHIFT;
    }

    /**
     * 构造非周期 hamming 窗：0.54 - 0.46·COS(2πi/(N-1))。
     *
     * @return 窗口系数
     */
    private static float[] buildHammingWindow() {
        float[] w = new float[WINDOW_SIZE];
        double a = 2.0 * Math.PI / (WINDOW_SIZE - 1);
        for (int i = 0; i < WINDOW_SIZE; i++) {
            w[i] = (float) (0.54 - 0.46 * Math.cos(a * i));
        }
        return w;
    }

    /**
     * 构造 kaldi mel 滤波器组（120 bins，20 ~ 23600Hz，htk 公式）。
     *
     * @return [120][1025] 权重矩阵
     */
    private static float[][] buildMelBanks() {
        float nyquist = 0.5f * SAMPLE_RATE;
        if (HIGH_FREQ <= 0) {
            throw new IllegalArgumentException("high_freq <= 0");
        }
        float fftBinWidth = SAMPLE_RATE / PADDED_WINDOW_SIZE;
        float melLow = melScale(LOW_FREQ);
        float melHigh = melScale(HIGH_FREQ);
        float melDelta = (melHigh - melLow) / (N_MELS + 1);

        float[][] banks = new float[N_MELS][N_FFT_BINS];
        for (int bin = 0; bin < N_MELS; bin++) {
            float leftMel = melLow + bin * melDelta;
            float centerMel = melLow + (bin + 1) * melDelta;
            float rightMel = melLow + (bin + 2) * melDelta;
            for (int i = 0; i < N_FFT_BINS - 1; i++) {  // mel banks 有 1024 个 fft bin + 尾部 0 补
                float freq = fftBinWidth * i;
                float mel = melScale(freq);
                float upSlope = (mel - leftMel) / (centerMel - leftMel);
                float downSlope = (rightMel - mel) / (rightMel - centerMel);
                float b = Math.max(0f, Math.min(upSlope, downSlope));
                banks[bin][i] = b;
            }
            // 最后一个 bin（nyquist）补 0
            banks[bin][N_FFT_BINS - 1] = 0f;
        }
        return banks;
    }

    /**
     * htk mel 刻度换算：1127·ln(1 + freq/700)。
     *
     * @param freq 频率（Hz）
     * @return mel 值
     */
    private static float melScale(float freq) {
        return (float) (1127.0 * Math.log(1.0 + freq / 700.0));
    }
}
