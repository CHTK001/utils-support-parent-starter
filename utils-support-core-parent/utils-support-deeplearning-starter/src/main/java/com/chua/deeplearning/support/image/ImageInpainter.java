package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.common.support.spi.ServiceProvider;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 图像修复器（Inpainting）。
 *
 * <p>覆盖去水印、去杂物、划痕修复、物体擦除等带掩码的 镜像→镜像 能力。
 * 输入为图像字节数组 + 修复掩码（alpha 通道，255=需修复区域，0=保留区域），
 * 输出为修复后的图像字节数组。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageInpainter {

    /**
     * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ImageInpainter create(String provider, String apiKey) {
        return ServiceProvider.of(ImageInpainter.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 创建
     *
     * @param name 名称
     * @return 创建的结果
     */
    static ImageInpainter create(String name) {
        return new DefaultImageInpainter(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建图像修复器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 修复器
     */
    static ImageInpainter create(String name, ModelSetting setting) {
        return new DefaultImageInpainter(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default ImageInpainter provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ImageInpainter model(String model) {
        return this;
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default ImageInpainter modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default ImageInpainter device(String device) {
        return this;
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageInpainter.class);
    }

    /**
     * 修复图像。
     *
     * @param imageData 输入图像字节数组（RGBA，alpha 通道为修复掩码，255=需修复）
     * @return 修复后图像字节数组
     */
    byte[] inpaint(byte[] imageData);
}

/**
 * 默认图像修复器。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageInpainter implements ImageInpainter {

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

    DefaultImageInpainter(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
    }

    @Override
    /**
     * 修复
     *
     * @param imageData 镜像数据
     * @return 修复的结果
     */
    @SuppressWarnings("unchecked")
    public byte[] inpaint(byte[] imageData) {
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
                throw new IllegalStateException("修复结果图像编码失败", e);
            }
        }
        throw new IllegalStateException("修复模型输出类型不支持: " + (out == null ? "null" : out.getClass().getName()));
    }
}
