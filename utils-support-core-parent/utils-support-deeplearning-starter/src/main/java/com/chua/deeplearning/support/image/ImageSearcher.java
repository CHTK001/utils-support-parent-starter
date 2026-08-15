package com.chua.deeplearning.support.image;

import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.search.SearchPipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通用图片检索：{@link FeatureExtractor} 提特征 + {@link VectorStorage} 检索。
 *
 * <p>检索流程由 {@link SearchPipeline} 通用管线编排（提取特征 → 向量检索），
 * 取代手写顺序调用。</p>
 *
 * <pre>{@code
 * ImageSearcher searcher = ImageSearcher.builder()
 *         .featureExtractor(FeatureExtractor.create("pytorch-image-feature"))
 *         .vectorStorage(VectorStorageBuilder.newBuilder().dimension(512).algorithm("COSINE").build())
 *         .build();
 * searcher.enroll("id1", imageBytes);
 * List&lt;ImageSearchHit&gt; hits = searcher.search(queryBytes, 5);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageSearcher {

    /**
     * 特征提取器。
     */
    private final FeatureExtractor featureExtractor;

    /**
     * 向量库。
     */
    private final VectorStorage vectorStorage;

    /**
     * 检索管线。
     */
    private final SearchPipeline searchPipeline;

    /**
     * 构造。
     *
     * @param featureExtractor 特征提取器
     * @param vectorStorage    向量存储
     */
    public ImageSearcher(FeatureExtractor featureExtractor, VectorStorage vectorStorage) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
        this.vectorStorage = Objects.requireNonNull(vectorStorage, "vectorStorage");
        this.searchPipeline = new SearchPipeline(featureExtractor, vectorStorage);
    }

    /**
     * 链式构建器。
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 图片检索构建器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static final class Builder {

        /**
         * 特征提取器。
         */
        private FeatureExtractor featureExtractor;

        /**
         * 向量库。
         */
        private VectorStorage vectorStorage;

        /**
         * 设置特征提取器。
         *
         * @param featureExtractor 特征提取器
         * @return this
         */
        public Builder featureExtractor(FeatureExtractor featureExtractor) {
            this.featureExtractor = featureExtractor;
            return this;
        }

        /**
         * 按模型 ID 创建特征提取器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder featureExtractor(String modelId) {
            this.featureExtractor = FeatureExtractor.create(modelId);
            return this;
        }

        /**
         * 设置向量存储。
         *
         * @param vectorStorage 向量库
         * @return this
         */
        public Builder vectorStorage(VectorStorage vectorStorage) {
            this.vectorStorage = vectorStorage;
            return this;
        }

        /**
         * 构建检索器。
         *
         * @return ImageSearcher
         */
        public ImageSearcher build() {
            return new ImageSearcher(featureExtractor, vectorStorage);
        }
    }

    /**
     * 提取特征。
     *
     * @param imageData 图片字节
     * @return 特征向量
     */
    public float[] extract(byte[] imageData) {
        float[] feature = featureExtractor.extract(imageData);
        if (feature == null) {
            throw new IllegalStateException("特征提取结果为空");
        }
        return feature;
    }

    /**
     * 入库：图片 → 特征 → 向量库。
     *
     * @param id        业务 ID
     * @param imageData 图片
     * @return 是否成功
     */
    public boolean enroll(String id, byte[] imageData) {
        return vectorStorage.add(id, extract(imageData));
    }

    /**
     * 入库：直接写特征。
     *
     * @param id      业务 ID
     * @param feature 特征
     * @return 是否成功
     */
    public boolean enroll(String id, float[] feature) {
        return vectorStorage.add(id, feature);
    }

    /**
     * 入库：带元数据。
     *
     * @param id        业务 ID
     * @param imageData 图片
     * @param metadata  元数据
     * @param content   附加文本
     * @return 是否成功
     */
    public boolean enroll(String id, byte[] imageData, Map<String, Object> metadata, String content) {
        return vectorStorage.add(new Vector(id, extract(imageData),
                metadata == null ? Map.of() : metadata, content));
    }

    /**
     * 检索相似图片。
     *
     * @param imageData 查询图
     * @param topK      返回条数
     * @return 命中列表
     */
    public List<ImageSearchHit> search(byte[] imageData, int topK) {
        List<Vector> vectors = searchPipeline.search(imageData, topK);
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }
        List<ImageSearchHit> hits = new ArrayList<>(vectors.size());
        for (Vector v : vectors) {
            double score = 0d;
            if (v.metadata() != null && v.metadata().get("score") instanceof Number n) {
                score = n.doubleValue();
            }
            hits.add(new ImageSearchHit(
                    v.id(),
                    score,
                    v.data(),
                    v.metadata() == null ? Collections.emptyMap() : v.metadata(),
                    v.content()));
        }
        return hits;
    }

    /**
     * 按特征检索。
     *
     * @param feature 查询特征
     * @param topK    返回条数
     * @return 命中列表
     */
    public List<ImageSearchHit> search(float[] feature, int topK) {
        List<Vector> vectors = vectorStorage.search(feature, Math.max(1, topK));
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }
        List<ImageSearchHit> hits = new ArrayList<>(vectors.size());
        for (Vector v : vectors) {
            double score = 0d;
            if (v.metadata() != null && v.metadata().get("score") instanceof Number n) {
                score = n.doubleValue();
            }
            hits.add(new ImageSearchHit(
                    v.id(),
                    score,
                    v.data(),
                    v.metadata() == null ? Collections.emptyMap() : v.metadata(),
                    v.content()));
        }
        return hits;
    }

    /**
     * 特征提取器。
     *
     * @return FeatureExtractor
     */
    public FeatureExtractor featureExtractor() {
        return featureExtractor;
    }

    /**
     * 向量库。
     *
     * @return VectorStorage
     */
    public VectorStorage vectorStorage() {
        return vectorStorage;
    }
}
