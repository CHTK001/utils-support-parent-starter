package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceQualityAssessor;
import com.chua.deeplearning.support.model.FaceQualityInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxFaceQualityAssessor implements FaceQualityAssessor {

    private String modelName;

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
    public FaceQualityInfo assess(byte[] imageData) {
        return FaceQualityAssessor.create(resolveModel()).assess(imageData);
    }

}
