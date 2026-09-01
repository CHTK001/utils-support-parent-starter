package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 多模态理解（VLM）能力接口。
 *
 * <p>输入图像 + 任务提示符，输出理解结果文本。
 * 支持图像描述、OCR、物体检测等任务（如 Florence-2、BLIP-2 等）。</p>
 *
 * <pre>{@code
 * String result = VlmUnderstanding.create("florence2")
 *     .understand(imageBytes, "<CAPTION>");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VlmUnderstanding {

    /**
     * 通过 SPI 创建实例。
     */
    static VlmUnderstanding create(String provider, String apiKey) {
        return ServiceProvider.of(VlmUnderstanding.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     */
    default VlmUnderstanding provider(String provider) { return this; }

    /**
     * 设置模型名称。
     */
    default VlmUnderstanding model(String model) { return this; }

    /**
     * 创建实例（默认模型）。
     */
    static VlmUnderstanding create(String name) {
        return new DefaultVlmUnderstanding(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建实例（指定配置）。
     */
    static VlmUnderstanding create(String name, ModelSetting setting) {
        return new DefaultVlmUnderstanding(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 查询该能力下全部可用模型。
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(VlmUnderstanding.class);
    }

    /**
     * 理解图像内容。
     *
     * @param imageData  图像字节
     * @param taskPrompt 任务提示符（如 "<CAPTION>"、"<OCR>"、"<OD>"）
     * @return 理解结果文本
     */
    String understand(byte[] imageData, String taskPrompt);
}

/**
 * 默认多模态理解实现。
 */
class DefaultVlmUnderstanding implements VlmUnderstanding {

    private static final String DEFAULT_MODEL = "florence2";

    private final IdentificationEngine engine;
    private final String modelName;

    DefaultVlmUnderstanding(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName != null ? modelName : DEFAULT_MODEL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String understand(byte[] imageData, String taskPrompt) {
        ITranslator<Object[], String> t = (ITranslator<Object[], String>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("VLM 模型未注册: " + modelName);
        }
        return t.translate(new Object[]{imageData, taskPrompt});
    }
}