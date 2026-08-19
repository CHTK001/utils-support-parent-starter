package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageEnhancer;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageEnhancer implements ImageEnhancer {

    /** 模型名称 */
    private String modelName;

    public OnnxImageEnhancer(String apiKey) {
    }

    @Override
    public ImageEnhancer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "onnx-gfpgan";
    }

    @Override
    public byte[] enhance(byte[] imageData) {
        return ImageEnhancer.create(resolveModel()).enhance(imageData);
    }

}


