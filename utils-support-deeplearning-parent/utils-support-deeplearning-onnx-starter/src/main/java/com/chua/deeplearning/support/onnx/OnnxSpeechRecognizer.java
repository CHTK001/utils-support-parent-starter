package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechRecognizer;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxSpeechRecognizer implements SpeechRecognizer {

    private String modelName;

    public OnnxSpeechRecognizer(String apiKey) {
    }

    @Override
    public SpeechRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "moonshine-base";
    }
}
