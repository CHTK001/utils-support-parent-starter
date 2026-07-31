package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

/**
 * 图像生成器。
 * <p>按类别 ID 或种子生成图像，适用于 BigGAN 等生成模型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageGenerator {

    /**
     * 创建图像生成器。
     *
     * @param name 模型名称
     * @return 生成器
     */
    static ImageGenerator create(String name) {
        return new DefaultImageGenerator(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建图像生成器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 生成器
     */
    static ImageGenerator create(String name, ModelSetting setting) {
        return new DefaultImageGenerator(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default ImageGenerator modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default ImageGenerator device(String device) {
        return this;
    }

    /**
     * 按类别 ID 生成图像。
     *
     * @param classId 类别 ID
     * @return 图像字节数组
     */
    byte[] generate(long classId);
}

/**
 * 默认图像生成器。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageGenerator implements ImageGenerator {

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

    DefaultImageGenerator(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public ImageGenerator modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public ImageGenerator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] generate(long classId) {
        ITranslator<Long, byte[]> t =
                (ITranslator<Long, byte[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(classId);
    }
}
