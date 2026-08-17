package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxImageClassifier implements ImageClassifier {

    private String modelName;

    public OnnxImageClassifier(String apiKey) {
    }

    @Override
    public ImageClassifier model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "efficient-net-lite4-classification";
    }
}
