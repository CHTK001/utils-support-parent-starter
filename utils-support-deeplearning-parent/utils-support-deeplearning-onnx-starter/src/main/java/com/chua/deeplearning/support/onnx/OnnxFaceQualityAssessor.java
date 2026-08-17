package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceQualityAssessor;
import com.chua.deeplearning.support.model.FaceQualityInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxFaceQualityAssessor implements FaceQualityAssessor {

    private String modelName;
    private double blurThreshold = 100.0;
    private String device = "cpu";

    public OnnxFaceQualityAssessor(String apiKey) {
    }

    @Override
    public FaceQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "face-quality";
    }

    @Override
    public FaceQualityAssessor blurThreshold(double blurThreshold) {
        this.blurThreshold = blurThreshold;
        return this;
    }

    @Override
    public FaceQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public FaceQualityInfo assess(byte[] imageData) {
        return FaceQualityAssessor.create(resolveModel()).blurThreshold(blurThreshold).device(device).assess(imageData);
    }

}
