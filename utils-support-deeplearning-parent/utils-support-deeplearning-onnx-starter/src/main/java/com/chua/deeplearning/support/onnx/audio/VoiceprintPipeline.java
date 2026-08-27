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
 * <p>与 {@code FacePipeline}/{@code ImageSearcher} 同构的 Builder API：</p>
 *
 * <pre>{@code
 * VoiceprintPipeline vp = VoiceprintPipeline.builder()
 *         .embedder(CampplusEmbedding.load())                  // 内嵌自动解压
 *         .vectorStorage(VectorStorageBuilder.newBuilder()     // 或默认文件落盘
 *                 .dimension(192)
 *                 .algorithm(VectorCompareAlgorithm.cosine())
 *                 .build())
 *         .build();
 *
 * vp.enroll("alice", Path.of("alice.wav"))
 *   .enroll("bob", Path.of("bob.wav"));
 *
 * List<Match> hits = vp.search(query, 5, 0.80);  // topK/threshold 调用时指定
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

    /**
     * 默认构造：文件持久化向量库 + 内嵌 CAM++。
     */
    public VoiceprintPipeline() {
        this(FileVectorStorage.create(192, defaultDirectory()), null);
    }

    /**
     * 指定向量库构造（维度须为 192）。
     *
     * @param storage 向量存储
     */
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

    /**
     * 提取声纹特征（低阶入口，与 FacePipeline.extractFeature 对位）。
     *
     * @param samplePath 音频路径
     * @return 192 维特征
     * @throws Exception 提取失败
     */
    public float[] extract(Path samplePath) throws Exception {
        return campplus.extract(AudioUtils.loadMono16k(samplePath));
    }

    /**
     * 入库：注册说话人声纹（重复 id 覆盖旧声纹）。
     *
     * @param speakerId  说话人标识
     * @param samplePath 参考音频
     * @return this
     * @throws Exception 提取或落库失败
     */
    public VoiceprintPipeline enroll(String speakerId, Path samplePath) throws Exception {
        return enroll(speakerId, extract(samplePath));
    }

    /**
     * 入库：直接注册特征向量。
     *
     * @param speakerId 说话人标识
     * @param feature   192 维特征
     * @return this
     */
    public VoiceprintPipeline enroll(String speakerId, float[] feature) {
        if (!storage.add(speakerId, feature)) {
            throw new IllegalStateException("声纹入库失败: " + speakerId);
        }
        log.info("[Voiceprint] enrolled {}: dim={}", speakerId, feature.length);
        return this;
    }

    /**
     * 检索：topK 与 threshold 调用时指定（与 search(feature,k,threshold) 对位）。
     *
     * @param samplePath 待测音频
     * @param k          返回条数
     * @param threshold  相似度门槛，&lt;=0 不过滤
     * @return 按相似度降序的匹配列表
     * @throws Exception 提取或检索失败
     */
    public List<Match> search(Path samplePath, int k, double threshold) throws Exception {
        float[] probe = extract(samplePath);
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
     * 管线构建器：与 FacePipeline/ImageSearcher 同构。
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

        /**
         * 按 SPI 名称创建向量存储（memory/jvector/milvus...）。
         * <p>MILVUS 必须提供 host/port/collection。</p>
         */
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

        /** 获取已配置向量库。 */
        public VectorStorage vectorStorage() {
            return vectorStorage;
        }
    }

    /** 对象转去除首尾空白的字符串。 */
    private static String trimOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
