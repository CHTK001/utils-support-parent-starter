package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.liveness.LivenessDetector;
import lombok.extern.slf4j.Slf4j;

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
        return modelName != null ? modelName : "face-liveness";
    }

    @Override
    public LivenessDetector threshold(float threshold) {
        return LivenessDetector.create(resolveModel()).threshold(threshold);
    }

    @Override
    public LivenessDetector modelPath(String path) {
        return LivenessDetector.create(resolveModel()).modelPath(path);
    }

    @Override
    public LivenessDetector device(String device) {
        return LivenessDetector.create(resolveModel()).device(device);
    }

    @Override
    public boolean isLive(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).isLive(imageData);
    }

    @Override
    public float liveScore(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).liveScore(imageData);
    }

}
