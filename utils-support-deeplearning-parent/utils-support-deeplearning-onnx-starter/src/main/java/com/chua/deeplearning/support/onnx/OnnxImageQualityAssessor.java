package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

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
        return modelName != null ? modelName : "nima";
    }
}
