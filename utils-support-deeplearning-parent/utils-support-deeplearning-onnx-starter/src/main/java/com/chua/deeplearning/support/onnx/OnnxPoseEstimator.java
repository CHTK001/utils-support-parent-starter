package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.pose.PoseEstimator;
import com.chua.deeplearning.support.pose.PoseKeypoint;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

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
        return modelName != null ? modelName : "yolov8n-pose";
    }

    @Override
    public PoseEstimator threshold(float threshold) {
        return PoseEstimator.create(resolveModel()).threshold(threshold);
    }

    @Override
    public PoseEstimator modelPath(String path) {
        return PoseEstimator.create(resolveModel()).modelPath(path);
    }

    @Override
    public PoseEstimator device(String device) {
        return PoseEstimator.create(resolveModel()).device(device);
    }

    @Override
    public List<PoseKeypoint> estimate(byte[] imageData) {
        return PoseEstimator.create(resolveModel()).estimate(imageData);
    }

    @Override
    public List<List<PoseKeypoint>> estimateMulti(byte[] imageData) {
        return PoseEstimator.create(resolveModel()).estimateMulti(imageData);
    }

}
