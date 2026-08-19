package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.ndarray.NDArray;

import java.util.Arrays;

/**
 * SmolDocling Vision                
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025/01/22
 */
public class SmolDoclingVisionOutput {

    /** 图像特征 */
    /** 图片features */
    private final NDArray imageFeatures;

    /**
     * 创建 SmolDoclingVisionOutput 实例
     * @param imageFeatures imageFeatures
     */
    public SmolDoclingVisionOutput(NDArray imageFeatures) {
        this.imageFeatures = imageFeatures;
    }

    /** 获取ImageFeatures */
    public NDArray getImageFeatures() {
        return imageFeatures;
    }

    /** 获取ShapeString */
    public String getShapeString() {
        return imageFeatures.getShape().toString();
    }
}
