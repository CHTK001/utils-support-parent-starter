package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageEnhancer;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

@Slf4j
public class OnnxImageEnhancer implements ImageEnhancer {

    /** 模型名称 */
    private String modelName;

    /**
    * 创建 onnx镜像enhancer 实例
    * @param apiKey API密钥
    */
    public OnnxImageEnhancer(String apiKey) {
    }

    @Override
    /** 模型 */
    public ImageEnhancer model(String model) {
        this.modelName = model;
        return this;
    }

    /**
    * 解析模型
    *
    * @return resolve模型的结果
    */
    private String resolveModel() {
        return modelName != null ? modelName : "onnx-gfpgan";
    }

    @Override
    /** 增强 */
    public byte[] enhance(byte[] imageData) {
        return ImageEnhancer.create(resolveModel()).enhance(imageData);
    }

}


