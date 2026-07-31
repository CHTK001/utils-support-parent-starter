package com.chua.deeplearning.support.plate;

import com.chua.deeplearning.support.utils.ImageCropUtils;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.plate.PlateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 车牌识别流水线，组合车牌检测和车牌识别两个步骤完成端到端识别。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PlatePipeline {

    /**
     * 车牌检测器。
     */
    private final PlateDetector detector;

    /**
     * 车牌识别器。
     */
    private final LicensePlateRecognizer recognizer;

    /**
     * 构造车牌流水线。
     *
     * @param detector   车牌检测器
     * @param recognizer 车牌识别器
     */
    public PlatePipeline(PlateDetector detector,
                         LicensePlateRecognizer recognizer) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.recognizer = recognizer;
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
     * 车牌流水线构建器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static final class Builder {

        /**
         * 车牌检测器。
         */
        private PlateDetector detector;

        /**
         * 车牌识别器。
         */
        private LicensePlateRecognizer recognizer;

        /**
         * 设置车牌检测器。
         *
         * @param detector 检测器
         * @return this
         */
        public Builder detector(PlateDetector detector) {
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
            this.detector = PlateDetector.create(modelId);
            return this;
        }

        /**
         * 设置车牌识别器。
         *
         * @param recognizer 识别器
         * @return this
         */
        public Builder recognizer(LicensePlateRecognizer recognizer) {
            this.recognizer = recognizer;
            return this;
        }

        /**
         * 按模型 ID 创建识别器。
         *
         * @param modelId 模型 ID
         * @return this
         */
        public Builder recognizer(String modelId) {
            this.recognizer = LicensePlateRecognizer.create(modelId);
            return this;
        }

        /**
         * 构建。
         *
         * @return PlatePipeline
         */
        public PlatePipeline build() {
            return new PlatePipeline(detector, recognizer);
        }
    }

    /**
     * 检测并识别图像中所有车牌。
     *
     * @param imageData 图像数据
     * @return 车牌检测命中列表
     */
    public List<PlateDetectHit> detect(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        List<PlateDetectHit> hits = new ArrayList<>(boxes.size());
        for (PredictRectangle box : boxes) {
            byte[] plate = ImageCropUtils.crop(imageData, box);
            String plateText = "";
            String plateColor = "";
            if (recognizer != null) {
                PlateResult result = recognizer.recognizePlate(plate);
                if (result != null) {
                    plateText = result.plateNo();
                    plateColor = result.plateColor();
                }
            }
            hits.add(new PlateDetectHit(box, plate, plateText, plateColor));
        }
        return hits;
    }

    /**
     * 仅检测车牌边界框（不识别）。
     *
     * @param imageData 图像数据
     * @return 检测框列表
     */
    public List<PredictRectangle> detectBoxes(byte[] imageData) {
        List<PredictRectangle> boxes = detector.detect(imageData);
        return boxes == null ? List.of() : boxes;
    }

    /**
     * 获取车牌检测器。
     *
     * @return PlateDetector
     */
    public PlateDetector detector() {
        return detector;
    }

    /**
     * 获取车牌识别器。
     *
     * @return LicensePlateRecognizer
     */
    public LicensePlateRecognizer recognizer() {
        return recognizer;
    }
}