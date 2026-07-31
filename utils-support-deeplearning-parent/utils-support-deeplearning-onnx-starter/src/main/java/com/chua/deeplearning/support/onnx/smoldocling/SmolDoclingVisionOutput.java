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

    private final NDArray imageFeatures;

    public SmolDoclingVisionOutput(NDArray imageFeatures) {
        this.imageFeatures = imageFeatures;
    }

    public NDArray getImageFeatures() {
        return imageFeatures;
    }

    public String getShapeString() {
        return imageFeatures.getShape().toString();
    }
}
