package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.audio.FileVectorStorage;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 声纹识别管线（CAM++ 神经模型，192 维嵌入）——全链式 API。
 *
 * <p>配置、入库、检索一气呵成：</p>
 *
 * <p><b>模型零配置</b>：声纹模型 CAM++（26MB）内嵌于
 * utils-support-models-onnx-sensevoice jar，首次调用自动解压到缓存目录，
 * 无需手动下载；{@code vectorDb} 仅决定<b>向量存储后端</b>。</p>
 *
 * <pre>{@code
 * List<VoiceprintPipeline.Match> hits = VoiceprintPipeline.create()
 *         .storageDir(Path.of("D:/voiceprints"))   // 可选：持久化目录
 *         .topK(3)                                  // 可选：返回条数，默认 5
 *         .threshold(0.80)                          // 可选：相似度门槛
 *         .enroll("alice", Path.of("alice.wav"))    // 入库（可连多个）
 *         .enroll("bob", Path.of("bob.wav"))
 *         .search(Path.of("query.wav"));            // 检索
 * }</pre>
 *
 * <p>旧签名保持兼容：{@link #search(Path, int)} 与
 * {@code enroll} 的原参数顺序重载仍可用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VoiceprintPipeline implements AutoCloseable {

    /** 默认 TopK。 */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 向量入库设施。
     */
    private VectorStorage storage;

    /**
     * CAM++ 神经声纹提取器
     */
    private final CampplusEmbedding campplus;

    /**
     * 持久化目录（链式配置项）
     */
    private Path storageDir;

    /**
     * 检索条数（链式配置项）
     */
    private int topK = DEFAULT_TOP_K;

    /**
     * 相似度门槛，低于该值的结果被过滤；&lt;=0 表示不过滤（链式配置项）
     */
    private double threshold;

    /**
     * 默认构造：文件持久化向量库 + CAM++ 神经声纹。
     */
    public VoiceprintPipeline() {
        this(defaultDirectory());
    }

    /**
     * 指定持久化目录构造。
     *
     * @param dir 声纹库目录
     */
    public VoiceprintPipeline(Path dir) {
        this.storageDir = dir;
        this.storage = FileVectorStorage.create(192, dir);
        this.campplus = CampplusEmbedding.load();
        log.info("[Voiceprint] init: CAM++ neural backend, dim=192, dir={}", dir);
    }

    /**
     * 指定向量库构造。
     *
     * @param storage 向量存储（维度须为 192）
     */
    public VoiceprintPipeline(VectorStorage storage) {
        this.storage = storage;
        this.campplus = CampplusEmbedding.load();
        log.info("[Voiceprint] init: CAM++ neural backend, dim=192, external storage");
    }

    /**
     * 创建声纹管线实例（链式起点）。
     *
     * @return 管线实例
     */
    public static VoiceprintPipeline create() {
        return new VoiceprintPipeline();
    }

    /**
     * 注入自定义向量存储（Milvus/JVector/Redis 等任意实现）。
     *
     * <p>须在首次入库/检索前调用；注入后 {@link #storageDir(Path)} 失效。</p>
     *
     * @param s 向量存储（维度须为 192）
     * @return this
     */
    public VoiceprintPipeline vectorDb(VectorStorage s) {
        if (s.dimension() != 192) {
            throw new IllegalArgumentException("向量库维度须为 192，当前: " + s.dimension());
        }
        if (storage != null && storage.size() > 0) {
            throw new IllegalStateException("已有数据，不允许再切换向量存储");
        }
        this.storage = s;
        return this;
    }

    /**
     * 按 SPI 提供方名称创建向量存储（memory/jvector/milvus...）。
     *
     * <pre>{@code
     * pipeline.vectorDb("memory", null);                          // 内存库（无必填）
     * pipeline.vectorDb("jvector", null);                         // 本地磁盘 ANN 索引（无必填）
     * pipeline.vectorDb("milvus", Map.of(                         // 外部向量数据库
     *         "host", "127.0.0.1", "port", 19530,
     *         "collection", "voiceprint"));                      // token 可选
     * }</pre>
     *
     * <p><b>必填校验</b>：MILVUS 必须提供 host / port / collection（缺失立即抛出，
     * 避免运行期才失败）；MEMORY / JVECTOR 无必填项。</p>
     *
     * @param provider 存储类型（memory / jvector / milvus）
     * @param config   提供方配置：Map（键 host / port / token / collection），无配置传 null
     * @return this
     */
    public VoiceprintPipeline vectorDb(String provider, Object config) {
        String host = null;
        Integer port = null;
        String token = null;
        String collection = null;
        if (config != null) {
            if (!(config instanceof java.util.Map<?, ?> map)) {
                throw new IllegalArgumentException("config 需为 Map<String,Object>（键: host/port/token/collection）: "
                        + config.getClass().getName());
            }
            host = trimOrNull(map.get("host"));
            port = Converter.convertIfNecessary(map.get("port"), Integer.class, null);
            token = trimOrNull(map.get("token"));
            collection = trimOrNull(map.get("collection"));
        }

        // 必填校验：外部向量库缺配置时快速失败
        String type = StringUtils.isBlank(provider) ? "" : provider.trim().toUpperCase();
        if ("MILVUS".equals(type)) {
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (StringUtils.isBlank(host)) {
                missing.add("host");
            }
            if (port == null) {
                missing.add("port");
            }
            if (StringUtils.isBlank(collection)) {
                missing.add("collection");
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        provider + " 缺少必填配置: " + missing + "（config 可用键: host/port/token/collection）");
            }
        }

        var builder = com.chua.common.support.vector.VectorStorageBuilder
                .newBuilder()
                .type(provider)
                .dimension(192)
                .algorithm(com.chua.common.support.vector.VectorCompareAlgorithm.cosine());
        if (!StringUtils.isBlank(host)) {
            builder.host(host);
        }
        if (port != null) {
            builder.port(port);
        }
        if (!StringUtils.isBlank(token)) {
            builder.token(token);
        }
        if (!StringUtils.isBlank(collection)) {
            builder.collection(collection);
        }
        return vectorDb(builder.build());
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
     * 配置声纹库持久化目录（须在首次入库/检索前调用）。
     *
     * @param dir 目录路径
     * @return this
     */
    public VoiceprintPipeline storageDir(Path dir) {
        if (storage.size() > 0) {
            throw new IllegalStateException("已有数据，不允许再切换存储目录");
        }
        this.storageDir = dir;
        return this;
    }

    /**
     * 配置检索返回条数。
     *
     * @param k TopK
     * @return this
     */
    public VoiceprintPipeline topK(int k) {
        this.topK = Math.max(1, k);
        return this;
    }

    /**
     * 配置相似度门槛：低于该值的匹配被过滤（1:1 验证场景建议 0.80）。
     *
     * @param t 余弦相似度阈值 [-1,1]；&lt;=0 关闭过滤
     * @return this
     */
    public VoiceprintPipeline threshold(double t) {
        this.threshold = t;
        return this;
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
        float[] samples = AudioUtils.loadMono16k(samplePath);
        float[] fp = campplus.extract(samples);
        if (!storage.add(speakerId, fp)) {
            throw new IllegalStateException("声纹入库失败: " + speakerId);
        }
        log.info("[Voiceprint] enrolled {}: dim={}", speakerId, fp.length);
        return this;
    }

    /**
     * 检索：使用已配置的 topK 与 threshold。
     *
     * @param samplePath 待测音频
     * @return 按相似度降序的匹配列表
     * @throws Exception 提取或检索失败
     */
    public List<Match> search(Path samplePath) throws Exception {
        return search(samplePath, topK);
    }

    /**
     * 检索：显式指定条数（threshold 仍然生效）。
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
