package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.MattingService;
import lombok.extern.slf4j.Slf4j;

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

    @Override
    public byte[] matte(byte[] imageData) {
        return MattingService.create(resolveModel()).matte(imageData);
    }

}
