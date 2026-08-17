package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxOcrRecognizer implements OcrRecognizer {

    private String modelName;

    public OnnxOcrRecognizer(String apiKey) {
    }

    @Override
    public OcrRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "pp-ocr-rec";
    }
}
