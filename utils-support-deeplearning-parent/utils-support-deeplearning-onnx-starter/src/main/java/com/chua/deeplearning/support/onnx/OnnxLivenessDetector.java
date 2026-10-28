package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.liveness.LivenessDetector;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*
* @author CH
* @since 4.0.0.42
*/

@Slf4j
public class OnnxLivenessDetector implements LivenessDetector {

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
     * 创建 onnxlivenessdetector 实例
     * @param apiKey API密钥
     */
    public OnnxLivenessDetector(String apiKey) {
    }

    /**
     * 模型
    */
    @Override
    public LivenessDetector model(String model) {
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
            throw new IllegalStateException("未指定活体检测模型，请通过 .model(\"模型ID\") 指定，"
                    + "或在系统配置 face 分组的 liveness_model 中选择，可用模型: "
                    + LivenessDetector.listModels());
        }
        return modelName;
    }

    /**
     * 阈值
    */
    @Override
    public LivenessDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    /**
     * 模型路径
    */
    @Override
    public LivenessDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    /**
     * Device
    */
    @Override
    public LivenessDetector device(String device) {
        this.device = device;
        return this;
    }

    /**
     * 是否Live
    */
    @Override
    public boolean isLive(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device)
                .isLive(imageData);
    }

    /**
     * livescore
    */
    @Override
    public float liveScore(byte[] imageData) {
        return LivenessDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device)
                .liveScore(imageData);
    }

}


