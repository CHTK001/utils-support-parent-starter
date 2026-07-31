package com.chua.deeplearning.support.nlp;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

/**
 * 文本翻译器。
 * <p>输入源语言文本，输出目标语言文本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TextTranslator {

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
    private final ModelSetting setting;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = "cpu";

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
    public TextTranslator modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public TextTranslator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String translate(String text) {
        ITranslator<String, String> t =
                (ITranslator<String, String>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(text);
    }
}
