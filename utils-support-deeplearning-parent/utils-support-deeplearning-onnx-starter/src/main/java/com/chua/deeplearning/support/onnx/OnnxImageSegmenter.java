package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageSegmenter;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxImageSegmenter implements ImageSegmenter {

    private String modelName;

    public OnnxImageSegmenter(String apiKey) {
    }

    @Override
    public ImageSegmenter model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "clipseg-zero-shot";
    }
}
