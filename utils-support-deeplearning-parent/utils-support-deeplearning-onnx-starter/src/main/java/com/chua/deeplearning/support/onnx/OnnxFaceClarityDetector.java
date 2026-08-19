package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceClarityDetector;
import com.chua.deeplearning.support.model.FaceQualityInfo;

/**
 * ONNX 人脸清晰度检测器（SPI provider="onnx"）。
 *
 * <p>注册表中无匹配的人脸清晰度模型，必须通过 {@code .model("模型ID")} 显式指定
 * 已注册模型，否则抛出异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxFaceClarityDetector implements FaceClarityDetector {

    /**
     * 清晰度检测模型名称
     */
    private String modelName;

    /**
     * 模糊度阈值
     */
    private double blurThreshold = 80.0;

    /**
     * 最小人脸面积比
     */
    private float minFaceRatio = 0.05f;

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 运行设备
     */
    private String device = "cpu";

    /**
     * SPI 构造函数。
     *
     * @param apiKey API 密钥（本地引擎忽略）
     */
    public OnnxFaceClarityDetector(String apiKey) {
    }

    @Override
    public FaceClarityDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 显式指定，可用模型: "
                    + FaceClarityDetector.listModels());
        }
        return modelName;
    }

    @Override
    public FaceClarityDetector blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    public FaceClarityDetector minFaceRatio(float ratio) {
        this.minFaceRatio = ratio;
        return this;
    }

    @Override
    public FaceClarityDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public FaceClarityDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public FaceQualityInfo assess(byte[] imageData) {
        return FaceClarityDetector.create(resolveModel())
                .blurThreshold(blurThreshold)
                .minFaceRatio(minFaceRatio)
                .modelPath(modelPath)
                .device(device)
                .assess(imageData);
    }
}
