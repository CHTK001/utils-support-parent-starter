package com.chua.deeplearning.support.onnx.audio.denoise;

/**
* STFT / ISTFT（复刻 模型scope DFSMN pipeline 使用的 torch.stft + librosa.istft）。
* <p>
* STFT：n_fft=1920、hop=960、win_长度=1920、center=False、hamming(periodic=False)，
* 输出频谱布局 [freq=961][帧][2]（{real, imag}），与 torch.stft(返回_复杂=False) 一致。
* </p>
* <p>
* ISTFT：复刻 librosa.istft（窗口=hamming、center=False、长度），
* 使用 ifftshift(librosa hamming periodic) 窗与重叠相加归一化。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
class DfsmnStftIStft {

    /** FFT 长度 */
    private static final int N_FFT = 1920;

    /** 帧移 */
    private static final int HOP_LENGTH = 960;

    /** 频点数 */
    private static final int N_FREQ = N_FFT / 2 + 1;

    /** torch 分析窗（hamming periodic=false） */
    private final float[] analysisWindow;

    /**
    * librosa 合成窗（hamming periodic，unshifted，与 librosa.istft 一致）
    */
    private final float[] synthWindow;

    /** 创建 dfsmnstftistft 实例 */
    DfsmnStftIStft() {
        this.analysisWindow = buildTorchHammingWindow();
        this.synthWindow = buildLibrosaIfftShiftWindow();
    }

    /**
    * 前向 STFT。
    *
    * @param signal 单声道时域信号
    * @return 频谱 [帧][961][2]，每帧每频点 {real, imag}
    */
    float[][][] stft(float[] signal) {
        int frames = numFrames(signal.length);
        float[][][] out = new float[frames][N_FREQ][2];
        for (int f = 0; f < frames; f++) {
            int start = f * HOP_LENGTH;
            float[] frame = new float[N_FFT];
            for (int i = 0; i < N_FFT; i++) {
                frame[i] = signal[start + i] * analysisWindow[i];
            }
            float[][] spec = ComplexFft.rfft(frame);
            for (int k = 0; k < N_FREQ; k++) {
                out[f][k][0] = spec[k][0];
                out[f][k][1] = spec[k][1];
            }
        }
        return out;
    }

    /**
    * 矩阵乘 mask 后逆 STFT：spectrum[帧][freq][2] 乘 mask[帧][freq]。
    *
    * @param spectrum 频谱 [帧][961][2]
    * @param masks    mask [帧][961]
    * @param length   输出信号长度
    * @return 时域信号
    */
    float[] istft(float[][][] spectrum, float[][] masks, int length) {
        int frames = spectrum.length;
        int expectedLen = N_FFT + HOP_LENGTH * (frames - 1);
        float[] y = new float[expectedLen];
        float[] winSum = new float[expectedLen];

        for (int f = 0; f < frames; f++) {
            // 每帧频点 = spectrum[f] * masks[f]
            float[][] spec = new float[N_FREQ][2];
            float[] mask = masks[f];
            for (int k = 0; k < N_FREQ; k++) {
                spec[k][0] = spectrum[f][k][0] * mask[k];
                spec[k][1] = spectrum[f][k][1] * mask[k];
            }
            float[] frame = ComplexFft.irfft(spec, N_FFT);
            int start = f * HOP_LENGTH;
            for (int i = 0; i < N_FFT; i++) {
                y[start + i] += frame[i] * synthWindow[i];
                winSum[start + i] += synthWindow[i] * synthWindow[i];
            }
        }
        float[] signal = new float[expectedLen];
        for (int i = 0; i < expectedLen; i++) {
            signal[i] = winSum[i] > 1e-15f ? y[i] / winSum[i] : 0f;
        }
 // 长度 裁剪或补零
        if (length != signal.length) {
            float[] out = new float[length];
            System.arraycopy(signal, 0, out, 0, Math.min(length, signal.length));
            return out;
        }
        return signal;
    }

    /**
    * 计算 STFT 帧数（center=false）。
    *
    * @param numSamples 样本数
    * @return 帧数
    */
    static int numFrames(int numSamples) {
        if (numSamples < N_FFT) {
            return 0;
        }
        return 1 + (numSamples - N_FFT) / HOP_LENGTH;
    }

    /**
    * torch.hamming_窗口(N, periodic=false)：0.54 - 0.46·COS(2πn/(N-1))。
    * @return 构建torchhamming窗口的结果
    */
    private static float[] buildTorchHammingWindow() {
        float[] w = new float[N_FFT];
        double a = 2.0 * Math.PI / (N_FFT - 1);
        for (int i = 0; i < N_FFT; i++) {
            w[i] = (float) (0.54 - 0.46 * Math.cos(a * i));
        }
        return w;
    }

    /**
    * librosa.获取_窗口('hamming')（periodic，unshifted）窗。
    * <p>periodic hamming：0.54 - 0.46·cos(2πn/N)。与 librosa.istft 内部
    * 获取_窗口(窗口, win_长度, fftbins=True) 一致，不做 ifftshift。</p>
    * @return 构建librosaifftShift窗口的结果
    */
    private static float[] buildLibrosaIfftShiftWindow() {
        float[] w = new float[N_FFT];
        double a = 2.0 * Math.PI / N_FFT;
        for (int i = 0; i < N_FFT; i++) {
            w[i] = (float) (0.54 - 0.46 * Math.cos(a * i));
        }
        return w;
    }
}
