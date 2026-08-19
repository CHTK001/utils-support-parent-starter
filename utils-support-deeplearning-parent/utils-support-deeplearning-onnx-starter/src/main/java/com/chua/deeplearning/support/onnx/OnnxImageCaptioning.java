package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageCaptioning;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageCaptioning implements ImageCaptioning {

    /** 模型名称 */
    private String modelName;

    /**
     * 创建 OnnxImageCaptioning 实例
     * @param apiKey apiKey
     */
    public OnnxImageCaptioning(String apiKey) {
    }

    @Override
    /** Model */
    public ImageCaptioning model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "vit-gpt2-captioning";
    }

    @Override
    /** Describe */
    public String describe(byte[] imageData) {
        return ImageCaptioning.create(resolveModel()).describe(imageData);
    }

}


