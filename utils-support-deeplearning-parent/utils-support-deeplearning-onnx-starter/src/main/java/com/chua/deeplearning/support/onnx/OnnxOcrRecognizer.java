package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

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
        return modelName != null ? modelName : "paddleocrv6";
    }

    @Override
    public OcrRecognizer lang(String lang) {
        return OcrRecognizer.create(resolveModel()).lang(lang);
    }

    @Override
    public OcrRecognizer modelPath(String path) {
        return OcrRecognizer.create(resolveModel()).modelPath(path);
    }

    @Override
    public OcrRecognizer device(String device) {
        return OcrRecognizer.create(resolveModel()).device(device);
    }

    @Override
    public OcrRecognizer useGpu(boolean useGpu) {
        return OcrRecognizer.create(resolveModel()).useGpu(useGpu);
    }

    @Override
    public String recognize(byte[] imageData) {
        return OcrRecognizer.create(resolveModel()).recognize(imageData);
    }

    @Override
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return OcrRecognizer.create(resolveModel()).recognizeDetail(imageData);
    }

}
