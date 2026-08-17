package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.SmileDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxSmileDetector implements SmileDetector {

    private String modelName;

    public OnnxSmileDetector(String apiKey) {
    }

    @Override
    public SmileDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "emotion-ferplus";
    }
}
