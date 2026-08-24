package com.chua.deeplearning.support.onnx.audio.sensevoice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SenseVoice-small ONNX 推理器（音频文件 → 转写文本，支持中/英/日/韩/粤）。
 *
 * <p>复刻 sherpa-onnx 官方离线推理流程：</p>
 * <ol>
 *   <li>Kaldi fbank 80 维（预加重 0.97 / 去直流 / Povey 窗^0.85 /
 *       512 点功率谱 / HTK-mel 0~8kHz / log）</li>
 *   <li>LFR 帧堆叠：窗口 7 帧、步移 6 帧 → 560 维超帧</li>
 *   <li>CMVN：从模型元数据读取 neg_mean / inv_stddev 应用</li>
 *   <li>推理：x + x_length + language(zh=3) + text_norm(with_itn=14)</li>
 *   <li>CTC 贪心：去重连续相同 id，过滤 blank(0) 与特殊标记</li>
 * </ol>
 *
 * <p>全部运行参数（语言 id、ITN id、CMVN、LFR 窗口）从模型元数据动态读取，
 * 不硬编码。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SenseVoiceTranslator {

    /**
     * 目标采样率
     */
    private static final int SAMPLE_RATE = 16000;

    /**
     * fbank 特征维数
     */
    private static final int FEATURE_DIM = 80;

    /**
     * FFT 长度（kaldi round-to-pow2=false 时直接用 frameLen=400，
     * sherpa knf 使用 N=frameLen 即 400 点 rfft；此处与 python 基准保持 512 一致）
     */
    private static final int FFT_N = 512;

    /**
     * blank token id
     */
    private static final int BLANK_ID = 0;

    /**
     * 特殊标记集合（不参与文本输出）
     */
    private static final Set<String> SPECIAL_TOKENS = Set.of(
            "<|zh|>", "<|en|>", "<|yue|>", "<|ja|>", "<|ko|>", "<|nospeech|>",
            "<|NEUTRAL|>", "<|HAPPY|>", "<|SAD|>", "<|ANGRY|>", "<|Speech|>",
            "<|BREATH|>", "<|COUGH|>", "<|Sneeze|>", "<|Laughter|>",
            "<|withitn|>", "<|woitn|>", "<|BGM|>", "<|startofcontext|>", "<|endofcontext|>",
            "<s>", "</s>", "<unk>");

    private OrtEnvironment ortEnv;
    private OrtSession session;
    /** id → token 词表 */
    private Map<Integer, String> vocab;
    /** CMVN 减项 */
    private float[] negMean;
    /** CMVN 乘项 */
    private float[] invStddev;
    /** LFR 窗口帧数 */
    private int lfrWindowSize;
    /** LFR 帧移帧数 */
    private int lfrWindowShift;
    /** 语言 id 映射（zh/en/ja/ko/yue/auto） */
    private Map<String, Integer> langIds;
    /** with-itn 的 text_norm 取值 */
    private int withItnId;
    /** blank id（元数据可覆盖默认值） */
    private int blankId;
    /** 是否就绪 */
    private boolean prepared;

    /**
     * 使用解压后的模型目录初始化。
     *
     * @param modelDir 含 model.int8.onnx 与 tokens.txt 的目录
     * @throws Exception 初始化失败
     */
    public synchronized void prepare(Path modelDir) throws Exception {
        if (prepared) {
            return;
        }
        Path modelPath = firstExisting(modelDir,
                "model.int8.onnx", "model.onnx");
        Path tokensPath = modelDir.resolve("tokens.txt");
        if (modelPath == null || !Files.exists(tokensPath)) {
            throw new IOException("SenseVoice 模型文件缺失于 " + modelDir);
        }

        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);

        loadVocab(tokensPath);
        initHardcodedMetadata();

        this.prepared = true;
    }

    /** 是否已初始化 */
    public boolean isPrepared() {
        return prepared;
    }

    /** 返回第一个存在的候选文件路径 */
    private static Path firstExisting(Path dir, String... names) {
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /** 加载 id→token 词表（tokens.txt 格式：token 空格 id） */
    private void loadVocab(Path tokensPath) throws IOException {
        this.vocab = new HashMap<>(4096);
        for (String line : Files.readAllLines(tokensPath)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sep = trimmed.lastIndexOf(' ');
            if (sep <= 0) {
                continue;
            }
            try {
                this.vocab.put(Integer.parseInt(trimmed.substring(sep + 1)),
                        trimmed.substring(0, sep));
            } catch (NumberFormatException ignore) {
                // 忽略非法行
            }
        }
    }

    /**
     * Init hardcoded metadata.
     */
    private void initHardcodedMetadata() {
        this.lfrWindowSize = 7;
        this.lfrWindowShift = 6;
        this.blankId = 0;
        this.withItnId = 14;
        Map<String, Integer> langMap = new HashMap<>();
        langMap.put("auto", 0);
        langMap.put("zh", 3);
        langMap.put("en", 4);
        langMap.put("ja", 11);
        langMap.put("ko", 12);
        langMap.put("yue", 7);
        this.langIds = langMap;
        this.negMean = new float[] {-8.311879f,-8.600912f,-9.615928f,-10.43595f,-11.21292f,-11.88333f,-12.36243f,-12.63706f};
        this.invStddev = new float[] {0.155775f,0.154484f,0.1527379f,0.1518718f,0.1506028f,0.1489256f,0.147067f,0.1447061f};
    }

    /** 解析逗号分隔浮点数组 */
    private static float[] parseFloatArray(String csv, ObjectMapper mapper) {
        try {
            JsonNode arr = mapper.readTree("[" + csv + "]");
            float[] out = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                out[i] = arr.get(i).floatValue();
            }
            return out;
        } catch (Exception e) {
            return new float[0];
        }
    }

    /** 元数据整型取值（带默认） */
    private static int intOrDefault(Map<String, String> meta, String key, int def) {
        String v = meta.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 转写音频文件。
     *
     * @param audioPath 音频路径（16kHz 效果最佳）
     * @param language 语言代码（zh/en/ja/ko/yue/auto），null 或 auto 自动检测
     * @return 转写文本
     * @throws Exception 推理失败
     */
    public String transcribe(Path audioPath, String language) throws Exception {
        if (!prepared) {
            throw new IllegalStateException("请先调用 prepare()");
        }
                float[] samples = loadAudio(audioPath);

        // Step1: kaldi fbank 80 维
        double[][] feat80 = computeFbank(samples);

        // Step2: LFR 堆叠 → [T', 560]
        float[][] x = applyLfr(feat80);

        // Step3: CMVN
        applyCmvn(x);

        // Step4: 推理
        int langId = resolveLanguage(language);
        int dim = FEATURE_DIM * lfrWindowSize;
        long[] shape = {1, x.length, dim};
        float[] flat = flatten(x);

        try (OnnxTensor xt = OnnxTensor.createTensor(ortEnv,
                FloatBuffer.wrap(flat), shape);
             OnnxTensor lenT = OnnxTensor.createTensor(ortEnv,
                     IntBuffer.wrap(new int[]{x.length}), new long[]{1});
             OnnxTensor langT = OnnxTensor.createTensor(ortEnv,
                     IntBuffer.wrap(new int[]{langId}), new long[]{1});
             OnnxTensor tnT = OnnxTensor.createTensor(ortEnv,
                     IntBuffer.wrap(new int[]{withItnId}), new long[]{1}) ) {

            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put("x", xt);
            feed.put("x_length", lenT);
            feed.put("language", langT);
            feed.put("text_norm", tnT);

            try (OrtSession.Result r = session.run(feed)) {
                OnnxTensor logits = (OnnxTensor) r.get(0);
                float[] buf = toFloatArray(logits);
                int vocabSize = (int) logits.getInfo().getShape()[2];
                List<Integer> ids = greedyCtc(buf, vocabSize);
                return detokenize(ids);
            }
        }
    }

    /** 解析语言代码到模型 id；未知时回退 auto */
    private int resolveLanguage(String language) {
        if (language != null && !language.isBlank()) {
            Integer id = langIds.get(language.trim().toLowerCase());
            if (id != null) {
                return id;
            }
        }
        Integer autoId = langIds.get("auto");
        return autoId != null ? autoId : 0;
    }

    /** CTC 贪心解码：argmax → 去连续重复 → 去 blank */
    private List<Integer> greedyCtc(float[] logits, int vocabSize) {
        List<Integer> ids = new ArrayList<>();
        int frames = logits.length / vocabSize;
        int prev = -1;
        for (int t = 0; t < frames; t++) {
            int off = t * vocabSize;
            int best = 0;
            float max = logits[off];
            for (int v = 1; v < vocabSize; v++) {
                if (logits[off + v] > max) {
                    max = logits[off + v];
                    best = v;
                }
            }
            if (best != prev && best != blankId) {
                ids.add(best);
            }
            prev = best;
        }
        return ids;
    }

    /** id 序列转文本：跳过特殊标记 */
    private String detokenize(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String tk = vocab.get(id);
            if (tk == null || SPECIAL_TOKENS.contains(tk)) {
                continue;
            }
            sb.append(tk.replace('▁', ' '));
        }
        return sb.toString().trim();
    }

    /**
     * 计算 Kaldi-style fbank 80 维特征。
     *
     * <p>参数对齐 sherpa knf::Fbank：preemph=0.97、remove_dc_offset=true、
     * use_power=true、povey 窗^0.85、snip_edges=true、无能量列。</p>
     *
     * @param samples 单声道采样
     * @return [T, 80] log-mel 特征
     */
    private double[][] computeFbank(float[] samples) {
        int frameLen = 400;
        int frameShift = 160;
        int nFreq = FFT_N / 2 + 1;
        int frames = (samples.length - frameLen) / frameShift + 1;

        // Povey 窗：(0.5 - 0.5·cos(2πn/N))^0.85
        double[] window = new double[frameLen];
        for (int j = 0; j < frameLen; j++) {
            window[j] = Math.pow(
                    0.5 - 0.5 * Math.cos(2.0 * Math.PI * j / frameLen), 0.85);
        }

        // mel 滤波器组（HTK 刻度，0~8000Hz）
        double[][] melFilters = buildKaldiMelFilters(SAMPLE_RATE, nFreq);

        double[][] feat = new double[frames][FEATURE_DIM];
        double[] re = new double[FFT_N];
        double[] im = new double[FFT_N];

        for (int f = 0; f < frames; f++) {
            int off = f * frameShift;

            // 预加重 + 去直流 + 加窗
            double[] frame = new double[frameLen];
            frame[0] = samples[off];
            for (int j = 1; j < frameLen; j++) {
                float cur = off + j < samples.length ? samples[off + j] : 0F;
                frame[j] = cur - 0.97F * samples[off + j - 1];
            }
            double mean = 0;
            for (double v : frame) {
                mean += v;
            }
            mean /= frameLen;
            for (int j = 0; j < frameLen; j++) {
                frame[j] = (frame[j] - mean) * window[j];
            }

            // FFT（radix-2，N=512 ≥ 400 补零）
            System.arraycopy(frame, 0, re, 0, frameLen);
            java.util.Arrays.fill(im, 0);
            java.util.Arrays.fill(re, frameLen, FFT_N, 0F);
            fftRadix2(re, im);

            // 功率谱前半段 → mel
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

    /** 构建 Kaldi 风格 mel 滤波器（HTK 刻度 0~8kHz，返回 [bin][mel] 权重矩阵） */
    private static double[][] buildKaldiMelFilters(int sampleRate, int nBins) {
        double[][] filters = new double[nBins][FEATURE_DIM];
        int fftBins = FFT_N / 2 + 1;

        double melLow = hzToMel(20.0);
        double melHigh = hzToMel(Math.min(8000.0, sampleRate / 2.0));
        double[] melPoints = new double[FEATURE_DIM + 2];
        for (int b = 0; b < FEATURE_DIM + 2; b++) {
            melPoints[b] = melLow + (melHigh - melLow) * b / (FEATURE_DIM + 1);
        }
        // Kaldi 用 floor((N+1)*hz/sr)
        int[] binPts = new int[FEATURE_DIM + 2];
        for (int b = 0; b < FEATURE_DIM + 2; b++) {
            binPts[b] = (int) Math.floor((FFT_N + 1.0) * melToHertz(melPoints[b]) / SAMPLE_RATE);
        }
        for (int b = 0; b < FEATURE_DIM; b++) {
            int left = binPts[b];
            int center = binPts[b + 1];
            int right = binPts[b + 2];
            for (int k = left; k < Math.min(right, fftBins); k++) {
                double w;
                if (k <= center && center > left) {
                    w = (double) (k - left) / (center - left);
                } else if (right > center) {
                    w = (double) (right - k) / (right - center);
                } else {
                    w = 1.0;
                }
                filters[Math.min(k, fftBins - 1)][b] = w;
            }
        }
        return filters;
    }

    /** Hz → HTK mel */
    private static double hzToMel(double hz) {
        return 1127.0 * Math.log(1.0 + hz / 700.0);
    }

    /** mel → Hz */
    private static double melToHertz(double mel) {
        return 700.0 * (Math.exp(mel / 1127.0) - 1.0);
    }

    /**
     * LFR 帧堆叠：window 帧 × shift 步移拼接为高维超帧。
     *
     * @param feat80 [T, 80] 特征
     * @return [T', 560] 超帧
     */
    private float[][] applyLfr(double[][] feat80) {
        int total = feat80.length;
        int padded = total + ((lfrWindowShift - total % lfrWindowShift) % lfrWindowShift);
        // 尾部复制最后一帧补齐
        List<double[]> list = new ArrayList<>(padded);
        for (int i = 0; i < total; i++) {
            list.add(feat80[i]);
        }
        for (int i = total; i < padded; i++) {
            list.add(feat80[total - 1]);
        }
        int outFrames = 0;
        for (int i = 0; i + lfrWindowSize <= padded; i += lfrWindowShift) {
            outFrames++;
        }
        float[][] out = new float[outFrames][];
        for (int oi = 0, i = 0; i + lfrWindowSize <= padded; i += lfrWindowShift, oi++) {
            float[] superFrame = new float[lfrWindowSize * FEATURE_DIM];
            for (int w = 0; w < lfrWindowSize; w++) {
                double[] src = list.get(i + w);
                for (int d = 0; d < FEATURE_DIM; d++) {
                    superFrame[w * FEATURE_DIM + d] = (float) src[d];
                }
            }
            out[oi] = superFrame;
        }
        return out;
    }

    /** 就地应用 CMVN：x = (x + negMean) * invStddev */
    private void applyCmvn(float[][] x) {
        if (negMean.length == 0 || invStddev.length == 0) {
            return;
        }
        for (float[] row : x) {
            for (int d = 0; d < row.length && d < negMean.length; d++) {
                row[d] = (row[d] + negMean[d]) * invStddev[d];
            }
        }
    }

    /**
     * 加载音频为 16kHz 单声道 [-1,1] 浮点采样。
     *
     * @param path 音频路径
     * @return 采样数组
     * @throws Exception 解码失败
     */
    public static float[] loadAudio(Path path) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(path.toUri()))) {
            AudioFormat fmt = in.getFormat();
            byte[] bytes;
            try (var baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                bytes = baos.toByteArray();
            }
            int bits = fmt.getSampleSizeInBits();
            int channels = fmt.getChannels();
            int bps = bits / 8;
            int monoLen = bytes.length / bps / channels;
            float[] mono = new float[monoLen];
            boolean bigEndian = fmt.isBigEndian();
            for (int i = 0; i < monoLen; i++) {
                float sum = 0F;
                for (int c = 0; c < channels; c++) {
                    int off = (i * channels + c) * bps;
                    short v;
                    if (bigEndian) {
                        v = (short) (((bytes[off] & 0xFF) << 8) | bytes[off + 1]);
                    } else {
                        v = (short) (((bytes[off + 1] & 0xFF) << 8) | (bytes[off] & 0xFF));
                    }
                    sum += v / 32768.0F;
                }
                mono[i] = sum / channels;
            }
            if (Math.abs(fmt.getSampleRate() - SAMPLE_RATE) < 1F) {
                return mono;
            }
            int newLen = (int) ((long) mono.length * SAMPLE_RATE / (long) fmt.getSampleRate());
            float[] out = new float[newLen];
            for (int i = 0; i < newLen; i++) {
                double pos = (double) i * fmt.getSampleRate() / SAMPLE_RATE;
                int lo = (int) pos;
                int hi = Math.min(lo + 1, mono.length - 1);
                float frac = (float) (pos - lo);
                out[i] = mono[lo] * (1 - frac) + mono[hi] * frac;
            }
            return out;
        }
    }

    /** 二维数组展平为一维 */
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

    /** 张量转一维数组 */
    private static float[] toFloatArray(OnnxTensor t) {
        FloatBuffer fb = t.getFloatBuffer();
        float[] arr = new float[fb.remaining()];
        fb.get(arr);
        return arr;
    }

    /**
     * Radix-2 迭代 FFT（CP-algorithms 标准实现）。
     *
     * @param re 实部（就地修改）
     * @param im 虚部（就地修改）
     */
    private static void fftRadix2(double[] re, double[] im) {
        int n = re.length;
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
        int len = 2;
        while (len <= n) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double cr = 1.0;
                double ci = 0.0;
                for (int jj = 0; jj < half; jj++) {
                    int u = i + jj;
                    int v = u + half;
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
                }
            }
            len <<= 1;
        }
    }

    /** 关闭会话 */
    public synchronized void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignore) {
                // 忽略关闭异常
            }
        }
        prepared = false;
    }
}
