package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageEnhancer;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageEnhancer implements ImageEnhancer {

    /** 模型名称 */
    private String modelName;

    /**
     * 创建 OnnxImageEnhancer 实例
     * @param apiKey apiKey
     */
    public OnnxImageEnhancer(String apiKey) {
    }

    @Override
    /** Model */
    public ImageEnhancer model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "onnx-gfpgan";
    }

    @Override
    /** Enhance */
    public byte[] enhance(byte[] imageData) {
        return ImageEnhancer.create(resolveModel()).enhance(imageData);
    }

}


