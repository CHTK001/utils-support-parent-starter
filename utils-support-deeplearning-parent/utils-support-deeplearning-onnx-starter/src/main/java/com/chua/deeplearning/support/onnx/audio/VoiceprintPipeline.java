package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.vector.Vector;
import com.chua.deeplearning.support.audio.FileVectorStorage;
import com.chua.common.support.vector.VectorStorage;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 声纹识别管线（链式配置，双方法极简 API）。
 *
 * <p><b>两条语义动作</b>：</p>
 * <ul>
 *   <li><b>入库</b> {@link #enroll(String, Path)}：音频 → 特征提取 →
 *       向量经 {@link VectorStorage} 落盘（人脸体系同款向量设施）</li>
 *   <li><b>检索</b> {@link #search(Path, int)}：音频 → 特征提取 →
 *       库内余弦相似度比对 → TopK 匹配结果</li>
 * </ul>
 *
 * <p><b>特征提取链路</b>：重采样 → 分帧加窗 → 精确 DFT 功率谱 →
 * HTK-mel 三角滤波 → log10 → 时序聚合（均值+标准差）→ L2 归一化。</p>
 *
 * <p><b>用法</b>：</p>
 *
 * <pre>{@code
 *   VoiceprintPipeline vp = VoiceprintPipeline.create()
 *           .sampleRate(16000)
 *           .melBands(40)
 *           .storageDir(Path.of("./vp-db"));
 *
 *   vp.enroll("alice", Path.of("alice.wav"));               // 入库
 *   List<Match> top = vp.search(Path.of("query.wav"), 3);    // 检索
 * }</pre>
 *
 * <p><b>注意</b>：链式参数必须在首次 {@code enroll/search} 前设置完成；
 * 提取与入库启动后修改核心参数将抛出 {@link IllegalStateException}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VoiceprintPipeline {

    /**
     * 默认采样率
     */
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    /**
     * 默认帧长 25ms@16kHz
     */
    private static final int DEFAULT_FRAME_LEN = 400;

    /**
     * 默认帧移 10ms@16kHz
     */
    private static final int DEFAULT_FRAME_SHIFT = 160;

    /**
     * 默认 mel 维数
     */
    private static final int DEFAULT_MEL_BANDS = 40;

    /**
     * 采样率
     */
    private int sampleRate = DEFAULT_SAMPLE_RATE;

    /**
     * 帧长（采样点）
     */
    private int frameLength = DEFAULT_FRAME_LEN;

    /**
     * 帧移（采样点）
     */
    private int frameShift = DEFAULT_FRAME_SHIFT;

    /**
     * mel 频带数
     */
    private int melBands = DEFAULT_MEL_BANDS;

    /**
     * 文件库持久化目录（未注入外部存储时使用）
     */
    private Path storageDir;

    /**
     * 外部注入的向量存储（优先于文件库）
     */
    private VectorStorage externalStorage;

    /**
     * 惰性初始化的 mel 滤波器组
     */
    private double[][] filters;

    /**
     * 惰性初始化的向量存储
     */
    private VectorStorage storage;

    /**
     * 是否已进入使用阶段（此后禁止改参）
     */
    private boolean started;

    private VoiceprintPipeline() {
    }

    /**
     * 创建声纹管线实例。
     *
     * @return 管线实例
     */
    public static VoiceprintPipeline create() {
        return new VoiceprintPipeline();
    }

    /**
     * 设置采样率（默认 16000）。
     *
     * @param rate 采样率 Hz
     * @return 管线实例
     */
    public VoiceprintPipeline sampleRate(int rate) {
        checkNotStarted("sampleRate");
        this.sampleRate = rate;
        return this;
    }

    /**
     * 设置帧长采样点数（默认 400，约 25ms）。
     *
     * @param points 帧长
     * @return 管线实例
     */
    public VoiceprintPipeline frameLength(int points) {
        checkNotStarted("frameLength");
        this.frameLength = points;
        return this;
    }

    /**
     * 设置帧移采样点数（默认 160，约 10ms）。
     *
     * @param points 帧移
     * @return 管线实例
     */
    public VoiceprintPipeline frameShift(int points) {
        checkNotStarted("frameShift");
        this.frameShift = points;
        return this;
    }

    /**
     * 设置 mel 频带数（默认 40；声纹维度 = 频带数 × 2）。
     *
     * @param bands 频带数
     * @return 管线实例
     */
    public VoiceprintPipeline melBands(int bands) {
        checkNotStarted("melBands");
        this.melBands = bands;
        return this;
    }

    /**
     * 设置文件向量库的持久化目录。
     *
     * @param dir 目录路径
     * @return 管线实例
     */
    public VoiceprintPipeline storageDir(Path dir) {
        checkNotStarted("storageDir");
        this.storageDir = dir;
        return this;
    }

    /**
     * 注入外部向量存储（Milvus/JVector 等），优先于文件库。
     *
     * @param storage 向量存储实现
     * @return 管线实例
     */
    public VoiceprintPipeline storage(VectorStorage storage) {
        checkNotStarted("storage");
        this.externalStorage = storage;
        return this;
    }

    /**
     * 方法一·入库：注册说话人声纹（重复 id 覆盖旧声纹）。
     *
     * @param speakerId 说话人标识
     * @param samplePath 参考音频
     * @throws Exception 提取或落库失败
     */
    public void enroll(String speakerId, Path samplePath) throws Exception {
        VectorStorage st = ensureReady();
        float[] fp = extract(AudioUtils.loadMono16k(samplePath));
        if (!st.add(speakerId, fp)) {
            throw new IllegalStateException("声纹入库失败: " + speakerId);
        }
        log.info("[Voiceprint] enrolled {}: dim={} 库内共 {}", speakerId, fp.length, st.size());
    }

    /**
     * 方法二·检索：待测音频与库内全部声纹做余弦相似度比对。
     *
     * <p>1:1 验证场景：search(1) 后检查 top1 的 id 与相似度阈值即可。</p>
     *
     * @param samplePath 待测音频
     * @param topK 返回条数
     * @return 按相似度降序的匹配列表
     * @throws Exception 提取或检索失败
     */
    public List<Match> search(Path samplePath, int topK) throws Exception {
        VectorStorage st = ensureReady();
        float[] probe = extract(AudioUtils.loadMono16k(samplePath));
        List<Vector> hits = st.search(probe, topK);
        List<Match> matches = new ArrayList<>(hits.size());
        for (Vector v : hits) {
            matches.add(new Match(v.id(), AudioUtils.cosine(probe, v.data())));
        }
        return matches;
    }

    /**
     * 已注册声纹数量。
     *
     * @return 数量
     */
    public int size() {
        return ensureReady().size();
    }

    /**
     * 匹配结果。
     *
     * @param speakerId 说话人标识
     * @param similarity 余弦相似度 [-1,1]
     */
    public record Match(String speakerId, double similarity) {
    }

    /**
     * 惰性初始化：解析维度、构建存储与滤波器组。
     *
     * @return 就绪的向量存储
     */
    private synchronized VectorStorage ensureReady() {
        if (!started) {
            validateParams();
            int dim = melBands * 2;
            if (externalStorage != null) {
                if (externalStorage.dimension() != dim) {
                    throw new IllegalArgumentException(
                            "外部向量库维度须为 " + dim + "，当前: " + externalStorage.dimension());
                }
                storage = externalStorage;
            } else {
                Path dir = storageDir != null ? storageDir : defaultDirectory();
                storage = FileVectorStorage.create(dim, dir);
            }
            filters = buildMelFilters();
            started = true;
            log.info("[Voiceprint] ready: dim={} sr={} frame={}/{} mels={} storage={}",
                    dim, sampleRate, frameLength, frameShift, melBands,
                    externalStorage != null ? "external" : "file");
        }
        return storage;
    }

    /** 校验参数合法性 */
    private void validateParams() {
        if (frameLength <= 0 || frameShift <= 0 || frameShift > frameLength) {
            throw new IllegalArgumentException(
                    "帧长/帧移非法: frameLength=" + frameLength + ", frameShift=" + frameShift);
        }
        if (melBands <= 0) {
            throw new IllegalArgumentException("melBands 必须为正: " + melBands);
        }
    }

    /** 使用期禁止修改核心参数 */
    private void checkNotStarted(String param) {
        if (started) {
            throw new IllegalStateException(
                    "参数 " + param + " 必须在首次 enroll/search 前设置");
        }
    }

    /**
     * 声纹库默认持久化目录。
     *
     * <p>优先系统属性 {@code deeplearning.model.cache-dir}，回落 {@code %TEMP%}。</p>
     *
     * @return 目录路径
     */
    private static Path defaultDirectory() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        Path base = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim()) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("chua-voiceprints");
    }

    /**
     * 内部特征提取：PCM 采样 → 声纹向量。
     *
     * @param samples 单声道 [-1,1] 采样
     * @return L2 归一化声纹向量
     */
    private float[] extract(float[] samples) {
        if (samples.length < frameLength) {
            throw new IllegalArgumentException("音频太短: " + samples.length + " 采样");
        }
        int frames = (samples.length - frameLength) / frameShift + 1;

        // 预计算 DFT 旋转表
        double[] cosTab = new double[frameLength];
        double[] sinTab = new double[frameLength];
        for (int j = 0; j < frameLength; j++) {
            cosTab[j] = Math.cos(-2.0 * Math.PI * j / frameLength);
            sinTab[j] = Math.sin(-2.0 * Math.PI * j / frameLength);
        }

        // 逐帧功率谱 + mel + log10
        double[][] melLog = new double[frames][melBands];
        for (int f = 0; f < frames; f++) {
            int off = f * frameShift;
            for (int m = 0; m < melBands; m++) {
                double energy = 0;
                int lo = (int) filters[m][0];
                int hi = (int) filters[m][1];
                for (int k = lo; k <= hi; k++) {
                    double re = 0;
                    double im = 0;
                    int idx = k;
                    for (int j = 0; j < frameLength; j++) {
                        double p = samples[off + j];
                        re += p * cosTab[idx];
                        im += p * sinTab[idx];
                        idx += k;
                        if (idx >= frameLength) {
                            idx -= frameLength;
                        }
                    }
                    double power = re * re + im * im;
                    energy += power * filters[m][2 + k - lo];
                }
                melLog[f][m] = Math.log10(Math.max(energy, 1e-10));
            }
        }

        // 时序聚合：逐 bin 均值 + 标准差
        int dim = melBands * 2;
        float[] fp = new float[dim];
        for (int m = 0; m < melBands; m++) {
            double sum = 0;
            double sq = 0;
            for (int f = 0; f < frames; f++) {
                sum += melLog[f][m];
                sq += melLog[f][m] * melLog[f][m];
            }
            double mean = sum / frames;
            double var = Math.max(0, sq / frames - mean * mean);
            fp[m] = (float) mean;
            fp[melBands + m] = (float) Math.sqrt(var);
        }
        AudioUtils.l2Normalize(fp);
        return fp;
    }

    /**
     * 构建 mel 三角滤波器组（HTK mel 刻度）。
     *
     * <p>每行格式：[loBin, hiBin, weight@loBin ... weight@hiBin]，
     * 三角形在中心 bin 权重为 1。</p>
     *
     * @return 滤波器组
     */
    private double[][] buildMelFilters() {
        int nBins = frameLength / 2 + 1;
        double[][] result = new double[melBands][];
        for (int m = 0; m < melBands; m++) {
            double fLo = melToHz(melBand(m));
            double fHi = melToHz(melBand(m + 2));
            int lo = Math.max(0, (int) Math.floor((frameLength + 1) * fLo / sampleRate));
            int hi = Math.min(nBins - 1, (int) Math.ceil((frameLength + 1) * fHi / sampleRate));
            if (hi <= lo) {
                hi = Math.min(nBins - 1, lo + 1);
            }
            double[] row = new double[hi - lo + 3];
            row[0] = lo;
            row[1] = hi;
            double fCenter = melToHz(melBand(m + 1));
            int center = (int) Math.round((frameLength + 1) * fCenter / sampleRate);
            for (int k = lo; k <= hi; k++) {
                double w;
                if (k <= center && center > lo) {
                    w = (double) (k - lo) / (center - lo);
                } else if (hi > center) {
                    w = (double) (hi - k) / (hi - center);
                } else {
                    w = 1.0;
                }
                row[2 + k - lo] = Math.max(0, w);
            }
            result[m] = row;
        }
        return result;
    }

    /**
     * 第 index 个 mel 频带的 mel 刻度值。
     *
     * @param index 序号（0..N+1）
     * @return mel 值
     */
    private double melBand(int index) {
        return hzToMel(0.0)
                + (hzToMel(sampleRate / 2.0) - hzToMel(0.0)) * index / (melBands + 1);
    }

    /**
     * Hz → HTK mel。
     *
     * @param hz 频率
     * @return mel 值
     */
    private static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    /**
     * mel → Hz。
     *
     * @param mel mel 值
     * @return 频率
     */
    private static double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }
}
