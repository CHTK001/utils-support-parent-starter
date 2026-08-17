package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.layout.LayoutDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.Map;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxLayoutDetector implements LayoutDetector {

    private String modelName;

    public OnnxLayoutDetector(String apiKey) {
    }

    @Override
    public LayoutDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "doclaynet";
    }

    @Override
    public LayoutDetector threshold(float threshold) {
        return LayoutDetector.create(resolveModel()).threshold(threshold);
    }

    @Override
    public LayoutDetector modelPath(String path) {
        return LayoutDetector.create(resolveModel()).modelPath(path);
    }

    @Override
    public LayoutDetector device(String device) {
        return LayoutDetector.create(resolveModel()).device(device);
    }

    @Override
    public LayoutDetector useGpu(boolean useGpu) {
        return LayoutDetector.create(resolveModel()).useGpu(useGpu);
    }

    @Override
    public Map<String, List<PredictRectangle>> detect(byte[] imageData) {
        return LayoutDetector.create(resolveModel()).detect(imageData);
    }

    @Override
    public String parse(byte[] imageData) {
        return LayoutDetector.create(resolveModel()).parse(imageData);
    }

}
