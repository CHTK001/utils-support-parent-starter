package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.audio.FileVectorStorage;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 声纹识别管线（CAM++ 神经模型，192 维嵌入）。
 *
 * <p>与 {@code FacePipeline}/{@code ImageSearcher} 同构的链式 API：</p>
 *
 * <pre>{@code
 * // 构建管线
 * VoiceprintPipeline vp = VoiceprintPipeline.builder()
 *         .embedder(CampplusEmbedding.load())
 *         .build();
 *
 * // 链式入库（带 id + 标签）
 * vp.id("alice").label("女声").enroll(Path.of("alice.wav"));
 * vp.id("bob").label("男声").enroll(Path.of("bob.wav"));
 *
 * // 链式检索（设置参数后 search）
 * List<Match> hits = vp.topK(5).threshold(0.80).search(queryWav);
 * }</pre>
 *
 * <p><b>模型零配置</b>：CAM++（26MB）内嵌于 sensevoice jar，首次调用自动解压。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VoiceprintPipeline implements AutoCloseable {

    /**
     * 向量存储后端（192 维，cosine）。
     */
    private final VectorStorage storage;

    /**
     * CAM++ 神经声纹特征提取器。
     */
    private final CampplusEmbedding campplus;

    /** ---- 链式检索参数 ---- */
    private int searchTopK = 5;
    private double searchThreshold;

    /** ---- 链式入库参数 ---- */
    private String currentId;
    private String currentLabel;

    /** 默认构造：文件持久化向量库 + 内嵌 CAM++。 */
    public VoiceprintPipeline() {
        this(FileVectorStorage.create(192, defaultDirectory()), null);
    }

    /** 指定向量库构造（维度须为 192）。 */
    public VoiceprintPipeline(VectorStorage storage) {
        this(storage, null);
    }

    private VoiceprintPipeline(VectorStorage storage, CampplusEmbedding embedder) {
        this.storage = Objects.requireNonNull(storage, "vectorStorage");
        this.campplus = embedder != null ? embedder : CampplusEmbedding.load();
        log.info("[Voiceprint] init: CAM++ neural, dim=192, storage={}",
                storage.getClass().getSimpleName());
    }

    /** 创建 Builder。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建默认管线实例（文件持久化）。 */
    public static VoiceprintPipeline create() {
        return builder().build();
    }

    // ==================== 链式检索配置 ====================

    /** 设置检索返回条数（链式调用）。 */
    public VoiceprintPipeline topK(int k) {
        this.searchTopK = Math.max(1, k);
        return this;
    }

    /** 设置相似度门槛：低于过滤，&lt;=0 不过滤（链式调用）。 */
    public VoiceprintPipeline threshold(double t) {
        this.searchThreshold = t;
        return this;
    }

    /** 设置当前入库说话人 ID（链式调用）。 */
    public VoiceprintPipeline id(String speakerId) {
        this.currentId = speakerId;
        return this;
    }

    /** 设置当前入库标签/备注（链式调用）。 */
    public VoiceprintPipeline label(String label) {
        this.currentLabel = label;
        return this;
    }

    // ==================== 核心 API ====================

    /**
     * 提取声纹特征。
     *
     * @param samplePath 音频路径
     * @return 192 维特征
     * @throws Exception 提取失败
     */
    public float[] extract(Path samplePath) throws Exception {
        return campplus.extract(AudioUtils.loadMono16k(samplePath));
    }

    /**
     * 入库：用当前 id/label 注册说话人声纹。
     *
     * @param samplePath 参考音频
     * @return this
     * @throws Exception 提取或落库失败
     */
    public VoiceprintPipeline enroll(Path samplePath) throws Exception {
        if (currentId == null) {
            throw new IllegalStateException("未设置说话人 id，请先调用 id()");
        }
        float[] feature = extract(samplePath);
        if (!storage.add(currentId, feature)) {
            throw new IllegalStateException("声纹入库失败: " + currentId);
        }
        log.info("[Voiceprint] enrolled {} (label={}): dim={}", currentId, currentLabel, feature.length);
        return this;
    }

    /**
     * 检索：用当前 topK / threshold 查找最近邻。
     *
     * @param samplePath 待测音频
     * @return 按相似度降序的匹配列表
     * @throws Exception 提取或检索失败
     */
    public List<Match> search(Path samplePath) throws Exception {
        float[] probe = extract(samplePath);
        List<Vector> hits = storage.search(probe, searchTopK);
        List<Match> matches = new ArrayList<>(hits.size());
        for (Vector v : hits) {
            double sim = AudioUtils.cosine(probe, v.data());
            if (searchThreshold > 0 && sim < searchThreshold) {
                continue;
            }
            matches.add(new Match(v.id(), sim));
        }
        return matches;
    }

    /** 已注册声纹数量。 */
    public int size() {
        return storage.size();
    }

    /** 释放底层向量库连接。 */
    @Override
    public void close() {
        try {
            storage.close();
        } catch (Exception e) {
            log.warn("[Voiceprint] close storage failed: {}", e.getMessage());
        }
    }

    /**
     * 匹配结果。
     *
     * @param speakerId  说话人标识
     * @param similarity 余弦相似度 [-1,1]
     */
    public record Match(String speakerId, double similarity) {
    }

    /** 声纹库默认持久化目录。 */
    private static Path defaultDirectory() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        Path base = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim()) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("chua-voiceprints");
    }

    /**
     * 管线构建器。
     */
    public static final class Builder {

        /** 声纹特征提取器（默认自动加载内嵌 CAM++）。 */
        private CampplusEmbedding embedder;

        /** 向量存储后端（默认 FileVectorStorage）。 */
        private VectorStorage vectorStorage;

        /** 持久化目录（未注入 vectorStorage 时生效）。 */
        private Path storageDir;

        /** 设置声纹特征提取器（默认自动加载内嵌 CAM++）。 */
        public Builder embedder(CampplusEmbedding e) {
            this.embedder = e;
            return this;
        }

        /** 注入向量存储实例。 */
        public Builder vectorStorage(VectorStorage s) {
            this.vectorStorage = s;
            return this;
        }

        /** 按 SPI 名称创建向量存储（memory/jvector/milvus...）。 */
        public Builder vectorStorage(String provider, Object config) {
            String host = null;
            Integer port = null;
            String token = null;
            String collection = null;
            if (config != null) {
                if (!(config instanceof java.util.Map<?, ?> map)) {
                    throw new IllegalArgumentException(
                            "config 需为 Map<String,Object>: " + config.getClass().getName());
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
            var b = com.chua.common.support.vector.VectorStorageBuilder
                    .newBuilder()
                    .type(provider)
                    .dimension(192)
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

        /** 文件持久化目录（未注入 vectorStorage 时生效）。 */
        public Builder storageDir(Path dir) {
            this.storageDir = dir;
            return this;
        }

        /** 构建管线。 */
        public VoiceprintPipeline build() {
            VectorStorage s = vectorStorage;
            if (s == null) {
                s = FileVectorStorage.create(192,
                        storageDir != null ? storageDir : defaultDirectory());
            }
            return new VoiceprintPipeline(s, embedder);
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
