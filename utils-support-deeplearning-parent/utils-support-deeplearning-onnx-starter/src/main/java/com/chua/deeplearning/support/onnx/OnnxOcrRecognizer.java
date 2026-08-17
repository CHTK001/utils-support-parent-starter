package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxOcrRecognizer implements OcrRecognizer {

    private String modelName;
    private String lang = "zh";
    private String modelPath;
    private boolean useGpu = false;
    private String device = "cpu";

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
        this.lang = lang;
        return this;
    }

    @Override
    public OcrRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public OcrRecognizer useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    @Override
    public OcrRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public String recognize(byte[] imageData) {
        return OcrRecognizer.create(resolveModel()).lang(lang).modelPath(modelPath).useGpu(useGpu).device(device).recognize(imageData);
    }

    @Override
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return OcrRecognizer.create(resolveModel()).lang(lang).modelPath(modelPath).useGpu(useGpu).device(device).recognizeDetail(imageData);
    }

}
