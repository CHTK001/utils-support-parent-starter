package com.chua.deeplearning.support.onnx.audio;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
* Wespeaker Rnet34 说话人嵌入提取翻译器（纯 ONNX Runtime 实现）。
*
* <h2>模型说明</h2>
* <p>wespeaker-resnet34 是专用于说话人验证的 ResNet34+LM 架构：
* <ul>
*   <li><b>输入</b>：16kHz 单声道 PCM float 音频，先经 Kaldi-style 80 维 fbank 特征提取（预加重 0.97 / 去直流 /
* Povey 窗^0.85 / 512 点功率谱 / HTK-mel 0~8khz / 日志）。</li>
*   <li><b>ONNX 期望</b>：rank=3 张量 [1, num_frames, 80]。</li>
*   <li><b>输出</b>：512 维 L2 归一化嵌入向量（x-vector）。</li>
*   <li><b>用途</b>：说话人验证、声纹识别、说话人分离。</li>
* </ul>
* </p>
*
* @author CH
* @since 4.0.0.44
 */
@Slf4j
public class WespeakerEmbeddingTranslator implements ITranslator<byte[], float[]> {

    private OrtEnvironment ortEnv; // ortenv
    private OrtSession session; // 会话
    private String modelPath; // 模型路径
    private int targetSampleRate = 16000; // Target样本rate
    private volatile boolean prepared = false; // prepared

    // fbank 提取参数（与 sherpa-onnx / kaldi-native-fbank 一致）
    private static final int SAMPLE_RATE = 16000;
    private static final int FFT_N = 512; // FFT_N
    private static final int FRAME_LEN = 400; // 25ms @ 16khz
    private static final int FRAME_SHIFT = 160; // 10ms @ 16khz
    private static final int FEATURE_DIM = 80; // 特征dim

    /**
    * 设置模型文件路径（仅供 模型registry 在 SPI 实例化后注入使用）。
    * @param modelPath 模型路径
    */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public String name() {
        return "wespeaker-resnet34";
    }

    @Override
    public float[] translate(byte[] audioData) {
        try {
            ensurePrepared();
            float[] pcm = decodeToPcm(audioData);
            if (pcm == null || pcm.length == 0) {
                throw new IllegalArgumentException("Cannot decode audio data");
            }

            // 截断到最长 30 秒
            int maxSamples = targetSampleRate * 30;
            if (pcm.length > maxSamples) {
                float[] truncated = new float[maxSamples];
                System.arraycopy(pcm, 0, truncated, 0, maxSamples);
                pcm = truncated;
            }

            // fbank 80 维特征 [T, 80]
            double[][] feat = computeFbank80(pcm);

            // 展平为 [1, T, 80] rank=3
            long[] shape = {1, feat.length, FEATURE_DIM};
            float[] flat = new float[feat.length * FEATURE_DIM];
            int pos = 0;
            for (double[] row : feat) {
                for (double v : row) {
                    flat[pos++] = (float) v;
                }
            }

            String inputName = session.getInputNames().iterator().next();
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flat), shape);
                 OrtSession.Result results = session.run(Map.of(inputName, inputTensor))) {

                float[][] output = (float[][]) results.get(0).getValue(); // [P3C 3.7 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
                float[] embedding = output[0];

                // L2 归一化
                double norm = 0.0;
                for (float v : embedding) {
                    norm += v * v;
                }
                norm = Math.sqrt(norm);
                if (norm > 0) {
                    for (int i = 0; i < embedding.length; i++) {
                        embedding[i] = (float) (embedding[i] / norm);
                    }
                }
                return embedding;
            }
        } catch (Exception e) {
            throw new RuntimeException("Wespeaker inference failed", e);
        }
    }

    /**
    * ensureprepared。
    */
    private void ensurePrepared() throws Exception {
        if (prepared) {
            return;
        }
        synchronized (this) {
            if (prepared) {
                return;
            }
            ortEnv = OrtEnvironment.getEnvironment();
            Path modelFile = resolveModelPath(modelPath);
            if (modelFile == null || !Files.exists(modelFile)) {
                throw new IllegalStateException("Model file not found: " + modelPath);
            }
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = ortEnv.createSession(modelFile.toString(), opts);
            prepared = true;
            log.info("[WespeakerEmbedding] model loaded: {}", modelFile);
        }
    }

    /**
    * resolve模型路径。
    * @param pathStr 路径str
    * @return resolve模型路径的结果
    */
    private static Path resolveModelPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return null;
        }
        Path abs = Path.of(pathStr);
        if (Files.exists(abs)) {
            return abs;
        }
        try {
            var is = WespeakerEmbeddingTranslator.class.getClassLoader()
                    .getResourceAsStream(pathStr.startsWith("/") ? pathStr.substring(1) : pathStr);
            if (is != null) {
                Path tmp = Files.createTempFile("wespeaker-", ".onnx");
                tmp.toFile().deleteOnExit();
                Files.write(tmp, is.readAllBytes());
                is.close();
                return tmp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
    * decode转为pcm。
    * @param audioData 音频数据
    * @return decode转为pcm的结果
    */
    private float[] decodeToPcm(byte[] audioData) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(audioData));
            AudioFormat fmt = ais.getFormat();

            if (fmt.getSampleRate() != targetSampleRate) {
                AudioFormat targetFmt = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        targetSampleRate, 16, 1, 2, targetSampleRate, false);
                ais = AudioSystem.getAudioInputStream(targetFmt, ais);
            }

            byte[] allBytes = ais.readAllBytes();
            ais.close();

            int samples = allBytes.length / 2;
            float[] pcm = new float[samples];
            for (int i = 0; i < samples; i++) {
                short s = (short) ((allBytes[i * 2] & 0xFF) | (allBytes[i * 2 + 1] << 8));
                pcm[i] = s / 32768.0f;
            }
            return pcm;
        } catch (Exception e) {
            log.warn("[WespeakerEmbedding] audio decode failed: {}", e.getMessage());
            return null;
        }
    }

    /**
    * Kaldi-style fbank 80 维特征提取
    *
    * @param samples 样本
    * @return computeFbank80的结果
    */
    private double[][] computeFbank80(float[] samples) {
        int nFreq = FFT_N / 2 + 1;
        int frames = Math.max(1, (samples.length - FRAME_LEN) / FRAME_SHIFT + 1);

        // Povey 窗
        double[] window = new double[FRAME_LEN];
        for (int j = 0; j < FRAME_LEN; j++) {
            window[j] = Math.pow(0.5 - 0.5 * Math.cos(2.0 * Math.PI * j / FRAME_LEN), 0.85);
        }

        // mel 滤波器
        double[][] melFilters = buildKaldiMelFilters(nFreq);

        double[][] feat = new double[frames][FEATURE_DIM];
        double[] re = new double[FFT_N];
        double[] im = new double[FFT_N];

        for (int f = 0; f < frames; f++) {
            int off = f * FRAME_SHIFT;
            double[] frame = new double[FRAME_LEN];
            // 预加重 + 去直流 + 加窗
            double mean = 0;
            for (int j = 0; j < FRAME_LEN; j++) {
                int idx = off + j;
                if (idx >= samples.length) {
                    break;
                }
                float cur = samples[idx];
                frame[j] = (j == 0) ? cur : (cur - 0.97F * samples[idx - 1]);
                mean += frame[j];
            }
            mean /= FRAME_LEN;
            for (int j = 0; j < FRAME_LEN; j++) {
                frame[j] = (frame[j] - mean) * window[j];
            }

            fftRadix2(frame, re, im);

            for (int m = 0; m < FEATURE_DIM; m++) {
                double energy = 0;
                for (int k = 0; k < nFreq; k++) {
                    double power = re[k] * re[k] + im[k] * im[k];
                    energy += power * melFilters[k][m];
                }
                feat[f][m] = Math.log(Math.max(energy, 1e-30));
            }
        }
        return feat;
    }

    /**
    * 构建kaldimel过滤器。
    * @param nFreq nfreq
    * @return 构建kaldimel过滤器的结果
    */
    private static double[][] buildKaldiMelFilters(int nFreq) {
        double[][] filters = new double[nFreq][FEATURE_DIM];

        double melLow = hzToMel(20.0);
        double melHigh = hzToMel(Math.min(8000.0, SAMPLE_RATE / 2.0));
        double[] melPoints = new double[FEATURE_DIM + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = melLow + (melHigh - melLow) * i / (FEATURE_DIM + 1);
        }
        double[] binPoints = new double[melPoints.length];
        for (int i = 0; i < melPoints.length; i++) {
            binPoints[i] = (FFT_N + 1) * melToHertz(melPoints[i]) / SAMPLE_RATE;
        }

        for (int m = 0; m < FEATURE_DIM; m++) {
            int left = (int) Math.floor(binPoints[m]);
            int center = (int) Math.floor(binPoints[m + 1]);
            int right = (int) Math.floor(binPoints[m + 2]);
            for (int k = left; k < center && k < nFreq; k++) {
                if (k >= 0) {
                    double w = (k - binPoints[m]) / (binPoints[m + 1] - binPoints[m]);
                    if (w > 0) {
                        filters[k][m] = w;
                    }
                }
            }
            for (int k = center; k < right && k < nFreq; k++) {
                if (k >= 0) {
                    double w = (binPoints[m + 2] - k) / (binPoints[m + 2] - binPoints[m + 1]);
                    if (w > 0 && filters[k][m] < w) {
                        filters[k][m] = w;
                    }
                }
            }
        }
        return filters;
    }

    /**
    * hz转为mel。
    * @param hz hz
    * @return hz转为mel的结果
    */
    private static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    /**
    * mel转为hertz。
    * @param mel mel
    * @return mel转为hertz的结果
    */
    private static double melToHertz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    /**
    * fftradix2。
    * @param inRe 入re
    * @param outRe 出re
    * @param outIm 出im
    */
    private static void fftRadix2(double[] inRe, double[] outRe, double[] outIm) {
        int n = inRe.length;
        for (int i = 0; i < n; i++) {
            outRe[i] = inRe[i];
            outIm[i] = 0;
        }
        // 位反转
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = outRe[i]; outRe[i] = outRe[j]; outRe[j] = tr;
                double ti = outIm[i]; outIm[i] = outIm[j]; outIm[j] = ti;
            }
        }
        // 蝶形
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1, curIm = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k;
                    int v = i + k + len / 2;
                    double tRe = curRe * outRe[v] - curIm * outIm[v];
                    double tIm = curRe * outIm[v] + curIm * outRe[v];
                    outRe[v] = outRe[u] - tRe;
                    outIm[v] = outIm[u] - tIm;
                    outRe[u] += tRe;
                    outIm[u] += tIm;
                    double nRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                }
            }
        }
    }
}
