package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.nlp.TextTranslator;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxTextTranslator implements TextTranslator {

    /** 模型名称 */
    private String modelName;

    /**
     * 创建 OnnxTextTranslator 实例
     * @param apiKey apiKey
     */
    public OnnxTextTranslator(String apiKey) {
    }

    @Override
    /** Model */
    public TextTranslator model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "opus-mt-zh-en";
    }

    @Override
    /** Translate */
    public String translate(String text) {
        return TextTranslator.create(resolveModel()).translate(text);
    }

}


