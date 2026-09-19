package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ActionDetector;
import com.chua.deeplearning.support.model.ActionDetectionResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX C3D 动作检测客户端，基于预注册模型执行视频动作识别。
 *
 * @author CH
 * @since 4.0.0.42
 * @return resolve模型的结果
 * @param model 模型
 */
@Slf4j
public class OnnxActionDetector implements ActionDetector {

    private String modelName; // 模型名称
    private float threshold = 0.45f; // 阈值
    private String modelPath; // 模型路径
    /**
     * onnx动作detector。
     * @param apiKey api键
     * @return resolve模型的结果
     * @param model 模型
     */
    private String device = "cpu";

    /**
     * 构造方法，创建 OnnxActionDetector 实例。
     *
     * @param apiKey api键，不允许为 null
     */
    public OnnxActionDetector(String apiKey) {
    }

    @Override
    public ActionDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型。
     *
     * @return 结果字符串
     */
    private String resolveModel() {
        return modelName != null ? modelName : "c3d-action-detection";
    }

    @Override
    public ActionDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public ActionDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ActionDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<ActionDetectionResult> detect(byte[] videoData) {
        return ActionDetector.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).detect(videoData);
    }
}
