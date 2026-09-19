package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

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
    /**
     * Matte
     *
     * @param imageData 镜像数据
     * @return matte的结果
     */
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
     * 将模型输出转换为 缓冲镜像。
     *
     * @param result 模型输出
     * @return BufferedImage，无法转换返回 空
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
        if (result instanceof byte[] bytes) {
            try {
                java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
                BufferedImage bi = javax.imageio.ImageIO.read(bis);
                if (bi != null) {
                    return bi;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
