package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

/**
 * 图像抠图（matting）能力接口。
 *
 * <p>输入图像，输出透明背景 PNG。底层基于 DJL onnxruntime-engine 的抠图模型（如 modnet），
   * 打开cv 预处理 + djl-onnx 推理。</p>
 *
 * <pre>{@code
 * byte[] png = MattingService.create("modnet").matte(imageBytes);
 * }</pre> * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MattingService {

    /**
      * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static MattingService create(String provider, String apiKey) {
        return ServiceProvider.of(MattingService.class)
                .getNewExtension(provider, apiKey);
    }

    /**
      * 设置 提供者。
     *
     * @param provider 提供者 名称
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
