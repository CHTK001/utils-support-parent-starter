package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

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

    /**
      * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ImageGenerator create(String provider, String apiKey) {
        return ServiceProvider.of(ImageGenerator.class)
                .getNewExtension(provider, apiKey);
    }

    /**
      * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default ImageGenerator provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ImageGenerator model(String model) {
        return this;
    }

    /**
     * 创建
     *
     * @param name 名称
     * @return 创建的结果
     */
    static ImageGenerator create(String name) {
        return new DefaultImageGenerator(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * <p>按能力接口从 {@link com.chua.deeplearning.support.engine.ModelRegistry} 枚举
     * 全部已注册模型，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 标识 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageGenerator.class);
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
      * 按类别 标识 生成图像。
     *
     * @param classId 类别 标识
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
    /** 设置 */
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
    /** 模型路径 */
    public ImageGenerator modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public ImageGenerator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * Generate
     *
     * @param classId 类标识
     * @return generate的结果
     */
    public byte[] generate(long classId) {
        ITranslator<Long, byte[]> t =
                (ITranslator<Long, byte[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(classId);
    }
}
