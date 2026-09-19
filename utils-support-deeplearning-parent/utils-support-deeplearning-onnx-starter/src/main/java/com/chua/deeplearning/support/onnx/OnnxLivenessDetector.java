package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.liveness.LivenessDetector;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

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

    /**
     * 创建 onnxlivenessdetector 实例
     * @param apiKey API密钥
     */
    public OnnxLivenessDetector(String apiKey) {
    }

    @Override
    /** 模型 */
    public LivenessDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /**
    * 解析模型
    *
    * @return resolve模型的结果
    */
    private String resolveModel() {
        return modelName != null ? modelName : "face-liveness-flrgb";
    }

    @Override
    /** 阈值 */
    public LivenessDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public LivenessDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public LivenessDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** 是否Live */
    public boolean isLive(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).isLive(imageData);
    }

    @Override
    /** livescore */
    public float liveScore(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).liveScore(imageData);
    }

}


