package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.liveness.LivenessDetector;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxLivenessDetector implements LivenessDetector {

    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = 0.5f;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    public OnnxLivenessDetector(String apiKey) {
    }

    @Override
    public LivenessDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "face-liveness-flrgb";
    }

    @Override
    public LivenessDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public LivenessDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public LivenessDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public boolean isLive(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).isLive(imageData);
    }

    @Override
    public float liveScore(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).liveScore(imageData);
    }

}


