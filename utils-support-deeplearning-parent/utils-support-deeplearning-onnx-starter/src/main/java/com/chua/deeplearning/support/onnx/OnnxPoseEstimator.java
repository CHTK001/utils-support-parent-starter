package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.pose.PoseEstimator;
import com.chua.deeplearning.support.pose.PoseKeypoint;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*/

@Slf4j
public class OnnxPoseEstimator implements PoseEstimator {

    /**
     * 模型名称
    */
    private String modelName;
    /**
     * 阈值
    */
    private float threshold = 0.5f;
    /**
     * 模型路径
    */
    private String modelPath;
    /**
     * 设备类型
    */
    private String device = "cpu";

    /**
     * 创建 onnxposeestimator 实例
     * @param apiKey API密钥
     */
    public OnnxPoseEstimator(String apiKey) {
    }

    /**
     * 模型
    */
    @Override
    public PoseEstimator model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 指定，可用模型: "
                    + PoseEstimator.listModels());
        }
        return modelName;
    }

    /**
     * 阈值
    */
    @Override
    public PoseEstimator threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    /**
     * 模型路径
    */
    @Override
    public PoseEstimator modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    /**
     * Device
    */
    @Override
    public PoseEstimator device(String device) {
        this.device = device;
        return this;
    }

    /**
     * Estimate
    */
    @Override
    public List<PoseKeypoint> estimate(byte[] imageData) {
        return PoseEstimator.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).estimate(imageData);
    }

    /**
     * estimatemulti
    */
    @Override
    public List<List<PoseKeypoint>> estimateMulti(byte[] imageData) {
        List<PoseKeypoint> single = estimate(imageData);
        return single == null ? List.of() : List.of(single);
    }

}


