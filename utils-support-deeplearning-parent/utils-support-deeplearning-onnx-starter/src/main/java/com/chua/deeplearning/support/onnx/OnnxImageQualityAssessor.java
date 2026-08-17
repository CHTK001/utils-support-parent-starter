package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxImageQualityAssessor implements ImageQualityAssessor {

    private String modelName;
    private double blurThreshold = 100.0;
    private String modelPath;
    private String device = "cpu";

    public OnnxImageQualityAssessor(String apiKey) {
    }

    @Override
    public ImageQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "nima";
    }

    @Override
    public ImageQualityAssessor blurThreshold(double blurThreshold) {
        this.blurThreshold = blurThreshold;
        return this;
    }

    @Override
    public ImageQualityAssessor modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ImageQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public ImageQualityInfo assess(byte[] imageData) {
        return ImageQualityAssessor.create(resolveModel()).blurThreshold(blurThreshold).modelPath(modelPath).device(device).assess(imageData);
    }

}
