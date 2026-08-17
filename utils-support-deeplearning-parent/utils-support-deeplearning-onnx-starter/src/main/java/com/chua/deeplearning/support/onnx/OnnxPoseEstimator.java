package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.pose.PoseEstimator;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OnnxPoseEstimator implements PoseEstimator {

    private String modelName;

    public OnnxPoseEstimator(String apiKey) {
    }

    @Override
    public PoseEstimator model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "vit-pose";
    }

    @Override
    public java.util.List<com.chua.deeplearning.support.model.PredictRectangle> estimate(byte[] imageData) {
        return PoseEstimator.create(resolveModel()).estimate(imageData);
    }

}
