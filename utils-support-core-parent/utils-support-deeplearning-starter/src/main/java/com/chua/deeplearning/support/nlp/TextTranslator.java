package com.chua.deeplearning.support.nlp;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
* 文本翻译器。
* <p>输入源语言文本，输出目标语言文本。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface TextTranslator {

    /**
    * 通过 SPI 创建实例（提供者="onnx" 等）。
    *
    * @param provider 提供者 名称
    * @param apiKey   API 密钥（本地引擎可空）
    * @return 实例
    */
    static TextTranslator create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(TextTranslator.class)
                .getNewExtension(provider, apiKey);
    }

    /**
    * 设置 提供者。
    *
    * @param provider 提供者 名称
    * @return this
    */
    default TextTranslator provider(String provider) {
        return this;
    }

    /**
    * 设置模型名称。
    *
    * @param model 模型名称
    * @return this
    */
    default TextTranslator model(String model) {
        return this;
    }


    /**
    * 创建文本翻译器。
    *
    * @param name 模型名称
    * @return 翻译器
    */
    static TextTranslator create(String name) {
        return new DefaultTextTranslator(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.nlp.TextTranslator.class);
    }


    /**
    * 创建文本翻译器。
    *
    * @param name    模型名称
    * @param setting 模型配置
    * @return 翻译器
    */
    static TextTranslator create(String name, ModelSetting setting) {
        return new DefaultTextTranslator(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
    * 设置模型路径。
    *
    * @param path 路径
    * @return this
    */
    default TextTranslator modelPath(String path) {
        return this;
    }

    /**
    * 设置运行设备。
    *
    * @param device 设备
    * @return this
    */
    default TextTranslator device(String device) {
        return this;
    }

    /**
    * 翻译文本。
    *
    * @param text 源文本
    * @return 译文
    */
    String translate(String text);
}

/**
* 默认文本翻译器。
*
* @author CH
* @since 4.0.0.42
 */
class DefaultTextTranslator implements TextTranslator {

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

    /**
     * 构造方法，创建 Default文本Translator 实例。
     *
     * @param engine 引擎，不允许为 null
     * @param modelName 模型名称，不允许为 null
     * @param setting 方法入参 setting
     */
    DefaultTextTranslator(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public TextTranslator modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public TextTranslator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * Translate
    *
    * @param text 文本
    * @return translate的结果
    */
    public String translate(String text) {
        ITranslator<String, String> t =
                (ITranslator<String, String>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(text);
    }
}
