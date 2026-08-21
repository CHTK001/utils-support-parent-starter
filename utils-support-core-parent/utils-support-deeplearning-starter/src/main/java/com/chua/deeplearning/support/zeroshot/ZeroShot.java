package com.chua.deeplearning.support.zeroshot;

import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.ImageSegmenter;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零样本 AI 统一门面（Zero-Shot AI Unified Facade）。
 *
 * <p>封装零样本分类、零样本检测、零样本分割的创建和调用，
 * 提供一行代码即可完成零样本推理的便捷 API。</p>
 *
 * <p>底层复用 {@link ImageClassifier}、{@link ImageDetector}、{@link ImageSegmenter} 标准接口，
 * 模型通过 {@code OnnxModelRegistrar} 注册，支持自动下载和嵌入式 jar 加载。</p>
 *
 * <h3>零样本分类（Zero-Shot Classification）</h3>
 * <pre>{@code
 * // 一行代码：输入图片，返回分类结果（使用默认候选标签）
 * String result = ZeroShot.classify(imageData);
 *
 * // 自定义模型
 * String result = ZeroShot.classifier("mobileclip-zero-shot")
 *         .classify(imageData);
 * }</pre>
 *
 * <h3>零样本检测（Zero-Shot Detection）</h3>
 * <pre>{@code
 * // 一行代码：输入图片，返回检测框
 * List<DetectionInfo> result = ZeroShot.detect(imageData);
 *
 * // 自定义模型 + 阈值
 * List<DetectionInfo> result = ZeroShot.detector("yolov8s-world")
 *         .threshold(0.2f)
 *         .detect(imageData);
 * }</pre>
 *
 * <h3>零样本分割（Zero-Shot Segmentation）</h3>
 * <pre>{@code
 * // 一行代码：输入图片，返回分割掩码
 * byte[] mask = ZeroShot.segment(imageData);
 * }</pre>
 *
 * <h3>可用零样本模型</h3>
 * <ul>
 *   <li><b>分类</b>：siglip-zero-shot-classification, clip-vit-zero-shot, mobileclip-zero-shot</li>
 *   <li><b>检测</b>：yolov8s-world, yolov8m-world, yolov8l-world, owlv2-zero-shot-detector, grounding-dino</li>
 *   <li><b>分割</b>：clipseg-zero-shot</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ZeroShot {

    /** 默认零样本分类模型 */
    private static final String DEFAULT_CLASSIFIER = "siglip-zero-shot-classification";
    /** 默认零样本检测模型 */
    private static final String DEFAULT_DETECTOR = "yolov8s-world";
    /** 默认零样本分割模型 */
    private static final String DEFAULT_SEGMENTER = "clipseg-zero-shot";

    /** 私有构造 */
    private ZeroShot() {
    }

    // ==================== 一行代码便捷方法 ====================

    /**
     * 零样本图像分类：输入图片，返回分类结果。
     * <p>使用默认模型 {@code siglip-zero-shot-classification}。</p>
     *
     * @param imageData 图片字节数组（JPEG/PNG）
     * @return 分类结果字符串（如 "person", "car"）
     */
    public static String classify(byte[] imageData) {
        return ImageClassifier.create(DEFAULT_CLASSIFIER).classify(imageData);
    }

    /**
     * 零样本图像检测：输入图片，返回检测框。
     * <p>使用默认模型 {@code yolov8s-world}。</p>
     *
     * @param imageData 图片字节数组（JPEG/PNG）
     * @return 检测结果列表
     */
    public static List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(DEFAULT_DETECTOR).detect(imageData);
    }

    /**
     * 零样本图像分割：输入图片，返回分割掩码。
     * <p>使用默认模型 {@code clipseg-zero-shot}。</p>
     *
     * @param imageData 图片字节数组（JPEG/PNG）
     * @return 分割掩码（二值图像字节数组）
     */
    public static byte[] segment(byte[] imageData) {
        return ImageSegmenter.create(DEFAULT_SEGMENTER).segment(imageData);
    }

    // ==================== 流式构建器工厂方法 ====================

    /**
     * 创建零样本分类器构建器（指定模型）。
     *
     * @param modelId 模型 ID（如 "siglip-zero-shot-classification", "mobileclip-zero-shot"）
     * @return 分类器构建器
     */
    public static ClassifierBuilder classifier(String modelId) {
        return new ClassifierBuilder(modelId);
    }

    /**
     * 创建零样本检测器构建器（指定模型）。
     *
     * @param modelId 模型 ID（如 "yolov8s-world", "owlv2-zero-shot-detector"）
     * @return 检测器构建器
     */
    public static DetectorBuilder detector(String modelId) {
        return new DetectorBuilder(modelId);
    }

    /**
     * 创建零样本分割器构建器（指定模型）。
     *
     * @param modelId 模型 ID（如 "clipseg-zero-shot"）
     * @return 分割器构建器
     */
    public static SegmenterBuilder segmenter(String modelId) {
        return new SegmenterBuilder(modelId);
    }

    // ==================== 模型列表方法 ====================

    /**
     * 列出所有可用的零样本分类模型。
     *
     * @return 模型 ID 列表
     */
    public static List<String> listClassifiers() {
        return ImageClassifier.listModels().stream()
                .filter(id -> id.contains("zero-shot") || id.contains("clip-vit") || id.contains("siglip") || id.contains("mobileclip"))
                .toList();
    }

    /**
     * 列出所有可用的零样本检测模型。
     *
     * @return 模型 ID 列表
     */
    public static List<String> listDetectors() {
        return ImageDetector.listModels().stream()
                .filter(id -> id.contains("zero-shot") || id.contains("world") || id.contains("owlv2") || id.contains("grounding"))
                .toList();
    }

    /**
     * 列出所有可用的零样本分割模型。
     *
     * @return 模型 ID 列表
     */
    public static List<String> listSegmenters() {
        return ImageSegmenter.listModels().stream()
                .filter(id -> id.contains("zero-shot") || id.contains("clipseg"))
                .toList();
    }

    /**
     * 列出所有零样本模型（分类 + 检测 + 分割）。
     *
     * @return 模型 ID → 类型 映射
     */
    public static Map<String, String> listAll() {
        var result = new LinkedHashMap<String, String>();
        listClassifiers().forEach(id -> result.put(id, "classification"));
        listDetectors().forEach(id -> result.put(id, "detection"));
        listSegmenters().forEach(id -> result.put(id, "segmentation"));
        return result;
    }

    // ==================== 内部构建器 ====================

    /**
     * 零样本分类器构建器。
     */
    public static final class ClassifierBuilder {
        private final String modelId;
        private String device = "cpu";

        ClassifierBuilder(String modelId) {
            this.modelId = modelId;
        }

        /** 设置运行设备 */
        public ClassifierBuilder device(String device) {
            this.device = device;
            return this;
        }

        /** 执行分类（返回分类结果字符串） */
        public String classify(byte[] imageData) {
            return ImageClassifier.create(modelId).device(device).classify(imageData);
        }
    }

    /**
     * 零样本检测器构建器。
     */
    public static final class DetectorBuilder {
        private final String modelId;
        private String device = "cpu";
        private float threshold = 0.1f;

        DetectorBuilder(String modelId) {
            this.modelId = modelId;
        }

        /** 设置检测阈值 */
        public DetectorBuilder threshold(float threshold) {
            this.threshold = threshold;
            return this;
        }

        /** 设置运行设备 */
        public DetectorBuilder device(String device) {
            this.device = device;
            return this;
        }

        /** 执行检测 */
        public List<DetectionInfo> detect(byte[] imageData) {
            return ImageDetector.create(modelId)
                    .device(device)
                    .threshold(threshold)
                    .detect(imageData);
        }
    }

    /**
     * 零样本分割器构建器。
     */
    public static final class SegmenterBuilder {
        private final String modelId;
        private String device = "cpu";

        SegmenterBuilder(String modelId) {
            this.modelId = modelId;
        }

        /** 设置运行设备 */
        public SegmenterBuilder device(String device) {
            this.device = device;
            return this;
        }

        /** 执行分割 */
        public byte[] segment(byte[] imageData) {
            return ImageSegmenter.create(modelId)
                    .device(device)
                    .segment(imageData);
        }
    }
}
