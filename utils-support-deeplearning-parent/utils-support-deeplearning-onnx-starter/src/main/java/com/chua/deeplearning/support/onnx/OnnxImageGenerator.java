package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageGenerator;
import lombok.extern.slf4j.Slf4j;

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
        return modelName != null ? modelName : "image-generator";
    }

    @Override
    public byte[] generate(long classId) {
        return ImageGenerator.create(resolveModel()).generate(classId);
    }

}
