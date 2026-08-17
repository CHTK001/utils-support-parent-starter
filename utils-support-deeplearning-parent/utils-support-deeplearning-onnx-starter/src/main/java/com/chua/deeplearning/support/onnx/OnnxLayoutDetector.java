package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.layout.LayoutDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import java.util.Map;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxLayoutDetector implements LayoutDetector {

    private String modelName;
    private float threshold = 0.5f;
    private String modelPath;
    private boolean useGpu = false;
    private String device = "cpu";

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
        this.threshold = threshold;
        return this;
    }

    @Override
    public LayoutDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public LayoutDetector useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    @Override
    public LayoutDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public Map<String, List<PredictRectangle>> detect(byte[] imageData) {
        return LayoutDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).useGpu(useGpu).device(device).detect(imageData);
    }

    @Override
    public String parse(byte[] imageData) {
        return LayoutDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).useGpu(useGpu).device(device).parse(imageData);
    }

}
