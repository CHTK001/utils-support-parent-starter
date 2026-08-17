package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.plate.LicensePlateRecognizer;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxLicensePlateRecognizer implements LicensePlateRecognizer {

    private String modelName;

    public OnnxLicensePlateRecognizer(String apiKey) {
    }

    @Override
    public LicensePlateRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov5-plate-detect";
    }
}
