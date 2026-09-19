package com.chua.deeplearning.support.onnx.smoldocling;

import ai.djl.ndarray.NDArray;

import java.util.Arrays;

/**
 * smoldocling Vision
 *
 * @author CH
 * @版本 4.0.0.32
 * @since 2025/01/22
 */
public class SmolDoclingVisionOutput {

    /** 图像特征 */
    /** 图片特征 */
    private final NDArray imageFeatures;

    /**
     * 创建 smoldoclingvision输出 实例
     * @param imageFeatures 镜像特征
     */
    public SmolDoclingVisionOutput(NDArray imageFeatures) {
        this.imageFeatures = imageFeatures;
    }

    /**
     * 获取镜像特征
     *
     * @return 获取镜像特征的结果
     */
    public NDArray getImageFeatures() {
        return imageFeatures;
    }

    /**
     * 获取shape字符串
     *
     * @return 获取shape字符串的结果
     */
    public String getShapeString() {
        return imageFeatures.getShape().toString();
    }
}
