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
 * 取代手写顺序调用。可选增强能力：</p>
 * <ul>
 *   <li>{@code segmentor} 语义/实例分割（先分割主体再检索，提升复杂背景精度）</li>
 *   <li>{@code enhancer} 通用图像增强（去噪/锐化/上色，改善检索质量）</li>
 *   <li>{@code superResolution} 超分辨率重建（模糊图 → 高清，提升小图检索）</li>
 * </ul>
 *
 * <pre>{@code
 * ImagePipeline pipeline = ImagePipeline.builder()
 *         .featureExtractor(FeatureExtractor.create("pytorch-image-feature"))
 *         .vectorStorage(VectorStorageBuilder.newBuilder().dimension(512).algorithm("COSINE").build())
 *         .segmentor(ImageSegmenter.create("seg-model"))     // 可选：分割
 *         .enhancer(ImageEnhancer.create("image-enhancer")) // 可选：增强
 *         .superResolution(ImageEnhancer.create("esrgan-sr"))// 可选：超分
 *         .build();
 * pipeline.enroll("id1", imageBytes);
 * List<ImageSearchHit> hits = pipeline.search(queryBytes, 5);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImagePipeline {

    /**
     * 特征提取器。
     */
    private final FeatureExtractor featureExtractor;

    /**
     * 向量库。
     */
    private final VectorStorage vectorStorage;

    /**
     * 分割器（可选：先分割主体再检索）。
     */
    private final ImageSegmenter segmentor;

    /**
     * 增强器（可选：去噪/锐化/上色）。
     */
    private final ImageEnhancer enhancer;

    /**
     * 超分辨率（可选：模糊图 → 高清）。
     */
    private final ImageEnhancer superResolution;

    /**
     * 检索管线。
     */
    private final SearchPipeline searchPipeline;

    /**
     * 构造。
     *
     * @param featureExtractor 特征提取器
     * @param vectorStorage    向量存储
     * @param segmentor        分割器，可为 null
     * @param enhancer         增强器，可为 null
     * @param superResolution  超分辨率，可为 null
     */
    public ImagePipeline(FeatureExtractor featureExtractor, VectorStorage vectorStorage,
                         ImageSegmenter segmentor, ImageEnhancer enhancer, ImageEnhancer superResolution) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
        this.vectorStorage = Objects.requireNonNull(vectorStorage, "vectorStorage");
        this.segmentor = segmentor;
        this.enhancer = enhancer;
        this.superResolution = superResolution;
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
         * 分割器（可选）。
         */
        private ImageSegmenter segmentor;

        /**
         * 增强器（可选）。
         */
        private ImageEnhancer enhancer;

        /**
         * 超分辨率（可选）。
         */
        private ImageEnhancer superResolution;

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
         * 设置分割器（可选：先分割主体再检索，提升复杂背景精度）。
         *
         * @param segmentor 分割器
         * @return this
         */
        public Builder segmentor(ImageSegmenter segmentor) {
            this.segmentor = segmentor;
            return this;
        }

        /**
         * 按模型 ID 创建分割器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder segmentor(String modelId) {
            this.segmentor = ImageSegmenter.create(modelId);
            return this;
        }

        /**
         * 设置增强器（可选：去噪/锐化/上色，改善检索质量）。
         *
         * @param enhancer 增强器
         * @return this
         */
        public Builder enhancer(ImageEnhancer enhancer) {
            this.enhancer = enhancer;
            return this;
        }

        /**
         * 按模型 ID 创建增强器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder enhancer(String modelId) {
            this.enhancer = ImageEnhancer.create(modelId);
            return this;
        }

        /**
         * 设置超分辨率（可选：模糊图 → 高清，提升小图检索精度）。
         *
         * @param superResolution 超分辨率增强器
         * @return this
         */
        public Builder superResolution(ImageEnhancer superResolution) {
            this.superResolution = superResolution;
            return this;
        }

        /**
         * 按模型 ID 创建超分辨率。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder superResolution(String modelId) {
            this.superResolution = ImageEnhancer.create(modelId);
            return this;
        }

        /**
         * 构建检索器。
         *
         * @return ImagePipeline
         */
        public ImagePipeline build() {
            return new ImagePipeline(featureExtractor, vectorStorage, segmentor, enhancer, superResolution);
        }
    }

    /**
     * 提取特征（可选增强 + 超分预处理）。
     *
     * <p>若已配置 enhancer/superResolution，会先对图像做增强/超分再提取特征。</p>
     *
     * @param imageData 图片字节
     * @return 特征向量
     */
    public float[] extract(byte[] imageData) {
        byte[] processed = imageData;
        if (enhancer != null) {
            processed = enhancer.enhance(processed);
        }
        if (superResolution != null) {
            processed = superResolution.enhance(processed);
        }
        float[] feature = featureExtractor.extract(processed);
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

    /**
     * 分割器。
     *
     * @return ImageSegmenter，未配置时返回 null
     */
    public ImageSegmenter segmentor() {
        return segmentor;
    }

    /**
     * 增强器。
     *
     * @return ImageEnhancer，未配置时返回 null
     */
    public ImageEnhancer enhancer() {
        return enhancer;
    }

    /**
     * 超分辨率。
     *
     * @return ImageEnhancer，未配置时返回 null
     */
    public ImageEnhancer superResolution() {
        return superResolution;
    }

    /**
     * 图像分割（辅助能力）。
     *
     * @param imageData 图像
     * @return 分割掩码图像，未配置时返回原始图像
     */
    public byte[] segment(byte[] imageData) {
        if (segmentor == null) {
            return imageData;
        }
        return segmentor.segment(imageData);
    }

    /**
     * 图像增强（辅助能力）。
     *
     * @param imageData 图像
     * @return 增强后图像，未配置时返回原始图像
     */
    public byte[] enhance(byte[] imageData) {
        if (enhancer == null) {
            return imageData;
        }
        return enhancer.enhance(imageData);
    }

    /**
     * 超分辨率重建（辅助能力）。
     *
     * @param imageData 图像
     * @return 高清图像，未配置时返回原始图像
     */
    public byte[] superResolve(byte[] imageData) {
        if (superResolution == null) {
            return imageData;
        }
        return superResolution.enhance(imageData);
    }
}
