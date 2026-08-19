package com.chua.deeplearning.support.feature;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 特征提取器，从图像或文本中提取特征向量。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FeatureExtractor {

    /**
     * 创建特征提取器。
     *
     * @param name 模型名称
     * @return 提取器
     */

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static FeatureExtractor create(String provider, String apiKey) {
        return ServiceProvider.of(FeatureExtractor.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default FeatureExtractor provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default FeatureExtractor model(String model) {
        return this;
    }

    /** 创建 */
    static FeatureExtractor create(String name) {
        return new DefaultFeatureExtractor(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.feature.FeatureExtractor.class);
    }


    /**
     * 创建特征提取器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 提取器
     */
    static FeatureExtractor create(String name, ModelSetting setting) {
        return new DefaultFeatureExtractor(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    FeatureExtractor modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    FeatureExtractor device(String device);

    /**
     * 设置是否归一化特征。
     *
     * @param normalize 是否归一化
     * @return this
     */
    FeatureExtractor normalize(boolean normalize);

    /**
     * 从图像中提取特征。
     *
     * @param imageData 图像数据
     * @return 特征向量
     */
    float[] extract(byte[] imageData);

    /**
     * 从文本中提取特征。
     *
     * @param text 文本
     * @return 特征向量
     */
    float[] extract(String text);
}

/**
 * 默认特征提取器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultFeatureExtractor implements FeatureExtractor {

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
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 是否归一化。
     */
    private boolean normalize = true;

    /**
     * 构造默认特征提取器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultFeatureExtractor(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    /** ModelPath */
    public FeatureExtractor modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public FeatureExtractor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Normalize */
    public FeatureExtractor normalize(boolean normalize) {
        this.normalize = normalize;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** Extract */
    public float[] extract(byte[] imageData) {
        ITranslator<byte[], float[]> t =
                (ITranslator<byte[], float[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** Extract */
    public float[] extract(String text) {
        ITranslator<String, float[]> t =
                (ITranslator<String, float[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(text);
    }
}
