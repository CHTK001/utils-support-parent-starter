package com.chua.deeplearning.support.face;

import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.liveness.LivenessDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageCropUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 人脸识别组合：检测 → 裁剪 →（可选）活体 → 特征 → {@link VectorStorage} 检索。
 *
 * <pre>{@code
 * FaceIdentify identify = FaceIdentify.builder()
 *         .detector("opencv-face")
 *         .liveness("face-anti-spoof")   // 可选
 *         .featureExtractor("pytorch-insightface")
 *         .vectorStorage(storage)
 *         .topK(3)
 *         .requireLive(true)
 *         .build();
 * List&lt;FaceIdentifyHit&gt; results = identify.identify(sceneBytes);
 * }</pre>sults = identify.identify(sceneBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FaceIdentify {

    /**
     * 默认 Top-K 值。
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 默认活体阈值。
     */
    private static final float DEFAULT_LIVENESS_THRESHOLD = 0.5f;

    /**
     * 人脸检测器。
     */
    private final FaceDetector detector;

    /**
     * 活体检测器，可为 空。
     */
    private final LivenessDetector liveness;

    /**
     * 特征提取器。
     */
    private final FeatureExtractor featureExtractor;

    /**
     * 向量库。
     */
    private final VectorStorage vectorStorage;

    /**
     * 检索 Top-K。
     */
    private final int topK;

    /**
     * 是否要求活体通过才继续识别。
     */
    private final boolean requireLive;

    /**
     * 活体阈值。
     */
    private final float livenessThreshold;

    /**
     * 构造。
     *
     * @param detector           检测器
     * @param liveness           活体，可为 空
     * @param featureExtractor   特征
     * @param vectorStorage      向量库
     * @param topK               Top-K
     * @param requireLive        是否过滤非活体
     * @param livenessThreshold  活体阈值
     */
    public FaceIdentify(FaceDetector detector,
                        LivenessDetector liveness,
                        FeatureExtractor featureExtractor,
                        VectorStorage vectorStorage,
                        int topK,
                        boolean requireLive,
                        float livenessThreshold) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.liveness = liveness;
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
        this.vectorStorage = Objects.requireNonNull(vectorStorage, "vectorStorage");
        this.topK = Math.max(1, topK);
        this.requireLive = requireLive;
        this.livenessThreshold = livenessThreshold;
    }

    /**
     * 兼容旧构造（无活体）。
     *
     * @param detector         检测器
     * @param featureExtractor 特征
     * @param vectorStorage    向量库
     * @param topK             Top-K
     */
    public FaceIdentify(FaceDetector detector,
                        FeatureExtractor featureExtractor,
                        VectorStorage vectorStorage,
                        int topK) {
        this(detector, null, featureExtractor, vectorStorage, topK, true, DEFAULT_LIVENESS_THRESHOLD);
    }

    /**
     * 构建器。
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 链式构建器。
     *
     * @since 4.0.0.42
     */
    public static final class Builder {

        /**
         * 人脸检测器。
         */
        private FaceDetector detector;

        /**
         * 活体检测器。
         */
        private LivenessDetector liveness;

        /**
         * 特征提取器。
         */
        private FeatureExtractor featureExtractor;

        /**
         * 向量库。
         */
        private VectorStorage vectorStorage;

        /**
         * 检索返回 Top-K 条数。
         */
        private int topK = DEFAULT_TOP_K;

        /**
         * 是否要求活体通过。
         */
        private boolean requireLive = true;

        /**
         * 活体分数阈值。
         */
        private float livenessThreshold = DEFAULT_LIVENESS_THRESHOLD;

        /**
         * 设置检测器。
         *
         * @param detector 人脸检测
         * @return this
         */
        public Builder detector(FaceDetector detector) {
            this.detector = detector;
            return this;
        }

        /**
         * 按模型 标识 创建检测器。
         *
         * @param modelId 模型 标识
         * @return this
         */
        public Builder detector(String modelId) {
            this.detector = FaceDetector.create(modelId);
            return this;
        }

        /**
         * 设置活体检测器。
         *
         * @param liveness 活体
         * @return this
         */
        public Builder liveness(LivenessDetector liveness) {
            this.liveness = liveness;
            return this;
        }

        /**
         * 按模型 标识 创建活体检测器。
         *
         * @param modelId 模型 标识
         * @return this
         */
        public Builder liveness(String modelId) {
            this.liveness = LivenessDetector.create(modelId);
            return this;
        }

        /**
         * 设置特征提取器。
         *
         * @param featureExtractor 特征
         * @return this
         */
        public Builder featureExtractor(FeatureExtractor featureExtractor) {
            this.featureExtractor = featureExtractor;
            return this;
        }

        /**
         * 按模型 标识 创建特征提取器。
         *
         * @param modelId 模型 标识
         * @return this
         */
        public Builder featureExtractor(String modelId) {
            this.featureExtractor = FeatureExtractor.create(modelId);
            return this;
        }

        /**
         * 设置向量库。
         *
         * @param vectorStorage 向量库
         * @return this
         */
        public Builder vectorStorage(VectorStorage vectorStorage) {
            this.vectorStorage = vectorStorage;
            return this;
        }

        /**
         * Top-K。
         *
         * @param topK 条数
         * @return this
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 是否要求活体通过才识别。
         *
         * @param requireLive true 过滤假体
         * @return this
         */
        public Builder requireLive(boolean requireLive) {
            this.requireLive = requireLive;
            return this;
        }

        /**
         * 活体阈值。
         *
         * @param livenessThreshold 阈值
         * @return this
         */
        public Builder livenessThreshold(float livenessThreshold) {
            this.livenessThreshold = livenessThreshold;
            return this;
        }

        /**
         * 构建。
         *
         * @return FaceIdentify
         */
        public FaceIdentify build() {
            return new FaceIdentify(detector, liveness, featureExtractor, vectorStorage,
                    topK, requireLive, livenessThreshold);
        }
    }

    /**
     * 整图识别：检测全部人脸（可选活体）并检索。
     *
     * @param imageData 场景图
     * @return 每人脸识别结果
     */
    public List<FaceIdentifyHit> identify(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        List<FaceIdentifyHit> results = new ArrayList<>(boxes.size());
        for (PredictRectangle box : boxes) {
            results.add(identifyOne(imageData, box));
        }
        return results;
    }

    /**
     * 仅对最大人脸识别。
     *
     * @param imageData 场景图
     * @return 结果，无人脸时 空；requirelive 且非活体时仍返回 hit（live=false, hits 空）或 空
     */
    public FaceIdentifyHit identifyLargest(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return null;
        }
        return identifyOne(imageData, pickLargest(boxes));
    }

    /**
     * 对单个人脸框进行识别。
     *
     * @param imageData 原始图像数据
     * @param box       人脸检测框
     * @return 识别结果
     */
    private FaceIdentifyHit identifyOne(byte[] imageData, PredictRectangle box) {
        byte[] face = ImageCropUtils.crop(imageData, box);
        LivenessResult lr = evaluateLiveness(face);
        if (requireLive && liveness != null && !lr.live()) {
            return new FaceIdentifyHit(box, null, List.of(), false, lr.score());
        }
        float[] feature = featureExtractor.extract(face);
        List<FaceSearchHit> hits = searchFeature(feature, topK);
        return new FaceIdentifyHit(box, feature, hits, lr.live(), lr.score());
    }

    /**
     * 入库：已裁剪人脸图（可选活体校验）。
     *
     * @param id        人员 标识
     * @param faceImage 人脸图
     * @return 是否成功
     */
    public boolean enroll(String id, byte[] faceImage) {
        if (requireLive && liveness != null) {
            LivenessResult lr = evaluateLiveness(faceImage);
            if (!lr.live()) {
                return false;
            }
        }
        return vectorStorage.add(id, featureExtractor.extract(faceImage));
    }

    /**
     * 入库：场景图取最大人脸。
     *
     * @param id        人员 标识
     * @param imageData 场景图
     * @return 是否成功
     */
    public boolean enrollLargest(String id, byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return false;
        }
        byte[] face = ImageCropUtils.crop(imageData, pickLargest(boxes));
        return enroll(id, face);
    }

    /**
     * 入库：特征 + 元数据。
     *
     * @param id       人员 标识
     * @param feature  特征
     * @param metadata 元数据
     * @param content  内容
     * @return 是否成功
     */
    public boolean enroll(String id, float[] feature, Map<String, Object> metadata, String content) {
        return vectorStorage.add(new Vector(id, feature,
                metadata == null ? Map.of() : metadata, content));
    }

    /**
     * 评估活体状态。
     *
     * @param faceImage 人脸图像数据
     * @return 活体结果
     */
    private LivenessResult evaluateLiveness(byte[] faceImage) {
        if (liveness == null) {
            return new LivenessResult(true, 1.0f);
        }
        try {
            boolean live = liveness.isLive(faceImage);
            float score;
            try {
                score = liveness.liveScore(faceImage);
            } catch (Exception e) {
                score = live ? 1.0f : 0.0f;
            }
            return new LivenessResult(live, score);
        } catch (Exception e) {
            try {
                float score = liveness.liveScore(faceImage);
                return new LivenessResult(score >= livenessThreshold, score);
            } catch (Exception ex) {
                throw new IllegalStateException("活体检测失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 在向量库中检索特征。
     *
     * @param feature 查询特征
     * @param k       返回条数
     * @return 命中列表
     */
    private List<FaceSearchHit> searchFeature(float[] feature, int k) {
        List<com.chua.common.support.vector.Vector> vectors = vectorStorage.search(feature, k);
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }
        List<FaceSearchHit> hits = new ArrayList<>(vectors.size());
        for (com.chua.common.support.vector.Vector v : vectors) {
            double score = 0d;
            if (v.metadata() != null && v.metadata().get("score") instanceof Number n) {
                score = n.doubleValue();
            }
            hits.add(new FaceSearchHit(
                    v.id(),
                    score,
                    v.data(),
                    v.metadata() == null ? Map.of() : v.metadata(),
                    v.content()));
        }
        return hits;
    }

    /**
     * 选取面积最大的检测框。
     *
     * @param boxes 检测框列表
     * @return 最大框
     */
    private static PredictRectangle pickLargest(List<PredictRectangle> boxes) {
        PredictRectangle largest = boxes.getFirst();
        float maxArea = largest.width() * largest.height();
        for (int i = 1; i < boxes.size(); i++) {
            PredictRectangle b = boxes.get(i);
            float area = b.width() * b.height();
            if (area > maxArea) {
                maxArea = area;
                largest = b;
            }
        }
        return largest;
    }

    /**
     * 检测器。
     *
     * @return FaceDetector
     */
    public FaceDetector detector() {
        return detector;
    }

    /**
     * 活体检测器。
     *
     * @return LivenessDetector 或 空
     */
    public LivenessDetector liveness() {
        return liveness;
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
     * 活体检测结果记录。
     *
     * @since 4.0.0.42
     * @param live live
     * @param score score
     * @return liveness结果的结果
     */
    private record LivenessResult(boolean live, float score) {
    }
}
