package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageCaptioning;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxImageCaptioning implements ImageCaptioning {

    private String modelName;

    public OnnxImageCaptioning(String apiKey) {
    }

    @Override
    public ImageCaptioning model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "clip-image-feature";
    }
}
