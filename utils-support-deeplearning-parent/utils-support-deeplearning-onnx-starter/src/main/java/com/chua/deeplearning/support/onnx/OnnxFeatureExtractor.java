package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.feature.FeatureExtractor;
import lombok.extern.slf4j.Slf4j;

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
        return modelName != null ? modelName : "bge-small-zh";
    }

    @Override
    public FeatureExtractor modelPath(String path) {
        return FeatureExtractor.create(resolveModel()).modelPath(path);
    }

    @Override
    public FeatureExtractor device(String device) {
        return FeatureExtractor.create(resolveModel()).device(device);
    }

    @Override
    public FeatureExtractor normalize(boolean normalize) {
        return FeatureExtractor.create(resolveModel()).normalize(normalize);
    }

    @Override
    public float[] extract(byte[] imageData) {
        return FeatureExtractor.create(resolveModel()).extract(imageData);
    }

    @Override
    public float[] extract(String text) {
        return FeatureExtractor.create(resolveModel()).extract(text);
    }

}
