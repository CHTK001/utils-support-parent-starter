package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.liveness.LivenessDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxLivenessDetector implements LivenessDetector {

    private String modelName;

    public OnnxLivenessDetector(String apiKey) {
    }

    @Override
    public LivenessDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "face-anti-spoof";
    }
}
