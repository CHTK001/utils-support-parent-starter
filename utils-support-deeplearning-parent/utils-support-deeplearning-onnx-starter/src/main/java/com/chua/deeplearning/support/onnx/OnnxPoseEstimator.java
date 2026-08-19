package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.pose.PoseEstimator;
import com.chua.deeplearning.support.pose.PoseKeypoint;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxPoseEstimator implements PoseEstimator {

    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = 0.5f;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

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
        this.threshold = threshold;
        return this;
    }

    @Override
    public PoseEstimator modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public PoseEstimator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<PoseKeypoint> estimate(byte[] imageData) {
        return PoseEstimator.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).estimate(imageData);
    }

    @Override
    public List<List<PoseKeypoint>> estimateMulti(byte[] imageData) {
        List<PoseKeypoint> single = estimate(imageData);
        return single == null ? List.of() : List.of(single);
    }

}


