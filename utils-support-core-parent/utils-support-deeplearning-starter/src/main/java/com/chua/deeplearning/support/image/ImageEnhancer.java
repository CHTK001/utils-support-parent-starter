package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

/**
 * 图像增强器。
 * <p>
 * 覆盖超分、风格迁移、上色、去模糊等 Image→Image 能力。
 * 输入/输出均为图像字节数组。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageEnhancer {

    /**
     * 创建图像增强器。
     *
     * @param name 模型名称
     * @return 增强器
     */
    static ImageEnhancer create(String name) {
        return new DefaultImageEnhancer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建图像增强器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 增强器
     */
    static ImageEnhancer create(String name, ModelSetting setting) {
        return new DefaultImageEnhancer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default ImageEnhancer modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default ImageEnhancer device(String device) {
        return this;
    }

    /**
     * 增强图像。
     *
     * @param imageData 输入图像字节数组
     * @return 输出图像字节数组
     */
    byte[] enhance(byte[] imageData);
}

/**
 * 默认图像增强器。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageEnhancer implements ImageEnhancer {

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
    private String device = "cpu";

    DefaultImageEnhancer(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public ImageEnhancer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public ImageEnhancer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] enhance(byte[] imageData) {
        ITranslator<byte[], byte[]> t =
                (ITranslator<byte[], byte[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
