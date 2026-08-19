package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import com.chua.common.support.spi.ServiceProvider;

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

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ImageEnhancer create(String provider, String apiKey) {
        return ServiceProvider.of(ImageEnhancer.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default ImageEnhancer provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ImageEnhancer model(String model) {
        return this;
    }

    /** 创建 */
    static ImageEnhancer create(String name) {
        return new DefaultImageEnhancer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageEnhancer.class);
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
    /** ModelPath */
    public ImageEnhancer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public ImageEnhancer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** Enhance */
    public byte[] enhance(byte[] imageData) {
        ITranslator<byte[], Object> t =
                (ITranslator<byte[], Object>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object out = t.translate(imageData);
        if (out instanceof byte[] bytes) {
            return bytes;
        }
        if (out instanceof BufferedImage img) {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                ImageIO.write(img, "png", bos);
                return bos.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("增强结果图像编码失败", e);
            }
        }
        throw new IllegalStateException("增强模型输出类型不支持: " + (out == null ? "null" : out.getClass().getName()));
    }
}
