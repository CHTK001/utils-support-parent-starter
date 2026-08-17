package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.plate.LicensePlateRecognizer;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.plate.PlateResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

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
        return modelName != null ? modelName : "yolov5-plate";
    }

    @Override
    public LicensePlateRecognizer threshold(float threshold) {
        return LicensePlateRecognizer.create(resolveModel()).threshold(threshold);
    }

    @Override
    public LicensePlateRecognizer modelPath(String path) {
        return LicensePlateRecognizer.create(resolveModel()).modelPath(path);
    }

    @Override
    public LicensePlateRecognizer device(String device) {
        return LicensePlateRecognizer.create(resolveModel()).device(device);
    }

    @Override
    public String recognize(byte[] imageData) {
        return LicensePlateRecognizer.create(resolveModel()).recognize(imageData);
    }

    @Override
    public List<DetectionInfo> recognizeDetail(byte[] imageData) {
        return LicensePlateRecognizer.create(resolveModel()).recognizeDetail(imageData);
    }

    @Override
    public PlateResult recognizePlate(byte[] imageData) {
        return LicensePlateRecognizer.create(resolveModel()).recognizePlate(imageData);
    }

}
