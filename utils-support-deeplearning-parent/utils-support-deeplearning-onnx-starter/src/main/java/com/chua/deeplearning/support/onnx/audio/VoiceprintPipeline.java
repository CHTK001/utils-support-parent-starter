package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.DefaultVectorStorage;
import com.chua.deeplearning.support.speech.SpeechEnhancer;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
* 声纹识别管线（CAM++ 神经模型，192 维嵌入）。
*
* <p>创建路径与 FacePipeline 同构（统一 String modelId provider 模式）：</p>
* <pre>{@code
* // 构建管线
* VoiceprintPipeline vp = VoiceprintPipeline.builder()
*         .embedder(CampplusEmbedding.load())
*         .max(100)                  // 检索结果上限（与 topK 取 min）
*         .denoise("dfsmn-ans")      // ○ 按模型 ID 降噪（SpeechEnhancer）
*         .vad("energy")             // ○ 按类型 VAD 切分（energy/silero...）
*         .build();
*
* // 链式入库
* vp.createEnroll()
*   .id("alice")
*   .label("女声")
*   .audio(aliceWav)
*   .execute();
*
* // 链式检索
* List<Match> hits = vp.createSearch()
*   .topK(5)
*   .threshold(0.80)
*   .query(queryWav)
*   .execute();
* }</pre>   .topK(5)
*   .threshold(0.80)
*   .query(queryWav)
*   .execute();
* }</pre>
*
* <p><b>模型零配置</b>：CAM++（26MB）内嵌于 sensevoice jar，首次调用自动解压。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class VoiceprintPipeline implements AutoCloseable {

    /** 向量存储后端（192 维，cosine）。 */
    private final VectorStorage storage;

    /** CAM++ 神经声纹特征提取器（传统方式）。 */
    private final CampplusEmbedding campplus;

    /** 声纹嵌入翻译器（模型registry 方式）。 */
    private final com.chua.deeplearning.support.translator.ITranslator<byte[], float[]> embedderTranslator;

    /** 检索结果上限（与 搜索 的 topk 取 最小）。 */
    private final int maxResults;

    /** 降噪增强器（空 表示不降噪）。 */
    private final SpeechEnhancer denoiseEnhancer;

    /** VAD 类型（空 表示不做 VAD）。 */
    private final String vadType;

    private VoiceprintPipeline(VectorStorage storage, CampplusEmbedding embedder, int maxResults,
                               SpeechEnhancer denoiseEnhancer, String vadType) {
        this.storage = Objects.requireNonNull(storage, "vectorStorage");
        this.campplus = embedder != null ? embedder : CampplusEmbedding.load();
        this.embedderTranslator = null;
        this.maxResults = Math.max(1, maxResults);
        this.denoiseEnhancer = denoiseEnhancer;
        this.vadType = vadType;
        log.info("[Voiceprint] init: CAM++ neural, dim=192, maxResults={}, storage={}, denoise={}, vad={}",
                maxResults, storage.getClass().getSimpleName(),
                denoiseEnhancer != null ? denoiseEnhancer.getClass().getSimpleName() : "off",
                vadType != null ? vadType : "off");
    }

    private VoiceprintPipeline(VectorStorage storage,
                               com.chua.deeplearning.support.translator.ITranslator<byte[], float[]> translator,
                               int maxResults, SpeechEnhancer denoiseEnhancer, String vadType) {
        this.storage = Objects.requireNonNull(storage, "vectorStorage");
        this.campplus = null;
        this.embedderTranslator = translator;
        this.maxResults = Math.max(1, maxResults);
        this.denoiseEnhancer = denoiseEnhancer;
        this.vadType = vadType;
        log.info("[Voiceprint] init: translator={}, dim=192, maxResults={}, storage={}, denoise={}, vad={}",
                translator.name(), maxResults, storage.getClass().getSimpleName(),
                denoiseEnhancer != null ? denoiseEnhancer.getClass().getSimpleName() : "off",
                vadType != null ? vadType : "off");
    }

    /**
    * 创建 构建器。
    *
    * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
    * 创建默认管线实例（文件持久化）。
    *
    * @return 创建的结果
     */
    public static VoiceprintPipeline create() {
        return builder().build();
    }

    /**
    * 创建链式入库操作。
    *
    * @return 创建enroll的结果
     */
    public EnrollConfig createEnroll() {
        return new EnrollConfig(this);
    }

    /**
    * 创建链式检索操作。
    *
    * @return 创建搜索的结果
     */
    public SearchConfig createSearch() {
        return new SearchConfig(this);
    }

    /** 提取声纹特征（可选降噪 + VAD 预处理）。 */
    float[] extract(Path samplePath) throws Exception {
        float[] samples = AudioUtils.loadMono16k(samplePath);
        if (denoiseEnhancer != null) {
            samples = denoise(samples);
        }
        if (vadType != null) {
            List<float[]> segments = splitByVad(samples, vadType);
            if (!segments.isEmpty()) {
                float[] longest = segments.get(0);
                for (float[] seg : segments) {
                    if (seg.length > longest.length) {
                        longest = seg;
                    }
                }
                samples = longest;
            }
        }
        if (embedderTranslator != null) {
 // 模型registry 方式：将 float[] 转为 WAV 字节后传给翻译器
            byte[] wavBytes = AudioUtils.toWavBytes(samples, 16000);
            return embedderTranslator.translate(wavBytes);
        }
        return campplus.extract(samples);
    }

    /**
    * 降噪预处理（委托给 语音enhancer）。
    *
    * @param s 16khz 采样
    * @return 增强后采样
     */
    private float[] denoise(float[] s) {
        try {
            float[] up = AudioUtils.resample(s, 16000, 48000);
            byte[] wavIn = AudioUtils.toWavBytes(up, 48000);
            byte[] wavOut = denoiseEnhancer.enhance(wavIn);
            float[] enhanced48k = com.chua.deeplearning.support.onnx.audio.denoise.WavDecoder
                    .decodeToFloat(wavOut, 48000);
            float[] down = AudioUtils.resample(enhanced48k, 48000, 16000);
            log.info("[Voiceprint] 降噪完成: {} → {} 采样", s.length, down.length);
            return down;
        } catch (Exception e) {
            log.warn("[Voiceprint] 降噪失败，回退原始音频: {}", e.getMessage());
            return s;
        }
    }

    /**
    * 按类型创建 VAD 切分。
    *
    * @param s    16khz 采样
    * @param type VAD 类型（"energy" / "silero" 等）
    * @return 语音段列表
     */
    private static List<float[]> splitByVad(float[] s, String type) {
        return switch (type.toLowerCase()) {
            case "energy" -> splitByEnergy(s, 0.01f, 0.4f, 28f);
            default -> List.of(s);
        };
    }

    /**
    * 能量 VAD 切分（通用实现，可复用于 asrpipeline）。
    *
    * @param s           16khz 单声道采样
    * @param silenceRms  静音 RMS 门限
    * @param minSegSec   最短语音段秒数
    * @param maxSegSec   最大段长秒数
    * @return 语音段列表
    * @param off off
    * @param len len
    */
    static List<float[]> splitByEnergy(float[] s, float silenceRms, float minSegSec, float maxSegSec) {
        int frame = (int) (0.03F * 16000);
        int minSeg = (int) (minSegSec * 16000);
        int maxSeg = (int) (maxSegSec * 16000);
        int mergeGap = (int) (0.6F * 16000);
        List<int[]> voiced = new ArrayList<>();
        int start = -1;
        for (int i = 0; i + frame <= s.length; i += frame) {
            boolean loud = rms(s, i, frame) >= silenceRms;
            if (loud && start < 0) {
                start = i;
            } else if (!loud && start >= 0 && i - start >= minSeg) {
                voiced.add(new int[]{start, i});
                start = -1;
            } else if (!loud) {
                start = -1;
            }
        }
        if (start >= 0) {
            voiced.add(new int[]{start, Math.min(s.length, start + maxSeg)});
        }
        List<int[]> merged = new ArrayList<>();
        for (int[] r : voiced) {
            if (!merged.isEmpty() && r[0] - merged.get(merged.size() - 1)[1] < mergeGap) {
                merged.get(merged.size() - 1)[1] = r[1];
            } else {
                merged.add(r);
            }
        }
        if (merged.isEmpty()) {
            return List.of(s);
        }
        List<float[]> out = new ArrayList<>(merged.size());
        for (int[] r : merged) {
            float[] seg = new float[Math.min(r[1], s.length) - r[0]];
            System.arraycopy(s, r[0], seg, 0, seg.length);
            out.add(seg);
        }
        return out;
    }

    private static float rms(float[] s, int off, int len) {
        double sum = 0;
        for (int i = off; i < off + len; i++) {
            sum += s[i] * s[i];
        }
        return (float) Math.sqrt(sum / len);
    }

    /** 向量存储。 */
    VectorStorage storage() {
        return storage;
    }

    /** 检索上限。 */
    int maxResults() {
        return maxResults;
    }

    /**
    * 已注册声纹数量。
    *
    * @return 大小的结果
     */
    public int size() {
        return storage.size();
    }

    @Override
    public void close() {
        try {
            storage.close();
        } catch (Exception e) {
            log.warn("[Voiceprint] close storage failed: {}", e.getMessage());
        }
    }

    // ==================== 入库链 ====================

    /**
    * 入库配置器：标识 → 标签 → 音频 → 执行。
    *
    * @param audioPath 音频路径
    * @return 音频的结果
    * @param label 标签
     */
    public static final class EnrollConfig {
        private final VoiceprintPipeline parent;
        private String id;
        private String label;
        /**
        * 标识。
        * @param speakerId speakerid
        * @return id的结果
        * @param audioPath 音频路径
        * @param label 标签
         */
        private Path audioPath;

        EnrollConfig(VoiceprintPipeline parent) {
            this.parent = parent;
        /**
        * id。
        * @param speakerId speakerId
        * @return id的结果
         */
        }

        public EnrollConfig id(String speakerId) {
            this.id = speakerId;
            return this;
        }

        public EnrollConfig label(String label) {
            this.label = label;
            return this;
        }

        public EnrollConfig audio(Path audioPath) {
            this.audioPath = audioPath;
            return this;
        }

        /**
        * 执行入库并返回管线实例（可继续链式）。
        *
        * @return 执行的结果
         */
        public VoiceprintPipeline execute() throws Exception {
            if (id == null || audioPath == null) {
                throw new IllegalStateException("id 和 audio 均为必填");
            }
            float[] feature = parent.extract(audioPath);
            Map<String, Object> metadata = new HashMap<>();
            if (label != null) {
                metadata.put("label", label);
            }
            parent.storage.add(new Vector(id, feature, metadata));
            log.info("[Voiceprint] enrolled {} (label={}): dim={}", id, label, feature.length);
            return parent;
        }
    }

    // ==================== 检索链 ====================

    /**
    * 检索配置器：topk → 阈值 → 查询 → 执行。
    *
    * @param queryPath 查询路径
    * @return 查询的结果
    * @param t t
     */
    public static final class SearchConfig {
        private final VoiceprintPipeline parent;
        private int topK = 5;
        private double threshold;
        /**
        * topk。
        * @param k k
        * @return topK的结果
        * @param queryPath 查询路径
        * @param t t
         */
        private Path queryPath;

        SearchConfig(VoiceprintPipeline parent) {
            this.parent = parent;
        /**
        * topK。
        * @param k k
        * @return topK的结果
         */
        }

        public SearchConfig topK(int k) {
            this.topK = Math.max(1, k);
            return this;
        }

        public SearchConfig threshold(double t) {
            this.threshold = t;
            return this;
        }

        public SearchConfig query(Path queryPath) {
            this.queryPath = queryPath;
            return this;
        }

        /**
        * 执行检索，返回匹配结果。
        *
        * @return 执行的结果
         */
        public List<Match> execute() throws Exception {
            if (queryPath == null) {
                throw new IllegalStateException("query 为必填");
            }
            int effectiveK = Math.min(topK, parent.maxResults);
            float[] probe = parent.extract(queryPath);
            List<Vector> hits = parent.storage().search(probe, effectiveK);
            List<Match> matches = new ArrayList<>(hits.size());
            for (Vector v : hits) {
                double sim = AudioUtils.cosine(probe, v.data());
                if (threshold > 0 && sim < threshold) {
                    continue;
                }
                matches.add(new Match(v.id(), sim, v.metadata()));
            }
            return matches;
        }
    }

    /**
    * 匹配结果。
    *
    * @param speakerId speakerid
    * @param similarity 相似度
    * @param metadata metadata
    * @return 匹配的结果
     */
    public record Match(String speakerId, double similarity, Map<String, Object> metadata) {
    }

    /**
    * 声纹库默认持久化目录。
    *
    * @return 默认目录的结果
     */
    private static Path defaultDirectory() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        Path base = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim()) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("chua-voiceprints");
    }

    /** 管线构建器。 */
    public static final class Builder {
        private CampplusEmbedding embedder;
        private com.chua.deeplearning.support.translator.ITranslator<byte[], float[]> translator;
        private VectorStorage vectorStorage;
        private Path storageDir;
        private int maxResults = 100;
        private SpeechEnhancer denoiseEnhancer;
        private String vadType;

        /**
        * embedder。
        * @param e e
        * @return embedder的结果
         */
        public Builder embedder(CampplusEmbedding e) {
            this.embedder = e;
            return this;
        }

        /**
        * 设置声纹嵌入模型 标识（从 模型registry 加载）。
        *
        * <p>统一 provider 模式（与 FacePipeline 一致），按模型 ID 从 ModelRegistry 解析：
        * <pre>{@code
        * .model("campplus-voiceprint")   // CAM++ 192维声纹
        * .model("wespeaker-resnet34")    // Wespeaker 512维说话人嵌入
        * }</pre>pre>
        *
        * @param modelId 模型 标识（对应 音频fingerprinter 注册表）
        * @return this
        * @param dir dir
        * @param provider 提供者
        * @param config 配置
        */
        public Builder model(String modelId) {
            if (modelId == null || modelId.isBlank()) {
                return this;
            }
            this.translator = com.chua.deeplearning.support.engine.AbstractIdentificationEngine.getInstance()
                    .get(modelId, com.chua.deeplearning.support.translator.ITranslator.class);
            if (this.translator == null) {
                throw new IllegalArgumentException("声纹模型未注册: " + modelId);
            }
            log.info("[Voiceprint] 使用 ModelRegistry 加载模型: {}", modelId);
            return this;
        }

        public Builder vectorStorage(VectorStorage s) {
            this.vectorStorage = s;
            return this;
        }

        public Builder vectorStorage(String provider, Object config) {
            String host = null;
            Integer port = null;
            String token = null;
            String collection = null;
            if (config != null) {
                if (!(config instanceof java.util.Map<?, ?> map)) {
                    throw new IllegalArgumentException("config 需为 Map<String,Object>");
                }
                host = trimOrNull(map.get("host"));
                Object portVal = map.get("port");
                if (portVal != null) {
                    port = Integer.parseInt(portVal.toString());
                }
                token = trimOrNull(map.get("token"));
                collection = trimOrNull(map.get("collection"));
            }
            if ("MILVUS".equalsIgnoreCase(provider)) {
                List<String> missing = new ArrayList<>();
                if (host == null) {
                    missing.add("host");
                }
                if (port == null) {
                    missing.add("port");
                }
                if (collection == null) {
                    missing.add("collection");
                }
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException(provider + " 缺少必填配置: " + missing);
                }
            }
            var b = com.chua.common.support.vector.VectorStorageBuilder.newBuilder()
                    .type(provider).dimension(192)
                    .algorithm(com.chua.common.support.vector.VectorCompareAlgorithm.cosine());
            if (host != null) {
                b.host(host);
            }
            if (port != null) {
                b.port(port);
            }
            if (token != null) {
                b.token(token);
            }
            if (collection != null) {
                b.collection(collection);
            }
            this.vectorStorage = b.build();
            return this;
        }

        public Builder storageDir(Path dir) {
            this.storageDir = dir;
            return this;
        }

        /**
        * 检索结果上限（与 搜索 的 topk 取 最小）。
        *
        * @param maxResults 最大结果
        * @return 最大的结果
         */
        public Builder max(int maxResults) {
            this.maxResults = Math.max(1, maxResults);
            return this;
        }

        /**
        * 设置降噪模型 标识（空 表示不降噪）。
        *
        * <p>统一 provider 模式（与 FacePipeline 一致），按模型 ID 从 ModelRegistry 解析：
        * <pre>{@code
        * .denoise("dfsmn-ans")  // DFSMN 单麦近场降噪
        * }</pre>
        *
        * @param modelId 模型 标识（对应 {@link SpeechEnhancer} 注册表）
        * @return this
         */
        public Builder denoise(String modelId) {
            this.denoiseEnhancer = modelId != null ? SpeechEnhancer.create(modelId) : null;
            return this;
        }

        /**
        * 设置 VAD 类型（空 表示不做 VAD）。
        *
        * <p>统一 provider 模式（与 FacePipeline 一致），按类型字符串选择切分策略：
        * <pre>{@code
        * .vad("energy")   // 能量 VAD（默认参数）
        * }</pre>
        *
        * @param type VAD 类型（"energy" 等）
        * @return this
        * @param v v
        */
        public Builder vad(String type) {
            this.vadType = type;
            return this;
        }

        public VoiceprintPipeline build() {
            VectorStorage s = vectorStorage;
            if (s == null) {
                s = DefaultVectorStorage.create(192,
                        storageDir != null ? storageDir : defaultDirectory());
            }
            if (translator != null) {
                return new VoiceprintPipeline(s, translator, maxResults, denoiseEnhancer, vadType);
            }
            return new VoiceprintPipeline(s, embedder, maxResults, denoiseEnhancer, vadType);
        }
    }

    private static String trimOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
