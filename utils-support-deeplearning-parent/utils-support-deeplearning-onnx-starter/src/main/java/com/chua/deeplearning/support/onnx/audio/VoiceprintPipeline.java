package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.DefaultVectorStorage;
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
 * <p>创建路径与 FacePipeline 同构：</p>
 * <pre>{@code
 * // 构建管线
 * VoiceprintPipeline vp = VoiceprintPipeline.builder()
 *         .embedder(CampplusEmbedding.load())
 *         .max(100)             // 检索结果上限（与 topK 取 min）
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

    /** CAM++ 神经声纹特征提取器。 */
    private final CampplusEmbedding campplus;

    /** 检索结果上限（与 search 的 topK 取 min）。 */
    private final int maxResults;

    private VoiceprintPipeline(VectorStorage storage, CampplusEmbedding embedder, int maxResults) {
        this.storage = Objects.requireNonNull(storage, "vectorStorage");
        this.campplus = embedder != null ? embedder : CampplusEmbedding.load();
        this.maxResults = Math.max(1, maxResults);
        log.info("[Voiceprint] init: CAM++ neural, dim=192, maxResults={}, storage={}",
                maxResults, storage.getClass().getSimpleName());
    }

    /** 创建 Builder。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建默认管线实例（文件持久化）。 */
    public static VoiceprintPipeline create() {
        return builder().build();
    }

    /** 创建链式入库操作。 */
    public EnrollConfig createEnroll() {
        return new EnrollConfig(this);
    }

    /** 创建链式检索操作。 */
    public SearchConfig createSearch() {
        return new SearchConfig(this);
    }

    /** 提取声纹特征。 */
    float[] extract(Path samplePath) throws Exception {
        return campplus.extract(AudioUtils.loadMono16k(samplePath));
    }

    /** 向量存储。 */
    VectorStorage storage() {
        return storage;
    }

    /** 检索上限。 */
    int maxResults() {
        return maxResults;
    }

    /** 已注册声纹数量。 */
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

    /** 入库配置器：id → label → audio → execute。 */
    public static final class EnrollConfig {
        private final VoiceprintPipeline parent;
        private String id;
        private String label;
        private Path audioPath;

        EnrollConfig(VoiceprintPipeline parent) {
            this.parent = parent;
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

        /** 执行入库并返回管线实例（可继续链式）。 */
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

    /** 检索配置器：topK → threshold → query → execute。 */
    public static final class SearchConfig {
        private final VoiceprintPipeline parent;
        private int topK = 5;
        private double threshold;
        private Path queryPath;

        SearchConfig(VoiceprintPipeline parent) {
            this.parent = parent;
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

        /** 执行检索，返回匹配结果。 */
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

    /** 匹配结果。 */
    public record Match(String speakerId, double similarity, Map<String, Object> metadata) {
    }

    /** 声纹库默认持久化目录。 */
    private static Path defaultDirectory() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        Path base = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim()) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("chua-voiceprints");
    }

    /** 管线构建器。 */
    public static final class Builder {
        private CampplusEmbedding embedder;
        private VectorStorage vectorStorage;
        private Path storageDir;
        private int maxResults = 100;

        public Builder embedder(CampplusEmbedding e) {
            this.embedder = e;
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

        /** 检索结果上限（与 search 的 topK 取 min）。 */
        public Builder max(int maxResults) {
            this.maxResults = Math.max(1, maxResults);
            return this;
        }

        public VoiceprintPipeline build() {
            VectorStorage s = vectorStorage;
            if (s == null) {
                s = DefaultVectorStorage.create(192,
                        storageDir != null ? storageDir : defaultDirectory());
            }
            return new VoiceprintPipeline(s, embedder, maxResults);
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
