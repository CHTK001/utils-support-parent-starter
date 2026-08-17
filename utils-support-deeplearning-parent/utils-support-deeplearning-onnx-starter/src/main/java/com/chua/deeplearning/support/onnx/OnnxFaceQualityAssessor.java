package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceQualityAssessor;
import com.chua.deeplearning.support.model.FaceQualityInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 人脸质量评估引擎（SPI provider="onnx"）。
 *
 * <p>注册表中无匹配的人脸质量评估模型，必须通过 {@code .model("模型ID")} 显式指定
 * 已注册模型，否则抛出异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxFaceQualityAssessor implements FaceQualityAssessor {

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 模糊阈值
     */
    private double blurThreshold = 100.0;

    /**
     * 运行设备
     */
    private String device = "cpu";

    public OnnxFaceQualityAssessor(String apiKey) {
    }

    @Override
    public FaceQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 显式指定，可用模型: " + FaceQualityAssessor.listModels());
        }
        return modelName;
    }

    @Override
    public FaceQualityAssessor blurThreshold(double blurThreshold) {
        this.blurThreshold = blurThreshold;
        return this;
    }

    @Override
    public FaceQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public FaceQualityInfo assess(byte[] imageData) {
        return FaceQualityAssessor.create(resolveModel()).blurThreshold(blurThreshold).device(device).assess(imageData);
    }

}
