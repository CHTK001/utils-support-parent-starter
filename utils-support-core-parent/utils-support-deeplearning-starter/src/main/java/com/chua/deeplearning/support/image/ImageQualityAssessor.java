package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 图像质量评估器。
 * <p>评估模糊度、亮度、对比度等通用图像质量指标。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageQualityAssessor {

    /**
     * 创建图像质量评估器。
     *
     * @param name 模型名称
     * @return 评估器
     */

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ImageQualityAssessor create(String provider, String apiKey) {
        return ServiceProvider.of(ImageQualityAssessor.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default ImageQualityAssessor provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ImageQualityAssessor model(String model) {
        return this;
    }

    static ImageQualityAssessor create(String name) {
        return new DefaultImageQualityAssessor(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * <p>按能力接口从 {@link com.chua.deeplearning.support.engine.ModelRegistry} 枚举
     * 全部已注册模型，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 ID 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageQualityAssessor.class);
    }


    /**
     * 创建图像质量评估器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 评估器
     */
    static ImageQualityAssessor create(String name, ModelSetting setting) {
        return new DefaultImageQualityAssessor(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模糊阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    ImageQualityAssessor blurThreshold(double threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    ImageQualityAssessor modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    ImageQualityAssessor device(String device);

    /**
     * 评估图像质量。
     *
     * @param imageData 图像字节数组
     * @return 质量信息
     */
    ImageQualityInfo assess(byte[] imageData);

    /**
     * 是否通过质量检测。
     *
     * @param imageData 图像字节数组
     * @return true 表示质量合格
     */
    default boolean isAcceptable(byte[] imageData) {
        ImageQualityInfo info = assess(imageData);
        return info.sharpnessOk() && info.brightnessOk();
    }
}

/**
 * 默认图像质量评估器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageQualityAssessor implements ImageQualityAssessor {

    /**
     * 默认模糊阈值。
     */
    private static final double DEFAULT_BLUR_THRESHOLD = 100.0;

    /**
     * 默认运行设备（CPU）。
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 识别引擎。
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称。
     */
    private final String modelName;

    /**
     * 模型配置。
     */
    @SuppressWarnings("unused")
    /** 设置 */
    private final ModelSetting setting;

    /**
     * 模糊阈值。
     */
    private double blurThreshold = DEFAULT_BLUR_THRESHOLD;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 构造默认图像质量评估器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultImageQualityAssessor(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public ImageQualityAssessor blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    public ImageQualityAssessor modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public ImageQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ImageQualityInfo assess(byte[] imageData) {
        ITranslator<byte[], ImageQualityInfo> t =
                (ITranslator<byte[], ImageQualityInfo>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
