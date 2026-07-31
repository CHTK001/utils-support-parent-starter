package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.utils.ImageCropUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * OCR 组合业务：检测 → 裁剪 → 识别（可选方向分类后续由流水线扩展）。
 *
 * <pre>{@code
 * OcrEngine ocr = OcrEngine.builder()
 *         .detector(ImageDetector.create("paddle-ocr-det"))
 *         .recognizer(OcrRecognizer.create("paddle-ocr-rec"))
 *         .build();
 * List&lt;OcrResult&gt; results = ocr.recognizeDetail(imageBytes);
 * String text = ocr.recognize(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OcrEngine {

    /**
     * 文字检测器。
     */
    private final ImageDetector detector;

    /**
     * 文字识别器。
     */
    private final OcrRecognizer recognizer;

    /**
     * 是否按阅读顺序排序（先 y 后 x）。
     */
    private final boolean sortReadingOrder;

    /**
     * 构造。
     *
     * @param detector         检测
     * @param recognizer       识别
     * @param sortReadingOrder 阅读序
     */
    public OcrEngine(ImageDetector detector, OcrRecognizer recognizer, boolean sortReadingOrder) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.sortReadingOrder = sortReadingOrder;
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

        private ImageDetector detector;
        private OcrRecognizer recognizer;
        private boolean sortReadingOrder = true;

        /**
         * 设置检测器。
         *
         * @param detector 检测
         * @return this
         */
        public Builder detector(ImageDetector detector) {
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
            this.detector = ImageDetector.create(modelId);
            return this;
        }

        /**
         * 设置识别器。
         *
         * @param recognizer 识别
         * @return this
         */
        public Builder recognizer(OcrRecognizer recognizer) {
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
            this.recognizer = OcrRecognizer.create(modelId);
            return this;
        }

        /**
         * 是否按阅读顺序排序结果。
         *
         * @param sortReadingOrder true 排序
         * @return this
         */
        public Builder sortReadingOrder(boolean sortReadingOrder) {
            this.sortReadingOrder = sortReadingOrder;
            return this;
        }

        /**
         * 构建。
         *
         * @return OcrEngine
         */
        public OcrEngine build() {
            return new OcrEngine(detector, recognizer, sortReadingOrder);
        }
    }

    /**
     * 识别整图文本（拼接）。
     *
     * @param imageData 图片
     * @return 文本
     */
    public String recognize(byte[] imageData) {
        return recognizeDetail(imageData).stream()
                .map(OcrResult::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 详细识别：检测框 + 文本。
     *
     * @param imageData 图片
     * @return 结果列表
     */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        List<DetectionInfo> boxes = detector.detect(imageData);
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        List<DetectionInfo> ordered = sortReadingOrder
                ? boxes.stream()
                .sorted(Comparator
                        .comparingDouble(DetectionInfo::y)
                        .thenComparingDouble(DetectionInfo::x))
                .toList()
                : boxes;
        List<OcrResult> results = new ArrayList<>(ordered.size());
        for (DetectionInfo box : ordered) {
            byte[] crop = ImageCropUtils.crop(imageData, box);
            String text = recognizer.recognize(crop);
            results.add(new OcrResult(
                    text == null ? "" : text,
                    box.confidence(),
                    new com.chua.deeplearning.support.model.PredictRectangle(
                            box.x(), box.y(), box.width(), box.height(),
                            box.confidence(), 0, box.label())));
        }
        return results;
    }

    /**
     * 检测器。
     *
     * @return ImageDetector
     */
    public ImageDetector detector() {
        return detector;
    }

    /**
     * 识别器。
     *
     * @return OcrRecognizer
     */
    public OcrRecognizer recognizer() {
        return recognizer;
    }
}
