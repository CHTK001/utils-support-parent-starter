package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxImageQualityAssessor implements ImageQualityAssessor {

    private String modelName;

    public OnnxImageQualityAssessor(String apiKey) {
    }

    @Override
    public ImageQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "image-quality";
    }

    @Override
    public ImageQualityAssessor blurThreshold(double threshold) {
        return ImageQualityAssessor.create(resolveModel()).blurThreshold(threshold);
    }

    @Override
    public ImageQualityAssessor modelPath(String path) {
        return ImageQualityAssessor.create(resolveModel()).modelPath(path);
    }

    @Override
    public ImageQualityAssessor device(String device) {
        return ImageQualityAssessor.create(resolveModel()).device(device);
    }

    @Override
    public ImageQualityInfo assess(byte[] imageData) {
        return ImageQualityAssessor.create(resolveModel()).assess(imageData);
    }

}
