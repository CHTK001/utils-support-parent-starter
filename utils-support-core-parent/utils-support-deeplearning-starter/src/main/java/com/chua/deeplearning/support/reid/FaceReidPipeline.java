package com.chua.deeplearning.support.reid;

import com.chua.deeplearning.support.face.FacePipeline;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageCropUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 人脸重识别（Face Re-ID）门面：跨摄像头 / 跨帧识别同一个人。
 *
 * <p><b>与「人脸识别」的区别。</b>人脸识别回答「这张脸是谁」（1:1 核对或 1:N 定库检索）；
 * 重识别回答「<b>这个人</b>刚才从哪个摄像头走进了哪个摄像头」——
 * 它在识别之上多了一层<b>时间维度</b>：把同一个人的多次出现串成一条轨迹。</p>
 *
 * <p><b>与行人重识别（{@link PersonReidPipeline}）的区别。</b>后者对整幅行人图提特征
 * （OSNet），不需要检测；人脸重识别必须先<b>检测出人脸</b>再对每张脸单独提特征，
 * 因为一张监控画面里通常有多张脸，每张脸是独立的观察对象。</p>
 *
 * <p><b>处理链路</b>：</p>
 * <pre>
 * 场景图 ──detect──► 多张人脸框 ──crop──► 逐张提特征 ──observe──► 跨帧轨迹归并
 * </pre>
 *
 * <p><b>典型用法</b>：</p>
 * <pre>{@code
 * FaceReidPipeline reid = FaceReidPipeline.builder()
 *         .detector(pipeline)          // 复用已有的检测管线（模型不重复加载）
 *         .featureExtractor(extractor) // 人脸特征模型
 *         .track(new DefaultPersonTrack(0.5f, 10000, 0, 30_000))
 *         .build();
 *
 * // 摄像头 A 看到一个人
 * FaceReidPipeline.ReidResult r1 = reid.observe("cam-A", "frame-001", imageBytes);
 * // 摄像头 B 又看到他 —— 自动并入同一条轨迹
 * FaceReidPipeline.ReidResult r2 = reid.observe("cam-B", "frame-002", imageBytes);
 * // r2.trackId() == r1.trackId()
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class FaceReidPipeline {

    /**
     * 人脸检测管线（提供检测框）。
     */
    private final FacePipeline detector;

    /**
     * 人脸特征提取器。
     */
    private final FeatureExtractor featureExtractor;

    /**
     * 跨帧轨迹管理器。
     */
    private final PersonTrack track;

    /**
     * 构造。
     *
     * @param detector         检测管线
     * @param featureExtractor 特征提取器
     * @param track            轨迹管理器
     */
    public FaceReidPipeline(FacePipeline detector,
                            FeatureExtractor featureExtractor,
                            PersonTrack track) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
        this.track = Objects.requireNonNull(track, "track");
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
     * 单张脸的观测结果。
     *
     * @param box      人脸框
     * @param trackId  归并到的轨迹 ID
     * @param score    与轨迹代表特征的相似度（新轨迹为 1.0）
     * @param matched  是否命中已有轨迹
     */
    public record FaceObservation(PredictRectangle box, String trackId, double score, boolean matched) {
    }

    /**
     * 一帧的观测结果。
     *
     * @param cameraId    摄像头标识
     * @param sourceId    来源帧标识
     * @param observations 逐张脸的观测结果
     */
    public record ReidResult(String cameraId,
                              String sourceId,
                              List<FaceObservation> observations) {

        /**
         * 规范构造器：集合不可变。
         *
         * @param observations 观测列表
         */
        public ReidResult {
            observations = observations == null ? List.of() : List.copyOf(observations);
        }

        /**
         * 命中的轨迹 ID 列表（去重）。
         *
         * @return 轨迹 ID 列表
         */
        public List<String> trackIds() {
            List<String> ids = new ArrayList<>();
            for (FaceObservation observation : observations) {
                if (observation.trackId() != null && !ids.contains(observation.trackId())) {
                    ids.add(observation.trackId());
                }
            }
            return ids;
        }
    }

    /**
     * 观察一帧场景图：检测 → 裁剪 → 逐张提特征 → 归并进轨迹。
     *
     * <p><b>同帧多脸互不合并</b>：一张合影里的两个人是两条独立轨迹，
     * 由 {@link PersonTrack} 按来源帧保证。</p>
     *
     * @param cameraId 摄像头标识
     * @param sourceId 来源帧标识（同一帧的去重依据）
     * @param imageData 场景图字节
     * @return 观测结果；未检出人脸时 observations 为空
     */
    public ReidResult observe(String cameraId, String sourceId, byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            return new ReidResult(cameraId, sourceId, List.of());
        }
        List<PredictRectangle> boxes = detector.detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return new ReidResult(cameraId, sourceId, List.of());
        }
        List<FaceObservation> observations = new ArrayList<>(boxes.size());
        long timestamp = System.currentTimeMillis();
        for (PredictRectangle box : boxes) {
            byte[] face = ImageCropUtils.crop(imageData, box);
            if (face == null || face.length == 0) {
                continue;
            }
            float[] feature = featureExtractor.extract(face);
            if (feature == null || feature.length == 0) {
                continue;
            }
            PersonTrack.Observation observation =
                    track.observe(cameraId, sourceId, feature, timestamp);
            observations.add(new FaceObservation(box, observation.trackId(),
                    observation.score(), observation.matched()));
        }
        return new ReidResult(cameraId, sourceId, observations);
    }

    /**
     * 观察一帧并只返回最大人脸（单人场景的快捷路径）。
     *
     * @param cameraId 摄像头标识
     * @param sourceId 来源帧标识
     * @param imageData 场景图字节
     * @return 观测结果；未检出人脸返回 {@code null}
     */
    public FaceObservation observeLargest(String cameraId, String sourceId, byte[] imageData) {
        ReidResult result = observe(cameraId, sourceId, imageData);
        if (result.observations().isEmpty()) {
            return null;
        }
        FaceObservation largest = null;
        float largestArea = -1f;
        for (FaceObservation observation : result.observations()) {
            PredictRectangle box = observation.box();
            float area = box.width() * box.height();
            if (area > largestArea) {
                largestArea = area;
                largest = observation;
            }
        }
        return largest;
    }

    /**
     * 以图搜人：在已有轨迹中检索最相似的人。
     *
     * @param imageData 查询图
     * @param topK      返回条数
     * @return 命中列表（按相似度降序）
     */
    public List<PersonTrack.TrackMatch> search(byte[] imageData, int topK) {
        if (imageData == null || imageData.length == 0) {
            return List.of();
        }
        List<PredictRectangle> boxes = detector.detectBoxes(imageData);
        if (boxes.isEmpty()) {
            return List.of();
        }
        float[] feature = featureExtractor.extract(ImageCropUtils.crop(imageData, boxes.get(0)));
        if (feature == null || feature.length == 0) {
            return List.of();
        }
        return search(feature, topK);
    }

    /**
     * 以特征搜人：在已有轨迹中检索最相似的人。
     *
     * @param feature 查询特征
     * @param topK    返回条数
     * @return 命中列表（按相似度降序）
     */
    public List<PersonTrack.TrackMatch> search(float[] feature, int topK) {
        return track.search(feature, topK);
    }

    /**
     * 轨迹管理器。
     *
     * @return 轨迹管理器
     */
    public PersonTrack track() {
        return track;
    }

    /**
     * 检测管线。
     *
     * @return 检测管线
     */
    public FacePipeline detector() {
        return detector;
    }

    /**
     * 特征提取器。
     *
     * @return 特征提取器
     */
    public FeatureExtractor featureExtractor() {
        return featureExtractor;
    }

    /**
     * 人脸重识别构建器。
     */
    public static final class Builder {

        /**
         * 检测管线。
         */
        private FacePipeline detector;

        /**
         * 特征提取器。
         */
        private FeatureExtractor featureExtractor;

        /**
         * 轨迹管理器。
         */
        private PersonTrack track;

        /**
         * 设置检测管线。
         *
         * @param detector 检测管线
         * @return this
         */
        public Builder detector(FacePipeline detector) {
            this.detector = detector;
            return this;
        }

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
         * 按模型标识创建特征提取器。
         *
         * @param modelId 模型标识
         * @return this
         */
        public Builder featureExtractor(String modelId) {
            this.featureExtractor = FeatureExtractor.create(modelId);
            return this;
        }

        /**
         * 设置轨迹管理器。
         *
         * @param track 轨迹管理器
         * @return this
         */
        public Builder track(PersonTrack track) {
            this.track = track;
            return this;
        }

        /**
         * 构建。
         *
         * @return FaceReidPipeline
         */
        public FaceReidPipeline build() {
            PersonTrack target = track == null ? new DefaultPersonTrack() : track;
            return new FaceReidPipeline(detector, featureExtractor, target);
        }
    }
}
