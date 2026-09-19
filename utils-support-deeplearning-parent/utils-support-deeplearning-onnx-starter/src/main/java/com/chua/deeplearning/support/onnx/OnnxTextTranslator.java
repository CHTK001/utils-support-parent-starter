package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.nlp.TextTranslator;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*/

@Slf4j
public class OnnxTextTranslator implements TextTranslator {

    /**
     * 模型名称
    */
    private String modelName;

    /**
     * 创建 onnx文本translator 实例
     * @param apiKey API密钥
     */
    public OnnxTextTranslator(String apiKey) {
    }

    @Override
    /**
     * 模型
    */
    public TextTranslator model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "opus-mt-zh-en";
    }

    @Override
    /**
     * Translate
    */
    public String translate(String text) {
        return TextTranslator.create(resolveModel()).translate(text);
    }

}


