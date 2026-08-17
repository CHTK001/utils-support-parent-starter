package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceRecognizer;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxFaceRecognizer implements FaceRecognizer {

    private String modelName;

    public OnnxFaceRecognizer(String apiKey) {
    }

    @Override
    public FaceRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "arc-face";
    }
}
