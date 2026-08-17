package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.MattingService;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxMattingService implements MattingService {

    private String modelName;

    public OnnxMattingService(String apiKey) {
    }

    @Override
    public MattingService model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "modnet";
    }
}
