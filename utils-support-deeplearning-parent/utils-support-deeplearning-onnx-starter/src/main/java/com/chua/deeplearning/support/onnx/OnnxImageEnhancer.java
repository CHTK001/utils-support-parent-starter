package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageEnhancer;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxImageEnhancer implements ImageEnhancer {

    private String modelName;

    public OnnxImageEnhancer(String apiKey) {
    }

    @Override
    public ImageEnhancer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "real-esrgan";
    }
}
