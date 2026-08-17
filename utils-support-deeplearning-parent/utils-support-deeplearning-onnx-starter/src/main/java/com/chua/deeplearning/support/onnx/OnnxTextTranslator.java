package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.nlp.TextTranslator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxTextTranslator implements TextTranslator {

    private String modelName;

    public OnnxTextTranslator(String apiKey) {
    }

    @Override
    public TextTranslator model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "opus-mt-zh-en";
    }

    @Override
    public String translate(String text) {
        return TextTranslator.create(resolveModel()).translate(text);
    }

}
