package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.SmileDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxSmileDetector implements SmileDetector {

    private String modelName;

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
    public SmileDetector modelPath(String path) {
        return SmileDetector.create(resolveModel()).modelPath(path);
    }

    @Override
    public SmileDetector device(String device) {
        return SmileDetector.create(resolveModel()).device(device);
    }

    @Override
    public List<PredictRectangle> detect(byte[] imageData) {
        return SmileDetector.create(resolveModel()).detect(imageData);
    }

}
