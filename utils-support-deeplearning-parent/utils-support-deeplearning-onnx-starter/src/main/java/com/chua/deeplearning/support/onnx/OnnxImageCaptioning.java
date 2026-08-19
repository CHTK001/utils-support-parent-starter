package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageCaptioning;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageCaptioning implements ImageCaptioning {

    /** 模型名称 */
    private String modelName;

    public OnnxImageCaptioning(String apiKey) {
    }

    @Override
    public ImageCaptioning model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "vit-gpt2-captioning";
    }

    @Override
    public String describe(byte[] imageData) {
        return ImageCaptioning.create(resolveModel()).describe(imageData);
    }

}


