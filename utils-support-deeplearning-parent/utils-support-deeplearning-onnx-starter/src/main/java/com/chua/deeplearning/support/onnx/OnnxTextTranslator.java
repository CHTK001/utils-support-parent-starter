package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.nlp.TextTranslator;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

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
        return modelName != null ? modelName : "t5-seq2seq";
    }
}
