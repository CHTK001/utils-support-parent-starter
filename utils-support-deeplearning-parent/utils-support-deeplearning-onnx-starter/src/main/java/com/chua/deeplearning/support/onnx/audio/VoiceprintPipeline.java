package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.audio.FileVectorStorage;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/**
 * 声纹识别管线（CAM++ 神经模型，192 维嵌入）。
 *
 * <p>与 {@code FacePipeline}/{@code ImageSearcher} 同构的 Builder API：</p>
 *
 * <pre>{@code
 * VoiceprintPipeline vp = VoiceprintPipeline.builder()
 *         .vectorStorage("milvus", Map.of(          // 向量库（可选，默认文件落盘）
 *                 "host", "127.0.0.1", "port", 19530,
 *                 "collection", "voiceprint"))
 *         .topK(3)
 *         .threshold(0.80)
 *         .build();
 *
 * vp.enroll("alice", Path.of("alice.wav"))
 *   .enroll("bob", Path.of("bob.wav"));
 * List<Match> hits = vp.search(Path.of("query.wav"));
 * }</pre>
 *
 * <p><b>模型零配置</b>：声纹模型 CAM++（26MB）内嵌于
 * utils-support-models-onnx-sensevoice jar，首次调用自动解压到缓存目录；
 * vectorStorage 仅决定<b>向量存储后端</b>。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VoiceprintPipeline implements AutoCloseable {

    /**
     * 向量入库设施。
     */
    private final VectorStorage storage;

    /**
     * CAM++ 神经声纹提取器
     */
    private final CampplusEmbedding campplus;
    /**
     * 检索条数
     */
    private final int topK;

    /**
     * 相似度门槛；&lt;=0 表示不过滤
     */
    private final double threshold;

    /**
     * 默认构造：文件持久化向量库。
     */
    public VoiceprintPipeline() {
        this(FileVectorStorage.create(192, defaultDirectory()), null, 5, 0);
    }

    /**
     * 指定向量库构造（维度须为 192）。
     *
     * @param storage 向量存储
     */
    public VoiceprintPipeline(VectorStorage storage) {
        this(storage, null, 5, 0);
    }

    private VoiceprintPipeline(VectorStorage storage, CampplusEmbedding embedder,
                               int topK, double threshold) {
        this.storage = Objects.requireNonNull(storage, "vectorStorage");
        this.campplus = embedder != null ? embedder : CampplusEmbedding.load();
        this.topK = Math.max(1, topK);
        this.threshold = threshold;
        log.info("[Voiceprint] init: CAM++ neural backend, dim=192, topK={}, threshold={}",
                topK, threshold);
    }

    /**
     * 创建 Builder（与 FacePipeline/ImageSearcher 风格一致）。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建默认管线实例（文件持久化）。
     *
     * @return 管线实例
     */
    public static VoiceprintPipeline create() {
        return builder().build();
    }

    /**
     * 入库：注册说话人声纹（重复 id 覆盖旧声纹）。
     *
     * @param speakerId 说话人标识
     * @param samplePath 参考音频
     * @return this
     * @throws Exception 提取或落库失败
     */
    public VoiceprintPipeline enroll(String speakerId, Path samplePath) throws Exception {
        return enroll(speakerId, extract(samplePath));
    }

    /**
     * 提取声纹特征（低阶入口，与 FacePipeline.extractFeature 对位）。
     *
     * @param samplePath 音频路径
     * @return 192 维特征
     * @throws Exception 提取失败
     */
    public float[] extract(Path samplePath) throws Exception {
        float[] samples = AudioUtils.loadMono16k(samplePath);
        return campplus.extract(samples);
    }

    /**
     * 入库：直接注册特征向量（与 FacePipeline.enroll(id, feature, ...) 对位）。
     *
     * @param speakerId 说话人标识
     * @param feature 192 维特征
     * @return 是否成功
     */
    public boolean enroll(String speakerId, float[] feature) {
        boolean ok = storage.add(speakerId, feature);
        if (ok) {
            log.info("[Voiceprint] enrolled {}: dim={}", speakerId, feature.length);
        }
        return ok;
    }

    /**
     * 检索：使用构建时配置的 topK 与 threshold。
     *
     * @param samplePath 待测音频
     * @return 按相似度降序的匹配列表
     * @throws Exception 提取或检索失败
     */
    public List<Match> search(Path samplePath) throws Exception {
        return search(samplePath, topK);
    }

    /**
     * 检索：显式指定条数（threshold 仍生效）。
     *
     * @param samplePath 待测音频
     * @param k 返回条数
     * @return 按相似度降序的匹配列表
     * @throws Exception 提取或检索失败
     */
    public List<Match> search(Path samplePath, int k) throws Exception {
        float[] samples = AudioUtils.loadMono16k(samplePath);
        float[] probe = campplus.extract(samples);
        List<Vector> hits = storage.search(probe, k);
        List<Match> matches = new ArrayList<>(hits.size());
        for (Vector v : hits) {
            double sim = AudioUtils.cosine(probe, v.data());
            if (threshold > 0 && sim < threshold) {
                continue;
            }
            matches.add(new Match(v.id(), sim));
        }
        return matches;
    }

    /**
     * 已注册声纹数量。
     *
     * @return 数量
     */
    public int size() {
        return storage.size();
    }

    /**
     * 释放底层向量库连接（Milvus 等外部存储需要）。
     */
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
     * @param speakerId 说话人标识
     * @param similarity 余弦相似度 [-1,1]
     */
    public record Match(String speakerId, double similarity) {
    }

    /**
     * 管线构建器：配置提取器、向量库、TopK、相似度门槛后 build()。
     */
    public static final class Builder {

        private CampplusEmbedding embedder;
        private VectorStorage vectorStorage;
        private Path storageDir;
        private int topK = 5;
        private double threshold;

        /**
         * 设置声纹特征提取器（默认自动加载内嵌 CAM++）。
         *
         * @param e 提取器
         * @return this
         */
        public Builder embedder(CampplusEmbedding e) {
            this.embedder = e;
            return this;
        }

        private VectorStorage vectorStorage;
        private Path storageDir;
        private int topK = 5;
        private double threshold;

        /**
         * 注入自定义向量存储实例。
         *
         * @param s 向量存储
         * @return this
         */
        public Builder vectorStorage(VectorStorage s) {
            this.vectorStorage = s;
            return this;
        }

        /**
         * 按 SPI 提供方名称创建向量存储（memory/jvector/milvus...）。
         *
         * <pre>{@code
         * builder.vectorStorage("memory", null);
         * builder.vectorStorage("milvus", Map.of(
         *         "host", "127.0.0.1", "port", 19530,
         *         "collection", "voiceprint"));
         * }</pre>
         *
         * <p>MILVUS 必须提供 host/port/collection，缺失立即抛出。</p>
         *
         * @param provider 存储类型
         * @param config   配置 Map（键 host/port/token/collection），可 null
         * @return this
         */
        public Builder vectorStorage(String provider, Object config) {
            String host = null;
            Integer port = null;
            String token = null;
            String collection = null;
            if (config != null) {
                if (!(config instanceof java.util.Map<?, ?> map)) {
                    throw new IllegalArgumentException(
                            "config 需为 Map<String,Object>: "
                                    + config.getClass().getName());
                }
                host = trimOrNull(map.get("host"));
                port = com.chua.common.support.utils.Converter
                        .convertIfNecessary(map.get("port"), Integer.class, null);
                token = trimOrNull(map.get("token"));
                collection = trimOrNull(map.get("collection"));
            }
            if ("MILVUS".equalsIgnoreCase(provider)) {
                java.util.List<String> missing = new ArrayList<>();
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
                    throw new IllegalArgumentException(
                            provider + " 缺少必填配置: " + missing);
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

        /**
         * 文件持久化目录（未注入 vectorStorage 时生效）。
         *
         * @param dir 目录
         * @return this
         */
        public Builder storageDir(Path dir) {
            this.storageDir = dir;
            return this;
        }

        /**
         * 检索返回条数。
         *
         * @param k TopK
         * @return this
         */
        public Builder topK(int k) {
            this.topK = Math.max(1, k);
            return this;
        }

        /**
         * 相似度门槛：低于过滤；&lt;=0 不过滤。
         *
         * @param t 阈值
         * @return this
         */
        public Builder threshold(double t) {
            this.threshold = t;
            return this;
        }

        /**
         * 构建管线。
         *
         * @return 管线实例
         */
        public VoiceprintPipeline build() {
            VectorStorage s = vectorStorage;
            if (s == null) {
                s = FileVectorStorage.create(192,
                        storageDir != null ? storageDir : defaultDirectory());
            }
            return new VoiceprintPipeline(s, embedder, topK, threshold);
        }

        /**
         * 向量库访问器。
         *
         * @return 已配置向量库（build 前可为 null）
         */
        public VectorStorage vectorStorage() {
            return vectorStorage;
        }
    }

    /**
     * 对象转去除首尾空白的字符串。
     *
     * @param v 原值
     * @return 字符串；null/空白返回 null
     */
    private static String trimOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 声纹库默认持久化目录。
     *
     * @return 目录路径
     */
    private static Path defaultDirectory() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        Path base = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim()) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("chua-voiceprints");
    }
}
