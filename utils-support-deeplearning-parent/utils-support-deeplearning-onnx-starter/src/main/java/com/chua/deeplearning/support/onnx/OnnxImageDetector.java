package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxImageDetector implements ImageDetector {

    private String modelName;
    private float threshold = 0.5f;
    private float nms = 0.4f;
    private String modelPath;
    private String device = "cpu";

    public OnnxImageDetector(String apiKey) {
    }

    @Override
    public ImageDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov8s";
    }

    @Override
    public ImageDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public ImageDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    @Override
    public ImageDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ImageDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(resolveModel()).threshold(threshold).nms(nms).modelPath(modelPath).device(device).detect(imageData);
    }

}
