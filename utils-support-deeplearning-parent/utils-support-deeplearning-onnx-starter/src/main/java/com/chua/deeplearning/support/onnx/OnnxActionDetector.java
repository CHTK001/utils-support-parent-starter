package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ActionDetector;
import com.chua.deeplearning.support.model.ActionDetectionResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxActionDetector implements ActionDetector {

    private String modelName;
    private float threshold = 0.45f;
    private String modelPath;
    private String device = "cpu";

    public OnnxActionDetector(String apiKey) {
    }

    @Override
    public ActionDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "c3d-action-detection";
    }

    @Override
    public ActionDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public ActionDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ActionDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<ActionDetectionResult> detect(byte[] videoData) {
        return ActionDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).detect(videoData);
    }
}