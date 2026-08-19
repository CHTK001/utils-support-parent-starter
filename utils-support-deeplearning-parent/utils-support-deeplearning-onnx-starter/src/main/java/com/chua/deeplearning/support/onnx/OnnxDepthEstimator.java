package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.DepthEstimator;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxDepthEstimator implements DepthEstimator {

    /** 模型名称 */
    private String modelName;

    /**
     * 创建 OnnxDepthEstimator 实例
     * @param apiKey apiKey
     */
    public OnnxDepthEstimator(String apiKey) {
    }

    @Override
    /** Model */
    public DepthEstimator model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "depth-anything";
    }

    @Override
    /** Estimate */
    public byte[] estimate(byte[] imageData) {
        return DepthEstimator.create(resolveModel()).estimate(imageData);
    }

}


