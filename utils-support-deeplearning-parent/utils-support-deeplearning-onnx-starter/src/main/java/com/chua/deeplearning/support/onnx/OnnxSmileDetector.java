package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.SmileDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxSmileDetector implements SmileDetector {

    private String modelName;
    private String modelPath;
    private String device = "cpu";

    public OnnxSmileDetector(String apiKey) {
    }

    @Override
    public SmileDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "smile-detector";
    }

    @Override
    public SmileDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public SmileDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<PredictRectangle> detect(byte[] imageData) {
        return SmileDetector.create(resolveModel()).modelPath(modelPath).device(device).detect(imageData);
    }

}
