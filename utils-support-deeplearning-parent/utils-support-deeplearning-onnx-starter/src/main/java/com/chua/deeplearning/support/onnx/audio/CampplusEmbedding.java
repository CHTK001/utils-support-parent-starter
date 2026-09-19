package com.chua.deeplearning.support.onnx.audio;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CAM++ 声纹嵌入提取器（中文优化，192 维）。
 *
 * <p>基于 ModelScope {@code iic/speech_campplus_sv_zh-cn_16k-common}，
 * 通过 Kaldi-style fbank 80 维特征 + CAM++ ONNX 推理生成 192 维说话人嵌入向量。</p>
 *
 * <p>模型文件 {@code audio/speaker/campplus_zh_cn_common_200k.onnx} 由 jar
 * {@code utils-support-models-onnx-sensevoice} 提供。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CampplusEmbedding {

    /**
     * 目标采样率
     */
    private static final int SAMPLE_RATE = 16000;

    /**
     * fbank 维数
     */
    private static final int FEATURE_DIM = 80;

    /**
     * FFT 窗口大小
     */
    private static final int FFT_N = 512;

    /**
     * 帧长 25ms
     */
    private static final int FRAME_LEN = 400;

    /**
     * 帧移 10ms
     */
    private static final int FRAME_SHIFT = 160;

    /**
     * 嵌入维度
     */
    private static final int EMBEDDING_DIM = 192;

    private final OrtEnvironment ortEnv; // ortenv
    private final OrtSession session; // 会话
    private final String inputName; // 输入名称
    private final double[][] melFilters; // mel过滤器

    /**
     * campplus嵌入。
     * @param session 会话
     * @param env env
     */
    private CampplusEmbedding(OrtSession session, OrtEnvironment env) {
        this.session = session;
        this.ortEnv = env;
        this.inputName = session.getInputNames().iterator().next();
        this.melFilters = buildKaldiMelFilters(FFT_N / 2 + 1);
        log.info("[Campplus] model loaded, input={}", inputName);
    }

    /**
     * 从 类路径 加载模型（自动从 JAR 解压到缓存目录）。
     *
     * @return CampplusEmbedding 实例
     */
    public static CampplusEmbedding load() {
        try {
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"),
                    "chua-models", "campplus");
            Path modelPath = cacheDir.resolve("campplus_zh_cn_common_200k.onnx");
            if (!Files.isRegularFile(modelPath)) {
                Files.createDirectories(cacheDir);
 // 直接从 类路径 读取（兼容 jar 内嵌模型），无需 NAT加载 SPI
                String[] candidates = {
                        "audio/speaker/campplus_zh_cn_common_200k.onnx",
                        "audio/speaker/campplus/model.onnx"
                };
                boolean copied = false;
                for (String r : candidates) {
                    try (var is = CampplusEmbedding.class.getClassLoader().getResourceAsStream(r)) {
                        if (is != null) {
                            Files.write(modelPath, is.readAllBytes());
                            copied = true;
                            break;
                        }
                    } catch (Exception ignore) {
                    }
                }
                if (!copied) {
                    throw new IllegalStateException("未在 classpath 找到 campplus 模型: " + java.util.Arrays.toString(candidates));
                }
            }
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            OrtSession session = env.createSession(modelPath.toString(), opts);
            String inputName = session.getInputNames().iterator().next();
            return new CampplusEmbedding(session, env);
        } catch (Exception e) {
            throw new RuntimeException("CAM++ 模型加载失败", e);
        }
    }

    /**
     * 提取声纹嵌入。
     *
     * @param samples 16khz 单声道 [-1,1] 浮点采样
     * @return L2 归一化 192 维嵌入向量
     */
    public float[] extract(float[] samples) {
        double[][] feat80 = computeFbank80(samples);

        // CAM++ takes raw fbank [1, T, 80] directly (no LFR/CMVN)
        long[] shape = {1, feat80.length, FEATURE_DIM};
        float[] flat = new float[feat80.length * FEATURE_DIM];
        int pos = 0;
        for (double[] row : feat80) {
            for (double v : row) {
                flat[pos++] = (float) v;
            }
        }
        try (OnnxTensor t = OnnxTensor.createTensor(ortEnv,
                FloatBuffer.wrap(flat), shape);
             OrtSession.Result r = session.run(Map.of(inputName, t))) {
            float[] emb = toFloatArray((OnnxTensor) r.get(0));
            l2Normalize(emb);
            return emb;
        } catch (Exception e) {
            throw new RuntimeException("CAM++ 推理失败", e);
        }
    }

    /**
     * 获取嵌入维度。
     *
     * @return 192
     */
    public int getDimension() {
        return EMBEDDING_DIM;
    }

    /**
     * Kaldi-style fbank 80 维计算
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
            window[j] = Math.pow(
                    0.5 - 0.5 * Math.cos(2.0 * Math.PI * j / FRAME_LEN), 0.85);
        }

        // Mel 滤波器组（HTK 刻度 20~8000Hz）
        double[][] melFilters = buildKaldiMelFilters(nFreq);

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
     * 构建 Kaldi-style mel 滤波器组 [nfreq bins][特征_DIM mels]
     *
     * @param nFreq nfreq
     * @return 构建kaldimel过滤器的结果
     */
    private static double[][] buildKaldiMelFilters(int nFreq) {
        double[][] filters = new double[nFreq][FEATURE_DIM];

        double melLow = hzToMel(20.0);
        double melHigh = hzToMel(Math.min(8000.0, SAMPLE_RATE / 2.0));
        double[] melPoints = new double[FEATURE_DIM + 2];
        for (int b = 0; b < FEATURE_DIM + 2; b++) {
            melPoints[b] = melLow + (melHigh - melLow) * b / (FEATURE_DIM + 1);
        }
        int[] binPts = new int[FEATURE_DIM + 2];
        for (int b = 0; b < FEATURE_DIM + 2; b++) {
            double hz = melToHertz(melPoints[b]);
            binPts[b] = (int) Math.floor((FFT_N + 1.0) * hz / SAMPLE_RATE);
        }

        for (int b = 0; b < FEATURE_DIM; b++) {
            int left = binPts[b], center = binPts[b + 1], right = binPts[b + 2];
            if (center <= left) {
                center = left + 1;
            }
            if (right <= center) {
                right = center + 1;
            }
            for (int k = left; k < Math.min(right, nFreq); k++) {
                double w;
                if (k <= center && center > left) {
                    w = (double) (k - left) / (center - left);
                } else if (right > center) {
                    w = (double) (right - k) / (right - center);
                } else {
                    w = 1.0;
                }
                if (w > 0 && k < nFreq) {
                    filters[k][b] = w;
                }
            }
        }
        return filters;
    }

     /**
      * fftradix2。Radix-2 迭代 FFT
      * @param frameSamples 帧样本
      * @param re re
      * @param im im
      * @param mel mel
      * @return mel转为hertz的结果
      */
    private static void fftRadix2(double[] frameSamples, double[] re, double[] im) {
        int n = FFT_N;
        System.arraycopy(frameSamples, 0, re, 0, Math.min(frameSamples.length, n));
        java.util.Arrays.fill(im, 0, n, 0);
        java.util.Arrays.fill(re, frameSamples.length, n, 0);

        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                double ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int jj = 0; jj < half; jj++) {
                    int u = i + jj, v = u + half;
                    double tR = cr * re[v] - ci * im[v];
                    double tI = cr * im[v] + ci * re[v];
                    re[v] = re[u] - tR;
                    im[v] = im[u] - tI;
                    re[u] += tR;
                    im[u] += tI;
                    double nr = cr * wr - ci * wi;
                    double ni = cr * wi + ci * wr;
                    cr = nr;
                    ci = ni;
                /**
                 * 转为floatarray。
                 * @param t t
                 * @return 转为floatarray的结果
                 */
                }
            }
        }
    }

    /**
     * 转为Float数组。
     *
     * @param t 方法入参 t
     * @return 结果值
     */
    private static float[] toFloatArray(ai.onnxruntime.OnnxTensor t) {
        FloatBuffer fb = t.getFloatBuffer();
        /**
         * l2Normalize。
         * @param v v
         */
        float[] arr = new float[fb.remaining()];
        fb.get(arr);
        return arr;
    }

    /**
     * l2Normalize。
     *
     * @param v 方法入参 v
     */
    private static void l2Normalize(float[] v) {
        double s = 0;
        /**
         * hz转为mel。
         * @param hz hz
         * @return hz转为mel的结果
         * @param mel mel
         */
        for (float x : v) {
            s += x * x;
        }
        double nn = Math.sqrt(s);
        if (nn > 1e-12) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= nn;
            }
        }
    }

    /**
     * hz转为Mel。
     *
     * @param hz 方法入参 hz
     * @return 结果数值
     */
    private static double hzToMel(double hz) {
        return 1127.0 * Math.log(1.0 + hz / 700.0);
    }

    /**
     * mel转为Hertz。
     *
     * @param mel 方法入参 mel
     * @return 结果数值
     */
    private static double melToHertz(double mel) {
        return 700.0 * (Math.exp(mel / 1127.0) - 1.0);
    }

    /** 关闭会话 */
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignore) {
            }
        }
    }

        /**
         * 将 double 矩阵拍平并转为 float 数组。
         *
         * @param mat mat
         * @return flatten的结果
         */
    private static float[] flatten(double[][] mat) {
        int rows = mat.length;
        int cols = rows > 0 ? mat[0].length : 0;
        float[] out = new float[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                out[r * cols + col] = (float) mat[r][col];
            }
        }
        return out;
    }

/**
 * 二维数组展平为一维。
 * @param mat mat
 * @return flatten的结果
 */
    private static float[] flatten(float[][] mat) {
        int total = 0;
        for (float[] row : mat) {
            total += row.length;
        }
        float[] out = new float[total];
        int pos = 0;
        for (float[] row : mat) {
            System.arraycopy(row, 0, out, pos, row.length);
            pos += row.length;
        }
        return out;
    }

    }
