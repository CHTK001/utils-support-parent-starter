package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageGenerator;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxImageGenerator implements ImageGenerator {

    private String modelName;

    public OnnxImageGenerator(String apiKey) {
    }

    @Override
    public ImageGenerator model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "small-stable-diffusion-combined";
    }
}
