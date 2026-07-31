package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.liveness.LivenessDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.utils.ImageCropUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 人脸检测链路：检测 → 裁剪 →（可选）活体。
 *
 * <pre>{@code
 * FacePipeline pipeline = FacePipeline.builder()
 *         .detector("opencv-face")
 *         .liveness("face-anti-spoof")  // 可选
 *         .requireLive(true)
 *         .build();
 * List&lt;FaceDetectionHit&gt; faces = pipeline.detect(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FacePipeline {

    /**
     * 人脸检测器。
     */
    private final FaceDetector detector;

    /**
     * 活体检测器，可为 null。
     */
    private final LivenessDetector liveness;

    /**
     * 是否要求活体通过才返回（仅 liveness 非空时生效）。
     */
    private final boolean requireLive;

    /**
     * 活体分数阈值（用于 liveScore 判断，若 isLive 可用则优先 isLive）。
     */
    private final float livenessThreshold;

    /**
     * 构造。
     *
     * @param detector           检测器
     * @param liveness           活体，可为 null
     * @param requireLive        是否过滤非活体
     * @param livenessThreshold  活体阈值
     */
    public FacePipeline(FaceDetector detector,
                        LivenessDetector liveness,
                        boolean requireLive,
                        float livenessThreshold) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.liveness = liveness;
        this.requireLive = requireLive;
        this.livenessThreshold = livenessThreshold;
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
     * @author CH
     * @since 4.0.0.42
     */
    public static final class Builder {

        private FaceDetector detector;
        private LivenessDetector liveness;
        private boolean requireLive = true;
        private float livenessThreshold = 0.5f;

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
         * 构建。
         *
         * @return FacePipeline
         */
        public FacePipeline build() {
            return new FacePipeline(detector, liveness, requireLive, livenessThreshold);
        }
    }

    /**
     * 检测链路：全部人脸（可按活体过滤）。
     *
     * @param imageData 场景图
     * @return 检测命中
     */
    public List<FaceDetectionHit> detect(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        List<FaceDetectionHit> hits = new ArrayList<>(boxes.size());
        for (PredictRectangle box : boxes) {
            byte[] face = ImageCropUtils.crop(imageData, box);
            LivenessResult lr = evaluateLiveness(face);
            if (requireLive && liveness != null && !lr.live()) {
                continue;
            }
            hits.add(new FaceDetectionHit(box, face, lr.live(), lr.score()));
        }
        return hits;
    }

    /**
     * 检测最大人脸（可要求活体）。
     *
     * @param imageData 场景图
     * @return 命中，无则 null
     */
    public FaceDetectionHit detectLargest(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
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
        return boxes == null ? List.of() : boxes;
    }

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
            // 若 isLive 与 threshold 不一致，以 isLive 为准，并用 threshold 兜底分数
            if (!live && score >= livenessThreshold) {
                // 模型 isLive 已判死，保留分数
            }
            return new LivenessResult(live, score);
        } catch (Exception e) {
            // 部分模型只返回 Float：回退 liveScore
            try {
                float score = liveness.liveScore(faceImage);
                return new LivenessResult(score >= livenessThreshold, score);
            } catch (Exception ex) {
                throw new IllegalStateException("活体检测失败: " + e.getMessage(), e);
            }
        }
    }

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

    private record LivenessResult(boolean live, float score) {
    }
}
