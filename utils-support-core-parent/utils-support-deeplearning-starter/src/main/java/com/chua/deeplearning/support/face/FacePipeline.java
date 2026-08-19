package com.chua.deeplearning.support.face;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.draw.DrawerPipeline;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageEnhancer;
import com.chua.deeplearning.support.liveness.LivenessDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.FaceQualityInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 人脸统一能力门面。
 *
 * <p>基于 {@link Pipeline} 通用管线框架，聚合人脸识别全量能力：
 * 检测、裁剪、活体、特征、1:1 比对、1:N 检索、登记、动漫、超分、修复、
 * 属性、表情、关键点、质量、换脸检测。</p>
 *
 * <p>管线能力（detect → crop → liveness → [feature → search]）由通用管线框架编排，
 * 多人脸场景由外层循环驱动，每张人脸一个独立 {@link PipelineContext}；
 * 单独能力直接委托底层模型实例，未注入的能力返回安全默认值。</p>
 *
 * <pre>{@code
 * FacePipeline pipeline = FacePipeline.builder()
 *         .detector("scrfd-face-detector")
 *         .feature("r50-face-feature")
 *         .liveness("face-anti-spoof")
 *         .anime("anime-face-detector")
 *         .superResolution("gfpgan-face-super-resolution")
 *         .restore("codeformer")
 *         .attribute("age-race-gender")
 *         .emotion("emotion-ferplus")
 *         .landmark("faceplugin-face-landmark")
 *         .deepfake("deepfake-detector")
 *         .requireLive(true)
 *         .build();
 *
 * List&lt;FaceDetectionHit&gt; faces = pipeline.detectPipeline(imageBytes);
 * byte[] enhanced = pipeline.superResolution(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FacePipeline {

    /**
     * 节点：裁剪
     */
    private static final String NODE_CROP = "crop";

    /**
     * 节点：活体
     */
    private static final String NODE_LIVENESS = "liveness";

    /**
     * 节点：对齐
     */
    private static final String NODE_ALIGN = "align";

    /**
     * 节点：修复
     */
    private static final String NODE_RESTORE = "restore";

    /**
     * 节点：高清化
     */
    private static final String NODE_ENHANCE = "enhance";

    /**
     * 节点：特征
     */
    private static final String NODE_FEATURE = "feature";

    /**
     * 节点：检索
     */
    private static final String NODE_SEARCH = "search";

    /**
     * 节点：收集检测
     */
    private static final String NODE_COLLECT = "collect";

    /**
     * 节点：收集识别
     */
    private static final String NODE_COLLECT_IDENTIFY = "collectIdentify";

    /**
     * 节点：终止
     */
    private static final String NODE_END = "end";

    /**
     * 人脸检测器。
     */
    private final FaceDetector detector;

    /**
     * 活体检测器，可为 null。
     */
    private final LivenessDetector liveness;

    /**
     * 特征提取器，可为 null（识别链路需要）。
     */
    private final FeatureExtractor featureExtractor;

    /**
     * 人脸向量库，可为 null（识别链路需要）。
     */
    private final VectorStorage vectorStorage;

    /**
     * 动漫人脸检测器，可为 null。
     */
    private final FaceDetector animeDetector;

    /**
     * 人脸超分器，可为 null。
     */
    private final ImageEnhancer superResolution;

    /**
     * 人脸修复器，可为 null。
     */
    private final ImageEnhancer restorer;

    /**
     * 属性分类器，可为 null。
     */
    private final ImageClassifier attributeClassifier;

    /**
     * 表情分类器，可为 null。
     */
    private final ImageClassifier emotionClassifier;

    /**
     * 关键点提取器，可为 null。
     */
    private final FeatureExtractor landmarkExtractor;

    /**
     * 质量评估器，可为 null。
     */
    private final FaceQualityAssessor qualityAssessor;

    /**
     * 换脸检测分类器，可为 null。
     */
    private final ImageClassifier deepfakeClassifier;

    /**
     * 检索 Top-K。
     */
    private final int topK;

    /**
     * 是否要求活体通过才返回（仅 liveness 非空时生效）。
     */
    private final boolean requireLive;

/**
     * 搜索 threshold
     */
    private final float livenessThreshold;

    /**
     * 检测框四扩展像素数
     */
    private final int cropPadding;

    /**
     * 最小人脸面积阈值（过滤过小检测框）
     */
    private final float minFaceArea;

    /**
     * 最小检测置信度（过滤低置信度检测框）
     */
    private final float minConfidence;

    /**
     * 识别（特征输出）是否应用 sigmoid（默认 true，部分模型输出 logits 需激活）
     */
    private final boolean sigmoidRecognize;

    /**
     * 单张人脸检测管线（裁剪 → 活体 → 收集）。
     */
    private final Pipeline detectPipeline;

    /**
     * 单张人脸识别管线（裁剪 → 活体 → 特征 → 检索 → 收集）。
     */
    private final Pipeline identifyPipeline;

    /**
     * 全量构造。
     *
     * @param detector            检测器
     * @param liveness            活体，可为 null
     * @param featureExtractor    特征，可为 null
     * @param vectorStorage       向量库，可为 null
     * @param animeDetector       动漫检测器，可为 null
     * @param superResolution     超分器，可为 null
     * @param restorer            修复器，可为 null
     * @param attributeClassifier 属性分类器，可为 null
     * @param emotionClassifier   表情分类器，可为 null
     * @param landmarkExtractor   关键点提取器，可为 null
     * @param qualityAssessor     质量评估器，可为 null
     * @param deepfakeClassifier  换脸分类器，可为 null
     * @param topK                Top-K
     * @param requireLive         是否过滤非活体
     * @param livenessThreshold   活体阈值
     */
    public FacePipeline(FaceDetector detector,
                        LivenessDetector liveness,
                        FeatureExtractor featureExtractor,
                        VectorStorage vectorStorage,
                        FaceDetector animeDetector,
                        ImageEnhancer superResolution,
                        ImageEnhancer restorer,
                        ImageClassifier attributeClassifier,
                        ImageClassifier emotionClassifier,
                        FeatureExtractor landmarkExtractor,
                        FaceQualityAssessor qualityAssessor,
                        ImageClassifier deepfakeClassifier,
                        int topK,
                        boolean requireLive,
                        float livenessThreshold,
                        int cropPadding,
                        float minFaceArea,
                        float minConfidence,
                        boolean sigmoidRecognize) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.liveness = liveness;
        this.featureExtractor = featureExtractor;
        this.vectorStorage = vectorStorage;
        this.animeDetector = animeDetector;
        this.superResolution = superResolution;
        this.restorer = restorer;
        this.attributeClassifier = attributeClassifier;
        this.emotionClassifier = emotionClassifier;
        this.landmarkExtractor = landmarkExtractor;
        this.qualityAssessor = qualityAssessor;
        this.deepfakeClassifier = deepfakeClassifier;
        this.topK = Math.max(1, topK);
        this.requireLive = requireLive;
        this.livenessThreshold = livenessThreshold;
        this.cropPadding = Math.max(0, cropPadding);
        this.minFaceArea = Math.max(0f, minFaceArea);
        this.minConfidence = Math.max(0f, Math.min(1f, minConfidence));
        this.sigmoidRecognize = sigmoidRecognize;
        this.detectPipeline = buildDetectPipeline();
        this.identifyPipeline = buildIdentifyPipeline();
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
         * 人脸检测器
         */
        private FaceDetector detector;

        /**
         * 活体检测器
         */
        private LivenessDetector liveness;

        /**
         * 特征提取器
         */
        private FeatureExtractor featureExtractor;

        /**
         * 向量存储
         */
        private VectorStorage vectorStorage;

        /**
         * 动漫人脸检测器
         */
        private FaceDetector animeDetector;

        /**
         * 超分辨率增强器
         */
        private ImageEnhancer superResolution;

        /**
         * 图像修复增强器
         */
        private ImageEnhancer restorer;

        /**
         * 属性分类器
         */
        private ImageClassifier attributeClassifier;

        /**
         * 情绪分类器
         */
        private ImageClassifier emotionClassifier;

        /**
         * 关键点提取器
         */
        private FeatureExtractor landmarkExtractor;

        /**
         * 人脸质量评估器
         */
        private FaceQualityAssessor qualityAssessor;

        /**
         * 深伪检测分类器
         */
        private ImageClassifier deepfakeClassifier;

        /**
         * Top-K 命中数量
         */
        private int topK = 5;

        /**
         * 是否要求活体检测通过
         */
        private boolean requireLive = true;

        /**
         * 活体检测置信度阈值
         */
        private float livenessThreshold = 0.5f;

        /**
         * 检测框四周扩展像素数（默认 0）
         */
        private int cropPadding;

        /**
         * 最小人脸面积阈值（默认 0 不过滤）
         */
        private float minFaceArea;

/**
         * 最小检测置信度（过滤低置信度检测框）
         */
        private float minConfidence;

        /**
         * 识别（特征输出）是否应用 sigmoid（默认 true）
         */
        private boolean sigmoidRecognize = true;

        /**
         * 设置检测器。
         *
         * @param detector 检测器
         * @return this
         */
        public Builder detector(FaceDetector detector) {
            this.detector = detector;
            return this;
        }

        /**
         * 按模型 ID 创建检测器。
         *
         * @param modelId 模型 ID
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
         * 按模型 ID 创建活体检测器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder liveness(String modelId) {
            this.liveness = LivenessDetector.create(modelId);
            return this;
        }

        /**
         * 设置特征提取器。
         *
         * @param extractor 特征
         * @return this
         */
        public Builder feature(FeatureExtractor extractor) {
            this.featureExtractor = extractor;
            return this;
        }

        /**
         * 按模型 ID 创建特征提取器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder feature(String modelId) {
            this.featureExtractor = FeatureExtractor.create(modelId);
            return this;
        }

        /**
         * 设置人脸向量库。
         *
         * @param storage 向量库
         * @return this
         */
        public Builder vectorStorage(VectorStorage storage) {
            this.vectorStorage = storage;
            return this;
        }

        /**
         * 设置动漫人脸检测器。
         *
         * @param detector 检测器
         * @return this
         */
        public Builder anime(FaceDetector detector) {
            this.animeDetector = detector;
            return this;
        }

        /**
         * 按模型 ID 创建动漫人脸检测器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder anime(String modelId) {
            this.animeDetector = FaceDetector.create(modelId);
            return this;
        }

        /**
         * 设置人脸超分器。
         *
         * @param enhancer 超分器
         * @return this
         */
        public Builder superResolution(ImageEnhancer enhancer) {
            this.superResolution = enhancer;
            return this;
        }

        /**
         * 按模型 ID 创建人脸超分器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder superResolution(String modelId) {
            this.superResolution = ImageEnhancer.create(modelId);
            return this;
        }

        /**
         * 设置人脸修复器。
         *
         * @param enhancer 修复器
         * @return this
         */
        public Builder restore(ImageEnhancer enhancer) {
            this.restorer = enhancer;
            return this;
        }

        /**
         * 按模型 ID 创建人脸修复器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder restore(String modelId) {
            this.restorer = ImageEnhancer.create(modelId);
            return this;
        }

        /**
         * 设置属性分类器。
         *
         * @param classifier 分类器
         * @return this
         */
        public Builder attribute(ImageClassifier classifier) {
            this.attributeClassifier = classifier;
            return this;
        }

        /**
         * 按模型 ID 创建属性分类器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder attribute(String modelId) {
            this.attributeClassifier = ImageClassifier.create(modelId);
            return this;
        }

        /**
         * 设置表情分类器。
         *
         * @param classifier 分类器
         * @return this
         */
        public Builder emotion(ImageClassifier classifier) {
            this.emotionClassifier = classifier;
            return this;
        }

        /**
         * 按模型 ID 创建表情分类器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder emotion(String modelId) {
            this.emotionClassifier = ImageClassifier.create(modelId);
            return this;
        }

        /**
         * 设置关键点提取器。
         *
         * @param extractor 关键点提取器
         * @return this
         */
        public Builder landmark(FeatureExtractor extractor) {
            this.landmarkExtractor = extractor;
            return this;
        }

        /**
         * 按模型 ID 创建关键点提取器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder landmark(String modelId) {
            this.landmarkExtractor = FeatureExtractor.create(modelId);
            return this;
        }

        /**
         * 设置质量评估器。
         *
         * @param assessor 质量评估器
         * @return this
         */
        public Builder quality(FaceQualityAssessor assessor) {
            this.qualityAssessor = assessor;
            return this;
        }

        /**
         * 设置换脸检测分类器。
         *
         * @param classifier 分类器
         * @return this
         */
        public Builder deepfake(ImageClassifier classifier) {
            this.deepfakeClassifier = classifier;
            return this;
        }

        /**
         * 按模型 ID 创建换脸检测分类器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder deepfake(String modelId) {
            this.deepfakeClassifier = ImageClassifier.create(modelId);
            return this;
        }

        /**
         * 设置检索 Top-K。
         *
         * @param topK 条数
         * @return this
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * 是否要求活体通过才纳入结果。
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
         * 设置检测框四周扩展像素数。
         *
         * @param cropPadding 扩展像素数
         * @return this
         */
        public Builder cropPadding(int cropPadding) {
            this.cropPadding = cropPadding;
            return this;
        }

        /**
         * 设置最小人脸面积阈值（过滤过小检测框）。
         *
         * @param minFaceArea 最小面积（像素²）
         * @return this
         */
        public Builder minFaceArea(float minFaceArea) {
            this.minFaceArea = minFaceArea;
            return this;
        }

        /**
         * 设置最小检测置信度（过滤低置信度检测框）。
         *
         * @param minConfidence 阈值 0~1
         * @return this
         */
        public Builder minConfidence(float minConfidence) {
            this.minConfidence = minConfidence;
            return this;
        }

        /**
         * 设置识别（特征输出）是否应用 sigmoid。
         *
         * @param sigmoidRecognize true 应用 sigmoid
         * @return this
         */
        public Builder sigmoidRecognize(boolean sigmoidRecognize) {
            this.sigmoidRecognize = sigmoidRecognize;
            return this;
        }

        /**
         * 构建。
         *
         * @return FacePipeline
         */
        public FacePipeline build() {
            return new FacePipeline(detector, liveness, featureExtractor, vectorStorage,
                    animeDetector, superResolution, restorer,
                    attributeClassifier, emotionClassifier, landmarkExtractor,
                    qualityAssessor, deepfakeClassifier,
                    topK, requireLive, livenessThreshold,
                    cropPadding, minFaceArea, minConfidence, sigmoidRecognize);
        }
    }

    /**
     * 编排单张人脸检测管线（裁剪 → 活体 → 收集）。
     *
     * @return 管线实例
     */
    private Pipeline buildDetectPipeline() {
        return PipelineBuilder.newBuilder("face-detect")
                .task(NODE_CROP, ctx -> {
                    FaceContext fc = current(ctx);
                    if (fc.currentBox() == null) {
                        return null;
                    }
                    // 检测框四周扩展（可配置），提升裁剪包容性
                    PredictRectangle box = fc.currentBox();
                    int px = cropPadding;
                    byte[] face = ImageCropUtils.crop(fc.imageData(),
                            (int) box.x() - px, (int) box.y() - px,
                            (int) box.width() + px * 2, (int) box.height() + px * 2);
                    fc.currentFace(face);
                    return null;
                }).taskEnd()
                .decision("hasFace", ctx -> current(ctx).currentFace() != null ? NODE_LIVENESS : NODE_END)
                .task(NODE_LIVENESS, ctx -> {
                    FaceContext fc = current(ctx);
                    // 活体检测用外扩 100% 的头部区域（FLRGB 等需含上下文，紧贴框会误判）
                    byte[] liveFace = fc.currentFace();
                    PredictRectangle box = fc.currentBox();
                    if (liveFace != null && box != null && liveness != null) {
                        byte[] expanded = expandFaceCrop(fc.imageData(), box);
                        if (expanded != null) {
                            liveFace = expanded;
                        }
                    }
                    LivenessResult lr = evaluateLiveness(liveFace);
                    fc.currentLive(lr.live(), lr.score());
                    return null;
                }).taskEnd()
                .decision("isLive", ctx -> !requireLive || liveness == null || current(ctx).currentLive() ? NODE_COLLECT : NODE_END)
                .task(NODE_COLLECT, ctx -> {
                    FaceContext fc = current(ctx);
                    fc.addDetectionHit(new FaceDetectionHit(
                            fc.currentBox(), fc.currentFace(),
                            fc.currentLive(), fc.currentLiveScore()));
                    return null;
                }).taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 编排单张人脸识别管线（裁剪 → 活体 → 对齐 → 修复 → 高清化 → 特征 → 检索）。
     *
     * @return 管线实例
     */
    private Pipeline buildIdentifyPipeline() {
        return PipelineBuilder.newBuilder("face-identify")
                .task(NODE_CROP, ctx -> {
                    FaceContext fc = current(ctx);
                    if (fc.currentBox() == null) {
                        return null;
                    }
                    // 检测框四周扩展（可配置），提升裁剪包容性
                    PredictRectangle box = fc.currentBox();
                    int px = cropPadding;
                    byte[] face = ImageCropUtils.crop(fc.imageData(),
                            (int) box.x() - px, (int) box.y() - px,
                            (int) box.width() + px * 2, (int) box.height() + px * 2);
                    fc.currentFace(face);
                    return null;
                }).taskEnd()
                .decision("hasFace", ctx -> current(ctx).currentFace() != null ? NODE_LIVENESS : NODE_END)
                .task(NODE_LIVENESS, ctx -> {
                    FaceContext fc = current(ctx);
                    LivenessResult lr = evaluateLiveness(fc.currentFace());
                    fc.currentLive(lr.live(), lr.score());
                    return null;
                }).taskEnd()
                .decision("isLive", ctx -> !requireLive || liveness == null || current(ctx).currentLive() ? NODE_ALIGN : NODE_END)
                .task(NODE_ALIGN, ctx -> {
                    // 人脸对齐：基于关键点摆正（旋转到两眼水平），提升后续特征提取精度
                    FaceContext fc = current(ctx);
                    byte[] face = fc.currentFace();
                    if (face != null) {
                        fc.currentAlignedFace(alignFace(face));
                    }
                    return null;
                }).taskEnd()
                .decision("hasAligned", ctx -> current(ctx).currentAlignedFace() != null ? NODE_RESTORE : NODE_END)
                .task(NODE_RESTORE, ctx -> {
                    // 人脸修复（对齐之后）：修复遮挡/模糊/老化，输出高清修复人脸
                    FaceContext fc = current(ctx);
                    byte[] face = fc.currentAlignedFace();
                    if (face != null) {
                        fc.currentRestoredFace(restorer == null ? face : restorer.enhance(face));
                    }
                    return null;
                }).taskEnd()
                .decision("hasRestored", ctx -> current(ctx).currentRestoredFace() != null ? NODE_ENHANCE : NODE_END)
                .task(NODE_ENHANCE, ctx -> {
                    // 人脸高清化（修复之后）：超分提升分辨率
                    FaceContext fc = current(ctx);
                    byte[] face = fc.currentRestoredFace();
                    if (face != null) {
                        fc.currentEnhancedFace(superResolution == null ? face : superResolution.enhance(face));
                    }
                    return null;
                }).taskEnd()
                .task(NODE_FEATURE, ctx -> {
                    FaceContext fc = current(ctx);
                    // 特征提取用高清化后人脸（或对齐后/原图兜底）
                    byte[] face = fc.currentEnhancedFace();
                    if (face == null) {
                        face = fc.currentAlignedFace();
                    }
                    if (face == null) {
                        face = fc.currentFace();
                    }
                    if (featureExtractor != null && face != null) {
                        fc.currentFeature(featureExtractor.extract(face));
                    }
                    return null;
                }).taskEnd()
                .decision("hasFeature", ctx -> current(ctx).currentFeature() != null && vectorStorage != null ? NODE_SEARCH : NODE_COLLECT_IDENTIFY)
                .task(NODE_SEARCH, ctx -> {
                    FaceContext fc = current(ctx);
                    fc.currentHits(searchFeature(fc.currentFeature(), topK));
                    return null;
                }).taskEnd()
                .task(NODE_COLLECT_IDENTIFY, ctx -> {
                    FaceContext fc = current(ctx);
                    fc.addIdentifyHit(new FaceIdentifyHit(
                            fc.currentBox(), fc.currentFeature(),
                            fc.currentHits(), fc.currentLive(), fc.currentLiveScore()));
                    return null;
                }).taskEnd()
                .task(NODE_END, ctx -> null).end().taskEnd()
                .end(NODE_END)
                .build();
    }

    /**
     * 从管线上下文提取人脸上下文。
     *
     * @param ctx 管线上下文
     * @return 人脸上下文
     */
    @SuppressWarnings("unchecked")
    private static FaceContext current(PipelineContext<?> ctx) {
        return (FaceContext) ctx.getAttribute("face");
    }

    // ==================== 管线能力 ====================

    /**
     * 检测管线：全部人脸（可按活体过滤）。
     *
     * @param imageData 场景图
     * @return 检测命中
     */
    public List<FaceDetectionHit> detectPipeline(byte[] imageData) {
        return runDetect(imageData).detectionHits();
    }

    /**
     * 识别管线：全部人脸（可选活体 + 特征检索）。
     *
     * @param imageData 场景图
     * @return 识别命中
     */
    public List<FaceIdentifyHit> identifyPipeline(byte[] imageData) {
        List<PredictRectangle> boxes = detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return List.of();
        }
        FaceContext fc = new FaceContext(imageData, boxes);
        while (fc.advance()) {
            runSingle(fc, identifyPipeline);
        }
        return fc.identifyHits();
    }

    /**
     * 识别管线：取最大人脸。
     *
     * @param imageData 场景图
     * @return 识别命中，无则 null
     */
    public FaceIdentifyHit identifyPipelineLargest(byte[] imageData) {
        List<PredictRectangle> boxes = detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return null;
        }
        PredictRectangle largest = pickLargest(boxes);
        FaceContext fc = new FaceContext(imageData, List.of(largest));
        fc.advance();
        runSingle(fc, identifyPipeline);
        return fc.identifyHits().isEmpty() ? null : fc.identifyHits().get(0);
    }

    /**
     * 登记管线：取最大人脸 → 特征 → 入库。
     *
     * @param id        人脸 ID
     * @param imageData 场景图
     * @param metadata  元数据
     * @return 是否成功
     */
    public boolean enrollPipeline(String id, byte[] imageData, Map<String, Object> metadata) {
        if (featureExtractor == null || vectorStorage == null) {
            return false;
        }
        // 复用完整特征提取管线（检测 → 裁剪 → 活体 → 对齐 → 修复 → 高清化 → 特征）
        FaceFeaturePipelineResult result = extractFeatureWithMeta(imageData);
        if (result == null || result.feature() == null || result.feature().length == 0) {
            return false;
        }
        return vectorStorage.add(new com.chua.common.support.vector.Vector(
                id, result.feature(), metadata == null ? Map.of() : metadata, id));
    }

    /**
     * 运行检测管线，返回填充好的人脸上下文。
     *
     * @param imageData 场景图
     * @return 上下文（含检测命中）
     */
    private FaceContext runDetect(byte[] imageData) {
        List<PredictRectangle> boxes = detectBoxes(imageData);
        FaceContext fc = new FaceContext(imageData, boxes);
        while (fc.advance()) {
            runSingle(fc, detectPipeline);
        }
        return fc;
    }

    /**
     * 对单张人脸执行指定管线。
     *
     * @param fc       上下文
     * @param pipeline 管线
     */
    private void runSingle(FaceContext fc, Pipeline pipeline) {
        PipelineContext<FaceContext> ctx = new PipelineContext<>(pipeline.getId(), fc);
        ctx.setAttribute("face", fc);
        ctx.setNextNodeId(NODE_CROP);
        pipeline.resume(ctx);
    }

    // ==================== 单独能力 ====================

    /**
     * 人脸检测（单独能力，返回全部检测框 + 活体）。
     *
     * @param imageData 场景图
     * @return 检测命中
     */
    public List<FaceDetectionHit> detect(byte[] imageData) {
        return runDetect(imageData).detectionHits();
    }

    /**
     * 检测最大人脸（可要求活体）。
     *
     * @param imageData 场景图
     * @return 命中，无则 null
     */
    public FaceDetectionHit detectLargest(byte[] imageData) {
        List<PredictRectangle> boxes = detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return null;
        }
        PredictRectangle largest = pickLargest(boxes);
        byte[] face = ImageCropUtils.crop(imageData, largest);
        LivenessResult lr = evaluateLiveness(face);
        if (requireLive && liveness != null && !lr.live()) {
            return null;
        }
        return new FaceDetectionHit(largest, face, lr.live(), lr.score());
    }

    /**
     * 仅检测框，不做活体。
     *
     * @param imageData 图
     * @return 框列表
     */
    public List<PredictRectangle> detectBoxes(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        // 置信度 + 面积阈值过滤
        return boxes.stream()
                .filter(b -> b.confidence() >= minConfidence || minConfidence == 0f)
                .filter(b -> (b.width() * b.height()) >= minFaceArea || minFaceArea == 0f)
                .toList();
    }

    /**
     * 特征提取。
     *
     * @param imageData 图片
     * @return 特征向量，未配置特征模型时返回空数组
     */
    public float[] extractFeature(byte[] imageData) {
        if (featureExtractor == null) {
            return new float[0];
        }
        List<PredictRectangle> boxes = detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return new float[0];
        }
        FaceContext fc = new FaceContext(imageData, boxes);
        while (fc.advance()) {
            runSingle(fc, identifyPipeline);
            if (fc.currentFeature() != null && fc.currentFeature().length > 0) {
                return fc.currentFeature();
            }
        }
        return new float[0];
    }

    /**
     * 人脸对齐管线（最大人脸）：detect → crop → liveness → align。
     *
     * <p>未配置关键点模型时 alignedFace 等同于 faceImage（不旋转）。</p>
     *
     * @param imageData 场景图
     * @return 对齐结果，无人脸返回 null
     */
    public FaceAlignResult align(byte[] imageData) {
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return null;
        }
        PredictRectangle largest = pickLargest(boxes);
        FaceContext fc = new FaceContext(imageData, List.of(largest));
        fc.advance();
        runSingle(fc, identifyPipeline);
        byte[] aligned = fc.currentAlignedFace() != null ? fc.currentAlignedFace() : fc.currentFace();
        return new FaceAlignResult(
                fc.currentBox(),
                fc.currentFace(),
                aligned,
                fc.currentLive(),
                fc.currentLiveScore(),
                System.currentTimeMillis() - t0);
    }

    /**
     * 特征提取完整管线（最大人脸）：detect → crop → liveness → align → feature。
     *
     * <p>返回检测框、裁剪图、对齐图、活体、特征向量与耗时，活体失败时 feature 为 null。</p>
     *
     * @param imageData 场景图
     * @return 特征管线结果，无人脸返回 null
     */
    public FaceFeaturePipelineResult extractFeatureWithMeta(byte[] imageData) {
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return null;
        }
        PredictRectangle largest = pickLargest(boxes);
        FaceContext fc = new FaceContext(imageData, List.of(largest));
        fc.advance();
        runSingle(fc, identifyPipeline);
        float[] feature = fc.currentFeature();
        byte[] aligned = fc.currentAlignedFace() != null ? fc.currentAlignedFace() : fc.currentFace();
        return new FaceFeaturePipelineResult(
                fc.currentBox(),
                fc.currentFace(),
                aligned,
                fc.currentLive(),
                fc.currentLiveScore(),
                feature,
                feature == null ? 0 : feature.length,
                System.currentTimeMillis() - t0);
    }

    /**
     * 1:1 余弦相似度比对。
     *
     * @param featureA 特征 A
     * @param featureB 特征 B
     * @return 相似度 0~1
     */
    public double compareFeature(float[] featureA, float[] featureB) {
        return cosineSimilarity(featureA, featureB);
    }

    /**
     * 1:1 比对完整管线：两侧都跑 detect→crop→liveness→align→feature，再余弦比对。
     *
     * @param imageA 图片 A
     * @param imageB 图片 B
     * @return 比对结果（相似度 + A/B 特征管线结果 + 耗时），任一侧无人脸返回 null
     */
    public FaceCompareResult compareWithMeta(byte[] imageA, byte[] imageB) {
        long t0 = System.currentTimeMillis();
        FaceFeaturePipelineResult a = extractFeatureWithMeta(imageA);
        FaceFeaturePipelineResult b = extractFeatureWithMeta(imageB);
        if (a == null || b == null || a.feature() == null || b.feature() == null) {
            return null;
        }
        double score = cosineSimilarity(a.feature(), b.feature());
        return new FaceCompareResult(score, score >= 0.5, a, b, System.currentTimeMillis() - t0);
    }

    /**
     * 特征检索（需向量库）。
     *
     * @param feature 查询特征
     * @return 命中列表
     */
    public List<FaceSearchHit> search(float[] feature, int k) {
        return searchFeature(feature, k);
    }

    /**
     * 特征入库（单独能力，直接写特征）。
     *
     * @param id        人脸 ID
     * @param feature   特征向量
     * @param metadata  元数据
     * @param content   附加内容
     * @return 是否成功
     */
    public boolean enroll(String id, float[] feature, Map<String, Object> metadata, String content) {
        if (vectorStorage == null || feature == null) {
            return false;
        }
        return vectorStorage.add(new com.chua.common.support.vector.Vector(
                id, feature, metadata == null ? Map.of() : metadata, content));
    }

    /**
     * 活体检测（辅助能力）。
     *
     * @param faceImage 裁剪人脸图
     * @return 是否活体，未配置活体时返回 true
     */
    public boolean isLive(byte[] faceImage) {
        if (liveness == null) {
            return true;
        }
        return liveness.isLive(faceImage);
    }

    /**
     * 活体分数（辅助能力）。
     *
     * @param faceImage 裁剪人脸图
     * @return 分数，未配置活体时返回 1.0
     */
    public float liveScore(byte[] faceImage) {
        if (liveness == null) {
            return 1.0f;
        }
        try {
            return liveness.liveScore(faceImage);
        } catch (Exception e) {
            return 0.0f;
        }
    }

    /**
     * 动漫人脸检测（辅助能力）。
     *
     * @param imageData 图片
     * @return 检测信息，未配置动漫模型时返回空列表
     */
    public List<DetectionInfo> animeDetect(byte[] imageData) {
        if (animeDetector == null) {
            return List.of();
        }
        return animeDetector.detectInfo(imageData);
    }

    /**
     * 人脸超分（辅助能力）。
     *
     * @param imageData 图片
     * @return 超分后图片，未配置超分模型时原样返回
     */
    public byte[] superResolution(byte[] imageData) {
        if (superResolution == null) {
            return imageData;
        }
        return superResolution.enhance(imageData);
    }

    /**
     * 人脸修复（辅助能力）。
     *
     * @param imageData 图片
     * @return 修复后图片，未配置修复模型时原样返回
     */
    public byte[] restore(byte[] imageData) {
        if (restorer == null) {
            return imageData;
        }
        return restorer.enhance(imageData);
    }

    /**
     * 人脸属性分析（辅助能力）。
     *
     * @param imageData 图片
     * @return 属性标签，未配置时返回空串
     */
    public String attributes(byte[] imageData) {
        if (attributeClassifier == null) {
            return "";
        }
        return attributeClassifier.classify(imageData);
    }

    /**
     * 人脸表情识别（辅助能力）。
     *
     * @param imageData 图片
     * @return 表情标签，未配置时返回空串
     */
    public String emotion(byte[] imageData) {
        if (emotionClassifier == null) {
            return "";
        }
        return emotionClassifier.classify(imageData);
    }

    /**
     * 人脸关键点（辅助能力，106 点）。
     *
     * @param imageData 图片
     * @return 关键点坐标，未配置时返回空数组
     */
    public float[] landmark(byte[] imageData) {
        if (landmarkExtractor == null) {
            return new float[0];
        }
        return landmarkExtractor.extract(imageData);
    }

    /**
     * 人脸质量评估（辅助能力）。
     *
     * @param imageData 图片
     * @return 质量信息，未配置时返回 null
     */
    public FaceQualityInfo quality(byte[] imageData) {
        if (qualityAssessor == null) {
            return null;
        }
        return qualityAssessor.assess(imageData);
    }

    /**
     * 换脸检测（辅助能力）。
     *
     * @param imageData 图片
     * @return 是否疑似换脸，未配置时返回 false
     */
    public boolean isDeepfake(byte[] imageData) {
        if (deepfakeClassifier == null) {
            return false;
        }
        String result = deepfakeClassifier.classify(imageData);
        if (result == null || result.isBlank()) {
            return false;
        }
        String lower = result.toLowerCase();
        return lower.contains("fake") || lower.contains("spoof") || lower.contains("deepfake");
    }

    /**
     * 枚举可用模型清单。
     *
     * <p>动态从 {@link ModelRegistry} 注册表获取全部模型，按能力接口（capabilityInterface）
     * 与模型名称约定归类，不再硬编码模型 ID 清单；新增模型注册后自动出现在对应分组。</p>
     *
     * @return 能力分组 → 模型 ID 列表
     */
    public Map<String, List<String>> listModels() {
        try {
            ModelRegistry.discoverAll();
        } catch (Exception e) {
            log.warn("模型注册表发现失败: {}", e.getMessage());
        }
        Map<String, java.util.LinkedHashSet<String>> grouped = new java.util.LinkedHashMap<>();
        for (ModelRegistry.Entry e : ModelRegistry.getAll()) {
            String group = groupOf(e);
            if (group != null) {
                grouped.computeIfAbsent(group, k -> new java.util.LinkedHashSet<>()).add(e.modelId());
            }
        }
        // 向量库非模型，属存储后端枚举
        grouped.computeIfAbsent("vectorStorage", k -> new java.util.LinkedHashSet<>())
                .addAll(List.of("jvector", "memory", "milvus"));
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, java.util.LinkedHashSet<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    /**
     * 按能力接口与名称约定归类模型。
     *
     * @param entry 注册表条目
     * @return 能力分组；无法识别时返回 null
     */
    private static String groupOf(ModelRegistry.Entry entry) {
        String name = entry.modelId() == null ? "" : entry.modelId().toLowerCase();
        Class<?> cap = entry.capabilityInterface();
        if (cap == com.chua.deeplearning.support.face.FaceDetector.class) {
            return name.contains("anime") ? "animeDetector" : "detector";
        }
        if (cap == com.chua.deeplearning.support.feature.FeatureExtractor.class) {
            if (name.contains("landmark")) {
                return "landmark";
            }
            // 仅人脸特征模型，通用特征（clip/reid/hand-pose 等）不属于人脸能力
            return nameContains(name, "face-feature", "face_feature", "arc-face", "ada-face", "face-rec", "faceplugin-face-feature", "r50-face") ? "feature" : null;
        }
        if (cap == com.chua.deeplearning.support.liveness.LivenessDetector.class) {
            return "liveness";
        }
        if (cap == com.chua.deeplearning.support.image.ImageEnhancer.class) {
            return name.contains("codeformer") || name.contains("restore") ? "restore" : "superResolution";
        }
        if (cap == com.chua.deeplearning.support.image.ImageClassifier.class) {
            // 部分活体模型注册为 ImageClassifier，按名称关键字识别
            if (nameContains(name, "spoof", "liveness")) {
                return "liveness";
            }
            // 仅人脸属性分类模型归入 attribute，其它分类模型（通用/情感/动物/语言等）不属于人脸能力
            if (nameContains(name, "age-", "age-recognition", "gender", "race-", "race_gender")
                    && !name.contains("language")) {
                return "attribute";
            }
            return null;
        }
        if (cap == com.chua.deeplearning.support.face.FaceQualityAssessor.class) {
            return "quality";
        }
        // 能力接口未声明时的名称约定回退
        return nameContains(name, "anime-face") ? "animeDetector"
                : nameContains(name, "spoof", "liveness") ? "liveness"
                : nameContains(name, "face-detect", "face_detect", "scrfd", "ultra-face", "yolo-face-det", "faceplugin-face-detect") ? "detector"
                : nameContains(name, "face-feature", "face_feature", "arc-face", "ada-face", "face-rec", "faceplugin-face-feature", "r50-face", "face-feature") ? "feature"
                : nameContains(name, "gfpgan", "esrgan", "super-res") ? "superResolution"
                : nameContains(name, "codeformer", "restoreformer", "face-enhance") ? "restore"
                : nameContains(name, "emotion-fer", "fer-plus", "ferplus", "yolo-face-emotion") ? "emotion"
                : nameContains(name, "age-gender", "age-recognition", "race-gender") ? "attribute"
                : nameContains(name, "face-landmark", "landmark") ? "landmark"
                : nameContains(name, "deepfake") ? "deepfake"
                : null;
    }

    /**
     * 名称是否包含任一关键字。
     *
     * @param name 名称（小写）
     * @param keys 关键字
     * @return 命中任一返回 true
     */
    private static boolean nameContains(String name, String... keys) {
        for (String key : keys) {
            if (name.contains(key)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 内部 ====================

    /**
     * 评估活体状态。
     *
     * @param faceImage 人脸图
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
     * 向量检索。
     *
     * @param feature 查询特征
     * @param k       返回条数
     * @return 命中列表
     */
    private List<FaceSearchHit> searchFeature(float[] feature, int k) {
        if (feature == null || vectorStorage == null) {
            return List.of();
        }
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
                    v.id(), score, v.data(),
                    v.metadata() == null ? Map.of() : v.metadata(),
                    v.content()));
        }
        return hits;
    }

    /**
     * 人脸对齐：基于关键点摆正（旋转到左右眼水平）。
     *
     * <p>使用 {@code landmarkExtractor} 提取人脸关键点（68 点：索引 36-41 左眼、
     * 42-47 右眼；106 点：索引 74-75 左眼中心、77-78 右眼中心）。
     * 计算左右眼连线倾角，通过仿射旋转将人脸摆正，提升后续修复/高清化/特征提取精度。
     * 未配置关键点或提取失败时原样返回。</p>
     *
     * @param face 裁剪人脸图
     * @return 对齐后人脸图
     */
    private byte[] alignFace(byte[] face) {
        if (landmarkExtractor == null) {
            return face;
        }
        try {
            float[] landmark = landmarkExtractor.extract(face);
            if (landmark == null || landmark.length < 4) {
                return face;
            }
            Point leftEye = eyeCenter(landmark, 36, 41, 74, 75);
            Point rightEye = eyeCenter(landmark, 42, 47, 77, 78);
            if (leftEye == null || rightEye == null || leftEye.x == rightEye.x) {
                return face;
            }
            double angle = Math.toDegrees(Math.atan2(rightEye.y - leftEye.y, rightEye.x - leftEye.x));
            if (Math.abs(angle) < 0.5) {
                return face;
            }
            ImageUtils.load();
            Mat src = ImageUtils.decode(face);
            if (src == null || src.empty()) {
                return face;
            }
            try {
                Mat out = new Mat();
                Mat rot = Imgproc.getRotationMatrix2D(new Point(src.cols() / 2.0, src.rows() / 2.0), angle, 1.0);
                Imgproc.warpAffine(src, out, rot, new Size(src.cols(), src.rows()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
                byte[] result = ImageUtils.encode(out);
                rot.release();
                out.release();
                return result;
            } finally {
                src.release();
            }
        } catch (Exception e) {
            log.warn("[face-pipeline] 人脸对齐失败，使用原图: {}", e.getMessage());
            return face;
        }
    }

    /**
     * 计算眼睛中心坐标。
     *
     * <p>兼容 68 点（左眼 36-41、右眼 42-47）与 106 点（左眼 74-75、右眼 77-78）
     * 两种关键点布局：优先取瞳孔索引，否则取眼眶均值。</p>
     *
     * @param landmark  关键点数组（x,y 交替）
     * @param pupilIdx  瞳孔索引（如 74）
     * @param pupilIdx2 瞳孔索引2（如 75）
     * @param ringStart 眼眶起点
     * @param ringEnd   眼眶终点
     * @return 眼睛中心，数据不足返回 null
     */
    private static Point eyeCenter(float[] landmark, int pupilIdx, int pupilIdx2, int ringStart, int ringEnd) {
        int n = landmark.length / 2;
        if (n > pupilIdx2) {
            // 瞳孔索引存在
            return new Point(landmark[pupilIdx * 2], landmark[pupilIdx * 2 + 1]);
        }
        if (n > ringEnd) {
            // 眼眶均值
            double x = 0, y = 0;
            int count = 0;
            for (int i = ringStart; i <= ringEnd && i < n; i++) {
                x += landmark[i * 2];
                y += landmark[i * 2 + 1];
                count++;
            }
            if (count == 0) {
                return null;
            }
            return new Point(x / count, y / count);
        }
        return null;
    }

    /**
     * 外扩 100% 裁剪人脸区域（AIAS 同款），供活体检测等需要上下文的能力使用。
     *
     * @param imageData 原图
     * @param box       人脸框（像素）
     * @return 外扩裁剪图，越界或失败返回 null
     */
    private static byte[] expandFaceCrop(byte[] imageData, PredictRectangle box) {
        if (imageData == null || box == null) {
            return null;
        }
        try {
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                return null;
            }
            int iw = src.cols(), ih = src.rows();
            int x1 = (int) box.x(), y1 = (int) box.y();
            int x2 = x1 + (int) box.width(), y2 = y1 + (int) box.height();
            int newX1 = Math.max((int) (x1 + x1 * 0.5f - x2 * 0.5f), 0);
            int newX2 = Math.min((int) (x2 + x2 * 0.5f - x1 * 0.5f), iw - 1);
            int newY1 = Math.max((int) (y1 + y1 * 0.5f - y2 * 0.5f), 0);
            int newY2 = Math.min((int) (y2 + y2 * 0.5f - y1 * 0.5f), ih - 1);
            int cw = newX2 - newX1, ch = newY2 - newY1;
            if (cw <= 0 || ch <= 0) {
                src.release();
                return null;
            }
            Mat sub = ImageUtils.crop(src, newX1, newY1, cw, ch);
            byte[] result = ImageUtils.encode(sub);
            sub.release();
            src.release();
            return result;
        } catch (Exception e) {
            log.warn("[face-pipeline] 活体外扩裁剪失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 选择面积最大的检测框。
     *
     * @param boxes 检测框列表
     * @return 面积最大的检测框
     */
    private static PredictRectangle pickLargest(List<PredictRectangle> boxes) {
        PredictRectangle largest = boxes.get(0);
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
     * 计算两个特征向量的余弦相似度。
     *
     * @param a 特征向量 A
     * @param b 特征向量 B
     * @return 余弦相似度，参数非法时返回 0
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0d;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
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
     * @return LivenessDetector 或 null
     */
    public LivenessDetector liveness() {
        return liveness;
    }

    /**
     * 特征提取器。
     *
     * @return FeatureExtractor 或 null
     */
    public FeatureExtractor featureExtractor() {
        return featureExtractor;
    }

    /**
     * 向量库。
     *
     * @return VectorStorage 或 null
     */
    public VectorStorage vectorStorage() {
        return vectorStorage;
    }

    /** LivenessResult */
    private record LivenessResult(boolean live, float score) {
    }

    /**
     * 创建标注管线，支持一键绘制检测结果。
     *
     * @return DrawerPipeline 实例
     */
    public DrawerPipeline withInitDrawer() {
        return new DrawerPipeline(0.5f);
    }
}
