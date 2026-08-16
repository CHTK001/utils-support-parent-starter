package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;

/**
 * 图像描述（Image Captioning）能力接口。
 *
 * <p>输入图像，输出对图像内容的文字描述。
 * 底层基于 ViT-GPT2 图像编码 + 文本解码模型（如 vit-gpt2-captioning）。</p>
 *
 * <pre>{@code
 * String caption = ImageCaptioning.create("vit-gpt2-captioning").describe(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageCaptioning {

    /**
     * 创建图像描述器。
     *
     * @param name 模型名称
     * @return 描述器
     */
    static ImageCaptioning create(String name) {
        return new DefaultImageCaptioning(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageCaptioning.class);
    }

    /**
     * 创建图像描述器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 描述器
     */
    static ImageCaptioning create(String name, ModelSetting setting) {
        return new DefaultImageCaptioning(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 描述图像内容。
     *
     * @param imageData 图像字节
     * @return 图像描述文本
     */
    String describe(byte[] imageData);
}

/**
 * 默认图像描述器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageCaptioning implements ImageCaptioning {

    /**
     * 默认模型名称
     */
    private static final String DEFAULT_MODEL = "vit-gpt2-captioning";

    /**
     * 识别引擎
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称
     */
    private final String modelName;

    /**
     * 构造默认图像描述器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultImageCaptioning(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName != null ? modelName : DEFAULT_MODEL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String describe(byte[] imageData) {
        ITranslator<byte[], String> t = engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
