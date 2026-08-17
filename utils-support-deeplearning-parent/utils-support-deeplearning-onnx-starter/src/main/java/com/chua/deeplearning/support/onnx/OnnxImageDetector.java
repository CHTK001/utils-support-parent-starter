package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxImageDetector implements ImageDetector {

    private String modelName;

    public OnnxImageDetector(String apiKey) {
    }

    @Override
    public ImageDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov8";
    }

    @Override
    public ImageDetector threshold(float threshold) {
        return ImageDetector.create(resolveModel()).threshold(threshold);
    }

    @Override
    public ImageDetector nms(float nms) {
        return ImageDetector.create(resolveModel()).nms(nms);
    }

    @Override
    public ImageDetector modelPath(String path) {
        return ImageDetector.create(resolveModel()).modelPath(path);
    }

    @Override
    public ImageDetector device(String device) {
        return ImageDetector.create(resolveModel()).device(device);
    }

    @Override
    public List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(resolveModel()).detect(imageData);
    }

}
