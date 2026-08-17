package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.EyeDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

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

    @Override
    public List<PredictRectangle> detect(byte[] imageData) {
        return EyeDetector.create(resolveModel()).detect(imageData);
    }

}
