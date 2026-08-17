package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.plate.LicensePlateRecognizer;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.plate.PlateResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxLicensePlateRecognizer implements LicensePlateRecognizer {

    private String modelName;
    private float threshold = 0.5f;
    private String modelPath;
    private String device = "cpu";

    public OnnxLicensePlateRecognizer(String apiKey) {
    }

    @Override
    public LicensePlateRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov5-plate";
    }

    @Override
    public LicensePlateRecognizer threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public LicensePlateRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public LicensePlateRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public String recognize(byte[] imageData) {
        return LicensePlateRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).recognize(imageData);
    }

    @Override
    public List<DetectionInfo> recognizeDetail(byte[] imageData) {
        return LicensePlateRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).recognizeDetail(imageData);
    }

    @Override
    public PlateResult recognizePlate(byte[] imageData) {
        return LicensePlateRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).recognizePlate(imageData);
    }

}
