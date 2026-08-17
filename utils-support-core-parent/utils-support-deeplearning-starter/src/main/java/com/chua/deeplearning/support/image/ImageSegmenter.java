package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 图像分割器，对图像进行语义分割或实例分割。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageSegmenter {

    /**
     * 创建图像分割器。
     *
     * @param name 模型名称
     * @return 分割器
     */

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ImageSegmenter create(String provider, String apiKey) {
        return ServiceProvider.of(ImageSegmenter.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default ImageSegmenter provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ImageSegmenter model(String model) {
        return this;
    }

    static ImageSegmenter create(String name) {
        return new DefaultImageSegmenter(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageSegmenter.class);
    }


    /**
     * 创建图像分割器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 分割器
     */
    static ImageSegmenter create(String name, ModelSetting setting) {
        return new DefaultImageSegmenter(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    ImageSegmenter modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    ImageSegmenter device(String device);

    /**
     * 分割图像。
     *
     * @param imageData 图像数据
     * @return 分割结果（掩码图像数据）
     */
    byte[] segment(byte[] imageData);

    /**
     * 分割图像，指定目标类别。
     *
     * @param imageData   图像数据
     * @param targetClass 目标类别
     * @return 分割结果（掩码图像数据）
     */
    byte[] segment(byte[] imageData, int targetClass);
}

/**
 * 默认图像分割器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageSegmenter implements ImageSegmenter {

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
     * 构造默认图像分割器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultImageSegmenter(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public ImageSegmenter modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public ImageSegmenter device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] segment(byte[] imageData) {
        ITranslator<byte[], byte[]> t =
                (ITranslator<byte[], byte[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] segment(byte[] imageData, int targetClass) {
        return segment(imageData);
    }
}
