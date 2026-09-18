package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 图像修复器（Inpainting）。
 * <p>
 * 覆盖去水印、去杂物、划痕修复、物体擦除等带掩码的 镜像→镜像 能力。
 * 输入为图像字节数组（RGBA，Alpha 通道为修复掩码，255=需修复区域，0=保留区域），
 * 输出为修复后的图像字节数组。
 * </p>
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
    * 查询该能力下全部可用模型。
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
    private final ModelSetting setting;

    /**
     * 构造方法，创建 DefaultImageInpainter 实例。
     *
     * @param engine 引擎，不允许为 null
     * @param modelName 模型名称，不允许为 null
     * @param setting 方法入参 setting
     */
    DefaultImageInpainter(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * 修复
    *
    * @param imageData 镜像数据
    * @return 修复的结果
    */
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
