package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.PedestrianDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxPedestrianDetector implements PedestrianDetector {

    private String modelName;

    public OnnxPedestrianDetector(String apiKey) {
    }

    @Override
    public PedestrianDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov8s";
    }
}
