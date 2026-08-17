package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxFeatureExtractor implements FeatureExtractor {

    private String modelName;

    public OnnxFeatureExtractor(String apiKey) {
    }

    @Override
    public FeatureExtractor model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "dino-v2";
    }
}
