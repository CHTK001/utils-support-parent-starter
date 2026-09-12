package com.chua.deeplearning.support.onnx.audio.paraformer;

import java.util.ArrayList;
import java.util.List;

/**
   * Paraformer 特征提取器（纯 Java 实现，复刻 kaldi-NAT-fbank + sherpa-onnx 流程）。
 * <p>
 * 处理链路：WAV 样本(16k, int16 范围) → kaldi fbank(80 维) → LFR 拼接(7 帧→560 维) → CMVN 归一化。
 * </p>
 * <ul>
 *   <li>kaldi fbank：帧长 25ms(400)、帧移 10ms(160)、hamming 窗、pre-emphasis 0.97、
   * DC 去除、FFT 512(补零)、power 谱、80 维 mel 滤波器组(20~8000Hz)、日志 能量</li>
 *   <li>LFR(Low Frame Rate)：窗口 7、步长 6，输出帧数 = 1 + (n-1)/6，每帧 7×80=560 维，
 *       越界处按边界帧复制</li>
 *   <li>CMVN：对每帧 80 维执行 (x + neg_mean) × inv_stddev，参数来自 ONNX metadata</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ParaformerFbankExtractor {

    /** 采样率 */
    /** 样本_rate */
    private static final float SAMPLE_RATE = 16000.0f;

    /** 帧长（毫秒） */
    /** 帧_长度_ms */
    private static final float FRAME_LENGTH_MS = 25.0f;

    /** 帧移（毫秒） */
    /** 帧_Shift_ms */
    private static final float FRAME_SHIFT_MS = 10.0f;

    /** 帧移采样数 */
    /** 窗口_Shift */
    private static final int WINDOW_SHIFT = (int) (SAMPLE_RATE * 0.001f * FRAME_SHIFT_MS);

    /** 帧长采样数 */
    /** 窗口_大小 */
    private static final int WINDOW_SIZE = (int) (SAMPLE_RATE * 0.001f * FRAME_LENGTH_MS);

    /** FFT 长度（400 向上取整到 2 的幂） */
    /** Padded_窗口_大小 */
    private static final int PADDED_WINDOW_SIZE = 512;

    /** 谱 bin 数（512/2+1） */
    /** N_fft_bins */
    private static final int N_FFT_BINS = PADDED_WINDOW_SIZE / 2 + 1;

    /** mel 滤波器组数量 */
    /** N_mels */
    private static final int N_MELS = 80;

    /** mel 低频截止 */
    /** Low_freq */
    private static final float LOW_FREQ = 20.0f;

    /** mel 高频截止（nyquist + 0 = 8000Hz） */
    /** High_freq */
    private static final float HIGH_FREQ = 8000.0f;

    /** pre-emphasis 系数 */
    /** Preemph_coeff */
    private static final float PREEMPH_COEFF = 0.97f;

    /** 是否去除直流分量 */
    /** 移除_dc_偏移量 */
    private static final boolean REMOVE_DC_OFFSET = true;

    /** 帧间能量下限 */
    /** Energy_地板 */
    private static final float ENERGY_FLOOR = 1.0f;

    /** LFR 窗口大小 */
    /** Lfr_窗口_大小 */
    private static final int LFR_WINDOW_SIZE = 7;

    /** LFR 窗口步长 */
    /** Lfr_窗口_Shift */
    private static final int LFR_WINDOW_SHIFT = 6;

    /** hamming 窗口系数 */
    /** 窗口 */
    private final float[] window;

    /** mel 滤波器组权重：每行一个 bin 的 {偏移量, 权重[]} */
    /** Mel_banks */
    private final float[][] melWeights;

    /** mel 滤波器组每行起始 fft bin */
    /** Mel_偏移量 */
    private final int[] melOffsets;

    /** CMVN 负均值（80 维） */
    /** Neg_mean */
    private float[] negMean;

    /** CMVN 逆标准差（80 维） */
    /** Inv_stddev */
    private float[] invStddev;

    /** 是否已配置 CMVN 参数 */
    /** 是否包含_cmvn */
    private boolean hasCmvn;

    /** 创建 paraformerfbankextractor 实例 */
    public ParaformerFbankExtractor() {
        this.window = buildWindow();
        MelBank bank = buildMelBank();
        this.melWeights = bank.weights;
        this.melOffsets = bank.offsets;
    }

    /**
     * 设置 CMVN 归一化参数（来自 ONNX metadata）。
     *
     * @param negMean   负均值数组，长度为 80
     * @param invStddev 逆标准差数组，长度为 80
     */
    public void setCmvn(float[] negMean, float[] invStddev) {
        this.negMean = negMean;
        this.invStddev = invStddev;
        this.hasCmvn = negMean != null && invStddev != null
                && negMean.length == N_MELS && invStddev.length == N_MELS;
    }

    /**
     * 提取完整特征：fbank → LFR → CMVN。
     *
     * @param samples 16khz 单声道样本（int16 范围，约 ±32768）
     * @return (frames, 560) 扁平 float 数组，每帧 560 维
     */
    public float[] extract(float[] samples) {
        float[][] fbank = computeFbank(samples);
        float[] lfr = applyLfr(fbank);
        if (hasCmvn) {
            applyCmvn(lfr);
        }
        return lfr;
    }

    /**
     * 计算 LFR 后的帧数。
     *
     * @param inputFrames fbank 帧数
     * @return LFR 输出帧数
     */
    public static int lfrFrames(int inputFrames) {
        if (inputFrames == 0) {
            return 0;
        }
        return 1 + (inputFrames - 1) / LFR_WINDOW_SHIFT;
    }

    /**
      * 计算 kaldi fbank（80 维 日志-mel 能量）。
     *
     * @param samples 16khz 单声道样本
     * @return (frames, 80) 特征矩阵
     */
    private float[][] computeFbank(float[] samples) {
        int numFrames = numFrames(samples.length);
        float[][] features = new float[numFrames][N_MELS];

        for (int frame = 0; frame < numFrames; frame++) {
            // 提取帧并做窗前处理（无 snip 边缘，sherpa paraformer 用 snip_edges=true）
            float[] buf = new float[WINDOW_SIZE];
            int start = frame * WINDOW_SHIFT;
            System.arraycopy(samples, start, buf, 0, WINDOW_SIZE);

 // DC 偏移量 去除
            if (REMOVE_DC_OFFSET) {
                removeDcOffset(buf);
            }

            // pre-emphasis（从后往前）
            for (int i = WINDOW_SIZE - 1; i > 0; i--) {
                buf[i] -= PREEMPH_COEFF * buf[i - 1];
            }
            buf[0] -= PREEMPH_COEFF * buf[0];

            // 加窗
            for (int i = 0; i < WINDOW_SIZE; i++) {
                buf[i] *= window[i];
            }

            // 补零到 512 并做 FFT，取 power 谱
            float[] padded = new float[PADDED_WINDOW_SIZE];
            System.arraycopy(buf, 0, padded, 0, WINDOW_SIZE);
            float[] power = powerSpectrum(padded);

            // mel 滤波器组加权
            float[] mel = new float[N_MELS];
            for (int m = 0; m < N_MELS; m++) {
                float energy = 0.0f;
                for (int k = 0; k < melWeights[m].length; k++) {
                    energy += melWeights[m][k] * power[melOffsets[m] + k];
                }
 // 日志 能量，下限 epsilon
                float t = Math.max(energy, Float.MIN_NORMAL);
                mel[m] = (float) Math.log(t);
            }
            features[frame] = mel;
        }
        return features;
    }

    /**
     * LFR 拼接：每输出帧取窗口 7 帧拼接成 560 维，越界处按边界帧复制。
     *
     * @param fbank (帧, 80) 特征矩阵
     * @return 扁平 (lfr帧 * 560) 数组
     */
    private float[] applyLfr(float[][] fbank) {
        int inputFrames = fbank.length;
        int outputFrames = lfrFrames(inputFrames);
        int outputDim = N_MELS * LFR_WINDOW_SIZE;
        float[] out = new float[outputFrames * outputDim];

        int leftContext = (LFR_WINDOW_SIZE - 1) / 2;
        for (int i = 0; i < outputFrames; i++) {
            int centerFrame = i * LFR_WINDOW_SHIFT;
            for (int j = 0; j < LFR_WINDOW_SIZE; j++) {
                int srcFrame = centerFrame + (j - leftContext);
                if (srcFrame < 0) {
                    srcFrame = 0;
                }
                if (srcFrame >= inputFrames) {
                    srcFrame = inputFrames - 1;
                }
                System.arraycopy(fbank[srcFrame], 0, out,
                        i * outputDim + j * N_MELS, N_MELS);
            }
        }
        return out;
    }

    /**
     * CMVN 归一化：对每个 80 维块执行 (x + neg_mean) × inv_stddev。
     *
     * @param features 扁平特征数组（LFR 后）
     */
    private void applyCmvn(float[] features) {
        int dim = negMean.length;
        for (int i = 0; i < features.length; i++) {
            int d = i % dim;
            features[i] = (features[i] + negMean[d]) * invStddev[d];
        }
    }

    /**
     * 计算可提取帧数（snip_edges=true，kaldi 逻辑）。
     *
     * @param numSamples 样本总数
     * @return 帧数
     */
    private static int numFrames(int numSamples) {
        if (numSamples < WINDOW_SIZE) {
            return 0;
        }
        return 1 + (numSamples - WINDOW_SIZE) / WINDOW_SHIFT;
    }

    /**
     * 去除直流分量（减去均值）。
     *
     * @param buf 帧数据，原地修改
     */
    private static void removeDcOffset(float[] buf) {
        float sum = 0.0f;
        for (float v : buf) {
            sum += v;
        }
        float mean = sum / buf.length;
        for (int i = 0; i < buf.length; i++) {
            buf[i] -= mean;
        }
    }

    /**
     * 计算 power 谱（|FFT|^2，取 257 个 bin）。
     *
     * @param padded 补零后的 512 样本
     * @return 257 维 power 谱
     */
    private float[] powerSpectrum(float[] padded) {
        FftRadix2 fft = new FftRadix2(PADDED_WINDOW_SIZE);
        float[] real = padded;
        float[] imag = new float[PADDED_WINDOW_SIZE];
        fft.transform(real, imag);

        float[] power = new float[N_FFT_BINS];
        for (int k = 0; k < N_FFT_BINS; k++) {
            power[k] = real[k] * real[k] + imag[k] * imag[k];
        }
        return power;
    }

    /**
      * 构造 hamming 窗：0.54 - 0.46·COS(2πi/(N-1))。
     *
     * @return 400 维窗口系数
     */
    private static float[] buildWindow() {
        float[] w = new float[WINDOW_SIZE];
        double a = 2.0 * Math.PI / (WINDOW_SIZE - 1);
        for (int i = 0; i < WINDOW_SIZE; i++) {
            w[i] = (float) (0.54 - 0.46 * Math.cos(a * i));
        }
        return w;
    }

    /**
     * 构造 kaldi mel 滤波器组（80 bins，20~8000Hz，htk 公式）。
     *
     * @return 滤波器组权重与偏移
     */
    private static MelBank buildMelBank() {
        float nyquist = 0.5f * SAMPLE_RATE;
        float fftBinWidth = SAMPLE_RATE / PADDED_WINDOW_SIZE;
        float melLow = melScale(LOW_FREQ);
        float melHigh = melScale(HIGH_FREQ);
        float melDelta = (melHigh - melLow) / (N_MELS + 1);

        float[][] weights = new float[N_MELS][];
        int[] offsets = new int[N_MELS];

        for (int bin = 0; bin < N_MELS; bin++) {
            float leftMel = melLow + bin * melDelta;
            float centerMel = melLow + (bin + 1) * melDelta;
            float rightMel = melLow + (bin + 2) * melDelta;

            List<Float> vals = new ArrayList<>(32);
            int firstIndex = -1;
            for (int i = 0; i < PADDED_WINDOW_SIZE / 2; i++) {
                float freq = fftBinWidth * i;
                float mel = melScale(freq);
                if (mel > leftMel && mel < rightMel) {
                    float weight;
                    if (mel <= centerMel) {
                        weight = (mel - leftMel) / (centerMel - leftMel);
                    } else {
                        weight = (rightMel - mel) / (rightMel - centerMel);
                    }
                    vals.add(weight);
                    if (firstIndex == -1) {
                        firstIndex = i;
                    }
                }
            }
            float[] arr = new float[vals.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = vals.get(i);
            }
            weights[bin] = arr;
            offsets[bin] = firstIndex;
        }
        return new MelBank(weights, offsets);
    }

    /**
     * htk mel 刻度换算。
     *
     * @param freq 频率（Hz）
     * @return mel 值
     */
    private static float melScale(float freq) {
        return (float) (1127.0 * Math.log(1.0 + freq / 700.0));
    }

    /**
     * mel 滤波器组容器。
     *
     * @param weights 每行权重数组
     * @param offsets 每行起始 fft bin
     * @return MelBank的结果
     */
    private record MelBank(float[][] weights, int[] offsets) {
    }
}
