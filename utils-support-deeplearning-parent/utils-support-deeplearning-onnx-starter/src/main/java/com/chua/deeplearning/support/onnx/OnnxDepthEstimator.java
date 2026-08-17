package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.DepthEstimator;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

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
}
