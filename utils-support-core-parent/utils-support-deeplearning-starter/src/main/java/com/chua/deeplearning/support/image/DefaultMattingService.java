package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 默认抠图服务：经统一推理引擎调用抠图模型，输出 PNG 字节。
 *
 * <p>含 alpha 通道的结果直接以 PNG 编码保留透明度；不含 alpha 的结果
 * 经 OpenCV Mat 编码返回。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultMattingService implements MattingService {

    /** 缺省模型。 */
    private static final String DEFAULT_MODEL = "modnet";

    /** 推理引擎。 */
    private final IdentificationEngine engine;

    /** 模型名称。 */
    private final String modelName;

    /**
     * 构造默认抠图服务。
     *
     * @param engine    推理引擎
     * @param modelName 模型名称（空时使用缺省模型）
     * @param setting   模型设置（保留参数位）
     */
    DefaultMattingService(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName != null ? modelName : DEFAULT_MODEL;
    }

    @Override
    public byte[] matte(byte[] imageData) {
        ITranslator<Object, Object> translator = engine.get(modelName, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("未找到抠图模型: " + modelName);
        }
        Object result = translator.translate(imageData);
        BufferedImage image = toBufferedImage(result);
        if (image == null) {
            throw new IllegalStateException("不支持的抠图结果类型: "
                    + modelName + " -> " + String.valueOf(result));
        }
        if (image.getColorModel().hasAlpha()) {
            // 含 alpha：PNG 编码保留透明通道
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(image, "PNG", baos);
                return baos.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("PNG 编码失败: " + modelName, e);
            }
        }
        return ImageUtils.encode(ImageUtils.toMat(image));
    }

    /**
     * 将推理结果转换为 BufferedImage。
     *
     * @param result 推理结果
     * @return BufferedImage（无法转换时为 null）
     */
    private static BufferedImage toBufferedImage(Object result) {
        if (result instanceof BufferedImage image) {
            return image;
        }
        if (result instanceof ai.djl.modality.cv.Image image
                && image.getWrappedImage() instanceof BufferedImage buffered) {
            return buffered;
        }
        if (result instanceof byte[] bytes) {
            try {
                return ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (Exception ignore) {
                // 非 PNG/JPEG 字节，按 null 处理
            }
        }
        return null;
    }
}
