package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.feature.FeatureExtractor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxFeatureExtractor implements FeatureExtractor {

    private String modelName;
    private String modelPath;
    private boolean normalize = true;
    private String device = "cpu";

    public OnnxFeatureExtractor(String apiKey) {
    }

    @Override
    public FeatureExtractor model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "bge-small-zh";
    }

    @Override
    public FeatureExtractor modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public FeatureExtractor normalize(boolean normalize) {
        this.normalize = normalize;
        return this;
    }

    @Override
    public FeatureExtractor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public float[] extract(byte[] imageData) {
        return FeatureExtractor.create(resolveModel()).modelPath(modelPath).normalize(normalize).device(device).extract(imageData);
    }

    @Override
    public float[] extract(String text) {
        return FeatureExtractor.create(resolveModel()).modelPath(modelPath).normalize(normalize).device(device).extract(text);
    }

}
