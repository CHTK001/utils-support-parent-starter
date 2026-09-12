package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.DepthEstimator;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

@Slf4j
public class OnnxDepthEstimator implements DepthEstimator {

    /** 模型名称 */
    private String modelName;

    /**
    * 创建 onnx深度estimator 实例
    * @param apiKey API密钥
     */
    public OnnxDepthEstimator(String apiKey) {
    }

    @Override
    /** 模型 */
    public DepthEstimator model(String model) {
        this.modelName = model;
        return this;
    }

    /**
    * 解析模型
    *
    * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "depth-anything";
    }

    @Override
    /** Estimate */
    public byte[] estimate(byte[] imageData) {
        return DepthEstimator.create(resolveModel()).estimate(imageData);
    }

}


