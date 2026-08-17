package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.EyeDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxEyeDetector implements EyeDetector {

    private String modelName;

    public OnnxEyeDetector(String apiKey) {
    }

    @Override
    public EyeDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "ultra-face";
    }
}
