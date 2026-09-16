package com.chua.deeplearning.support.onnx.audio.zipformer;

/**
* Kaldi 兼容 80 维 fbank 特征提取器 (16 khz)。
*
* <p>参数与 sherpa-onnx / kaldi-native-fbank 默认配置一致:
* 帧长 25 ms、帧移 10 ms、Povey 窗、预加重 0.97、去直流、80 个 Mel 滤波器。
*
* @author chua
* @since 4.0.0.42
 */
public class ZipformerFbank {

    private static final int SAMPLE_RATE = 16000; // 样本rate
    private static final int FRAME_LENGTH = 400; // 帧长度
    private static final int FRAME_SHIFT = 160; // 帧Shift
    private static final int FFT_SIZE = 512; // fft大小
    private static final int NUM_BINS = FFT_SIZE / 2 + 1; // NUM_BINS
    private static final int NUM_MELS = 80; // NUM_MELS
    private static final float PREEMPH = 0.97f; // PREEMPH
    private static final float LOW_FREQ = 20.0f; // LOW_FREQ
    private static final float HIGH_FREQ = SAMPLE_RATE / 2.0f; // HIGH_FREQ
    private static final float FLOOR = 1.1920929e-7f; // 地板

    private final float[] window; // 窗口
    private final float[][] melBanks; // melbanks
    private final float[] cosTable; // costable
    private final float[] sinTable; // sintable

    /**
    * zipformerfbank。
     */
    public ZipformerFbank() {
        this.window = buildPoveyWindow();
        this.melBanks = buildMelBanks();
        this.cosTable = new float[NUM_BINS * FRAME_LENGTH];
        this.sinTable = new float[NUM_BINS * FRAME_LENGTH];
        for (int k = 0; k < NUM_BINS; k++) {
            for (int n = 0; n < FRAME_LENGTH; n++) {
                double angle = -2.0 * Math.PI * k * n / FFT_SIZE;
                cosTable[k * FRAME_LENGTH + n] = (float) Math.cos(angle);
                sinTable[k * FRAME_LENGTH + n] = (float) Math.sin(angle);
            }
        }
    }

    /**
    * 提取 fbank 特征矩阵。
    *
    * @param samples 16 khz 单声道 PCM [-1,1]
    * @return [帧数][80] 二维特征
    * @param hz hz
    * @param mel mel
    */
    public float[][] extract(float[] samples) {
        if (samples == null || samples.length < FRAME_LENGTH) {
            return new float[0][NUM_MELS];
        }
        int numFrames = (samples.length - FRAME_LENGTH) / FRAME_SHIFT + 1;
        float[][] result = new float[numFrames][NUM_MELS];

        double[] frame = new double[FRAME_LENGTH];
        double[] powerSpec = new double[NUM_BINS];

        for (int t = 0; t < numFrames; t++) {
            int offset = t * FRAME_SHIFT;
            for (int i = 0; i < FRAME_LENGTH; i++) {
                frame[i] = samples[offset + i];
            }

            for (int i = FRAME_LENGTH - 1; i >= 1; i--) {
                frame[i] -= PREEMPH * frame[i - 1];
            }

            double mean = 0;
            for (double v : frame) {
                mean += v;
            }
            mean /= FRAME_LENGTH;
            for (int i = 0; i < FRAME_LENGTH; i++) {
                frame[i] = (frame[i] - mean) * window[i];
            }

            computePowerSpectrum(frame, powerSpec);

            for (int m = 0; m < NUM_MELS; m++) {
                double energy = 0;
                float[] bank = melBanks[m];
                for (int k = 0; k < NUM_BINS; k++) {
                    energy += powerSpec[k] * bank[k];
                }
                result[t][m] = (float) Math.log(Math.max(energy, FLOOR));
            }
        }
        return result;
    /**
    * computepowerspectrum。
    * @param frame 帧
    * @param powerSpec powerspec
    * @return 构建povey窗口的结果
     */
    }

    private void computePowerSpectrum(double[] frame, double[] powerSpec) {
        for (int k = 0; k < NUM_BINS; k++) {
            double re = 0;
            double im = 0;
            int base = k * FRAME_LENGTH;
            for (int n = 0; n < FRAME_LENGTH; n++) {
                re += frame[n] * cosTable[base + n];
                im += frame[n] * sinTable[base + n];
            }
            powerSpec[k] = re * re + im * im;
        }
    }

    private float[] buildPoveyWindow() {
        float[] w = new float[FRAME_LENGTH];
        for (int i = 0; i < FRAME_LENGTH; i++) {
            double ratio = (double) i / FRAME_LENGTH;
            w[i] = (float) Math.pow(0.5 - 0.5 * Math.cos(2.0 * Math.PI * ratio), 0.85);
        }
        return w;
    /**
    * 构建melbanks。
    * @return 构建melbanks的结果
    * @param hz hz
    * @param mel mel
     */
    }

    private float[][] buildMelBanks() {
        int numFFTBins = NUM_BINS;
        double melLow = melToHertzInverse(LOW_FREQ);
        double melHigh = melToHertzInverse(HIGH_FREQ);
        double delta = (melHigh - melLow) / (NUM_MELS + 1);

        double[] centerMel = new double[NUM_MELS + 2];
        for (int i = 0; i < NUM_MELS + 2; i++) {
            centerMel[i] = melLow + i * delta;
        }
        int[] binPoints = new int[NUM_MELS + 2];
        for (int i = 0; i < NUM_MELS + 2; i++) {
            double hz = hertzToMelInverse(centerMel[i]);
            binPoints[i] = (int) Math.round((FFT_SIZE + 1) * hz / SAMPLE_RATE);
        }

        float[][] banks = new float[NUM_MELS][numFFTBins];
        for (int m = 0; m < NUM_MELS; m++) {
            int left = binPoints[m];
            int center = binPoints[m + 1];
            int right = binPoints[m + 2];
            for (int k = left; k < Math.min(center, numFFTBins); k++) {
                if (center > left) {
                    banks[m][k] = (float) ((k - left) / (double) (center - left));
                }
            }
            for (int k = center; k < Math.min(right, numFFTBins); k++) {
                if (right > center) {
                    banks[m][k] = (float) ((right - k) / (double) (right - center));
                }
            }
        }
        return banks;
    }

    private static double hertzToMelInverse(double mel) {
        return 700.0 * (Math.exp(mel / 1127.0) - 1.0);
    }

    private static double melToHertzInverse(double hz) {
        return 1127.0 * Math.log(1.0 + hz / 700.0);
    }
}
