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
* CAM++ 声纹嵌入提取翻译器（纯 ONNX Runtime 实现，192维）。
*
* <h2>模型说明</h2>
* <p>CAM++ 是阿里 DAMO 提出的说话人验证模型，中文优化：
* <ul>
*   <li><b>输入</b>：16kHz 单声道 PCM float 音频采样数组。</li>
*   <li><b>输出</b>：192 维 L2 归一化嵌入向量。</li>
*   <li><b>用途</b>：声纹识别、说话人验证、声纹入库检索。</li>
* </ul>
* </p>
*
* @author CH
* @since 4.0.0.44
 */
@Slf4j
public class CampplusEmbeddingTranslator implements ITranslator<byte[], float[]> {

    private static final int SAMPLE_RATE = 16000; // 样本rate
    private static final int FEATURE_DIM = 80; // 特征dim
    private static final int FFT_N = 512; // FFT_N
    private static final int FRAME_LEN = 400; // 帧len
    private static final int FRAME_SHIFT = 160; // 帧Shift
    private static final int EMBEDDING_DIM = 192; // 嵌入dim

    private OrtEnvironment ortEnv; // ortenv
    private OrtSession session; // 会话
    private String modelPath; // 模型路径

    /**
    * 设置模型文件路径（仅供 模型registry 在 SPI 实例化后注入使用）。
    *
    * @param modelPath 模型路径
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }
    private double[][] melFilters; // mel过滤器
    private volatile boolean prepared = false; // prepared

    @Override
    public String name() {
        return "campplus-voiceprint";
    }

    @Override
    public float[] translate(byte[] audioData) {
        try {
            ensurePrepared();
            float[] pcm = decodeToPcm(audioData);
            if (pcm == null || pcm.length == 0) {
                throw new IllegalArgumentException("Cannot decode audio data");
            }

 // Compute fbank 80-dim 特征
            double[][] feat80 = computeFbank80(pcm);

 // CAM++ 取 raw fbank [1, T, 80]
            long[] shape = {1, feat80.length, FEATURE_DIM};
            float[] flat = new float[feat80.length * FEATURE_DIM];
            int pos = 0;
            for (double[] row : feat80) {
                for (double v : row) {
                    flat[pos++] = (float) v;
                }
            }

 // 创建 输入 tensor
            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(flat), shape);

 // 运行 推理
            String inputName = session.getInputNames().iterator().next();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            try (OrtSession.Result results = session.run(inputs)) {
 // 输出 shape: [1, 192]
                float[][] output = (float[][]) results.get(0).getValue();
                float[] embedding = output[0];

                // L2 normalize
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
            throw new RuntimeException("CAM++ inference failed", e);
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
            melFilters = buildKaldiMelFilters(FFT_N / 2 + 1);

            Path modelFile = resolveModelPath(modelPath);
            if (modelFile == null || !Files.exists(modelFile)) {
                throw new IllegalStateException("Model file not found: " + modelPath);
            }
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = ortEnv.createSession(modelFile.toString(), opts);
            prepared = true;
            log.info("[CampplusEmbedding] model loaded: {}", modelFile);
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
            var is = CampplusEmbeddingTranslator.class.getClassLoader()
                    .getResourceAsStream(pathStr.startsWith("/") ? pathStr.substring(1) : pathStr);
            if (is != null) {
                Path tmp = Files.createTempFile("campplus-", ".onnx");
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

            if (fmt.getSampleRate() != SAMPLE_RATE) {
                AudioFormat targetFmt = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        SAMPLE_RATE, 16, 1, 2, SAMPLE_RATE, false);
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
            log.warn("[CampplusEmbedding] audio decode failed: {}", e.getMessage());
            return null;
        }
    }

    /**
    * Kaldi-style fbank 80-dim 特征 extraction
    *
    * @param samples 样本
    * @return computeFbank80的结果
     */
    private double[][] computeFbank80(float[] samples) {
        int nFreq = FFT_N / 2 + 1;
        int frames = Math.max(1, (samples.length - FRAME_LEN) / FRAME_SHIFT + 1);

 // Povey 窗口
        double[] window = new double[FRAME_LEN];
        for (int j = 0; j < FRAME_LEN; j++) {
            window[j] = Math.pow(0.5 - 0.5 * Math.cos(2.0 * Math.PI * j / FRAME_LEN), 0.85);
        }

        double[][] feat = new double[frames][FEATURE_DIM];
        double[] re = new double[FFT_N];
        double[] im = new double[FFT_N];

        for (int f = 0; f < frames; f++) {
            int off = f * FRAME_SHIFT;
            double[] frame = new double[FRAME_LEN];
            frame[0] = off < samples.length ? samples[off] : 0;
            for (int j = 1; j < FRAME_LEN; j++) {
                float cur = off + j < samples.length ? samples[off + j] : 0F;
                frame[j] = cur - 0.97F * samples[off + j - 1];
            }
            double mean = 0;
            for (double v : frame) {
                mean += v;
            }
            mean /= FRAME_LEN;
            double var = 0;
            for (double v : frame) {
                var += (v - mean) * (v - mean);
            }
            var /= FRAME_LEN;
            double std = Math.sqrt(var + 1e-9);
            for (int j = 0; j < FRAME_LEN; j++) {
                frame[j] = (frame[j] - mean) / std;
                frame[j] *= window[j];
            }

            java.util.Arrays.fill(re, 0.0);
            java.util.Arrays.fill(im, 0.0);
            for (int j = 0; j < FRAME_LEN; j++) {
                re[j] = frame[j];
            }
            fft(re, im);

            double[] powerSpectrum = new double[nFreq];
            for (int k = 0; k < nFreq; k++) {
                powerSpectrum[k] = re[k] * re[k] + im[k] * im[k];
            }

            for (int m = 0; m < FEATURE_DIM; m++) {
                double sum = 0;
                for (int k = 0; k < nFreq; k++) {
                    sum += powerSpectrum[k] * melFilters[k][m];
                }
                feat[f][m] = Math.log(Math.max(sum, 1e-10));
            }
        }
        return feat;
    }

    /**
    * 构建kaldimel过滤器。
    * @param nFreq nfreq
    * @return 构建kaldimel过滤器的结果
     */
    private double[][] buildKaldiMelFilters(int nFreq) {
        double lowFreq = 20.0;
        double highFreq = 8000.0;
        int nFilters = FEATURE_DIM + 2;
        double[] melPoints = new double[nFilters];
        for (int i = 0; i < nFilters; i++) {
            double mel = lowFreq + (highFreq - lowFreq) * i / (nFilters - 1);
            melPoints[i] = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
        }

        double hzPerBin = (double) (SAMPLE_RATE / 2) / nFreq;
        int[] binPoints = new int[nFilters];
        for (int i = 0; i < nFilters; i++) {
            binPoints[i] = (int) Math.floor(melPoints[i] / hzPerBin);
        }

        double[][] filters = new double[nFreq][FEATURE_DIM];
        for (int m = 0; m < FEATURE_DIM; m++) {
            int start = binPoints[m];
            int center = binPoints[m + 1];
            int end = binPoints[m + 2];
            for (int k = start; k < center; k++) {
                if (k < nFreq && center > start) {
                    filters[k][m] = (double) (k - start) / (center - start);
                }
            }
            for (int k = center; k < end; k++) {
                if (k < nFreq && end > center) {
                    filters[k][m] = (double) (end - k) / (end - center);
                }
            }
        }
        return filters;
    }

    /**
    * fft。
    * @param re re
    * @param im im
     */
    private void fft(double[] re, double[] im) {
        int n = re.length;
        if (n == 0) {
            return;
        }
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - bits);
            if (i < j) {
                double temp = re[i]; re[i] = re[j]; re[j] = temp;
                temp = im[i]; im[i] = im[j]; im[j] = temp;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            double angle = -2.0 * Math.PI / size;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            for (int i = 0; i < n; i += size) {
                double curRe = 1.0, curIm = 0.0;
                for (int j = 0; j < half; j++) {
                    int a = i + j;
                    int b = i + j + half;
                    double tRe = curRe * re[b] - curIm * im[b];
                    double tIm = curRe * im[b] + curIm * re[b];
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    double newCurRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newCurRe;
                }
            }
        }
    }
}
