package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 图像抠图（matting）能力接口。
 *
 * <p>输入图像，输出透明背景 PNG。底层基于 DJL onnxruntime-engine 的抠图模型（如 modnet），
 * OpenCV 预处理 + djl-onnx 推理。</p>
 *
 * <pre>{@code
 * byte[] png = MattingService.create("modnet").matte(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MattingService {

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static MattingService create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(MattingService.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default MattingService provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default MattingService model(String model) {
        return this;
    }


    /**
     * 创建抠图服务。
     *
     * @param name 模型名称
     * @return 抠图服务
     */
    static MattingService create(String name) {
        return new DefaultMattingService(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建抠图服务。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 抠图服务
     */
    static MattingService create(String name, ModelSetting setting) {
        return new DefaultMattingService(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 抠图：输入图像，输出透明背景 PNG。
     *
     * @param imageData 图像字节
     * @return 透明 PNG 字节
     */
    byte[] matte(byte[] imageData);
}

/**
 * 默认抠图服务实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultMattingService implements MattingService {

    /**
     * 默认模型名称
     */
    private static final String DEFAULT_MODEL = "modnet";

    /**
     * 识别引擎
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称
     */
    private final String modelName;

    /**
     * 构造默认抠图服务。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultMattingService(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName != null ? modelName : DEFAULT_MODEL;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** Matte */
    public byte[] matte(byte[] imageData) {
        ITranslator<Object, Object> t = engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = t.translate(imageData);
        BufferedImage image = toBufferedImage(result);
        if (image != null) {
            return ImageUtils.encode(ImageUtils.toMat(image));
        }
        throw new IllegalStateException("模型输出不是图像: " + modelName + " -> " + result);
    }

    /**
     * 将模型输出转换为 BufferedImage。
     *
     * @param result 模型输出
     * @return BufferedImage，无法转换返回 null
     */
    private static BufferedImage toBufferedImage(Object result) {
        if (result instanceof BufferedImage image) {
            return image;
        }
        if (result instanceof ai.djl.modality.cv.Image djlImage) {
            Object wrapped = djlImage.getWrappedImage();
            if (wrapped instanceof BufferedImage image) {
                return image;
            }
        }
        return null;
    }
}
