package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.feature.FeatureExtractor;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxFeatureExtractor implements FeatureExtractor {

    /** 模型名称 */
    private String modelName;
    /** 模型路径 */
    private String modelPath;
    /** 是否归一化 */
    /** Normalize */
    private boolean normalize = true;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

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


