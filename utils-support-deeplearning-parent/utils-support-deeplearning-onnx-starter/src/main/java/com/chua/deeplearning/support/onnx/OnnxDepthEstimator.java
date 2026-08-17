package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.DepthEstimator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxDepthEstimator implements DepthEstimator {

    private String modelName;

    public OnnxDepthEstimator(String apiKey) {
    }

    @Override
    public DepthEstimator model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "depth-anything";
    }

    @Override
    public byte[] estimate(byte[] imageData) {
        return DepthEstimator.create(resolveModel()).estimate(imageData);
    }

}
