package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 图片清晰度检测门面。
 *
 * <p>专注清晰度评估单一职责：评估模糊度/亮度/对比度并给出是否清晰结论。
 * 若需要增强图片，请使用独立的 {@link ImageEnhancer} 门面。</p>
 *
 * <p>嵌入式场景推荐 OpenCV 后端（Laplacian 方差，无需下载模型文件）。</p>
 *
 * <h2>用法示例</h2>
 * <pre>{@code
 * ImageClarityDetector detector = ImageClarityDetector.create("opencv", "");
 * ImageQualityInfo info = detector.blurThreshold(100.0).assess(imageBytes);
 * if (info.sharpnessOk()) {
 *     // 图片清晰
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageClarityDetector {

    /**
     * 通过 SPI 创建实例（provider="opencv"/"onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ImageClarityDetector create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(ImageClarityDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default ImageClarityDetector provider(String provider) {
        return this;
    }

    /**
     * 设置清晰度检测模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ImageClarityDetector model(String model) {
        return this;
    }

    /**
     * 通过引擎创建实例（引擎自动发现已注册的模型）。
     *
     * @param name 模型名称（如 "opencv-image-quality"、"nima"）
     * @return 实例
     */
    static ImageClarityDetector create(String name) {
        return new DefaultImageClarityDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * @return 模型 ID 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry
                .getModelIdsByCapability(ImageQualityAssessor.class);
    }

    /**
     * 通过引擎创建实例（指定模型配置）。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 实例
     */
    static ImageClarityDetector create(String name, ModelSetting setting) {
        return new DefaultImageClarityDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模糊度阈值。
     *
     * @param threshold 阈值（Laplacian 方差低于该值视为模糊，推荐 80~150）
     * @return this
     */
    ImageClarityDetector blurThreshold(double threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    ImageClarityDetector modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备（"cpu"/"cuda"）
     * @return this
     */
    ImageClarityDetector device(String device);

    /**
     * 评估图片清晰度。
     *
     * @param imageData 图片字节数组
     * @return 质量信息（含模糊度/亮度/对比度及是否清晰）
     */
    ImageQualityInfo assess(byte[] imageData);

    /**
     * 图片是否清晰。
     *
     * @param imageData 图片字节数组
     * @return true 表示清晰度合格
     */
    default boolean isAcceptable(byte[] imageData) {
        return assess(imageData).sharpnessOk();
    }
}

/**
 * 默认图片清晰度检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageClarityDetector implements ImageClarityDetector {

    /**
     * 默认模糊阈值
     */
    private static final double DEFAULT_BLUR_THRESHOLD = 100.0;

    /**
     * 默认设备
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 识别引擎
     */
    private final IdentificationEngine engine;

    /**
     * 清晰度检测模型名称
     */
    private final String modelName;

    /**
     * 模型配置
     */
    @SuppressWarnings("unused")
    private final ModelSetting setting;

    /**
     * 模糊度阈值
     */
    private double blurThreshold = DEFAULT_BLUR_THRESHOLD;

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 运行设备
     */
    private String device = DEFAULT_DEVICE;

    DefaultImageClarityDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
        if (setting.getModelPath() != null) {
            this.modelPath = setting.getModelPath();
        }
        if (setting.getDevice() != null) {
            this.device = setting.getDevice();
        }
    }

    @Override
    public ImageClarityDetector blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    public ImageClarityDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public ImageClarityDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ImageQualityInfo assess(byte[] imageData) {
        ITranslator<byte[], ImageQualityInfo> t =
                (ITranslator<byte[], ImageQualityInfo>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("清晰度检测模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
