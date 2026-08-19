package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageQualityAssessor implements ImageQualityAssessor {

    /** 模型名称 */
    private String modelName;
    /** 模糊度阈值 */
    /** Blur阈值 */
    private double blurThreshold = 100.0;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
     * 创建 OnnxImageQualityAssessor 实例
     * @param apiKey apiKey
     */
    public OnnxImageQualityAssessor(String apiKey) {
    }

    @Override
    /** Model */
    public ImageQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "nima";
    }

    @Override
    /** BlurThreshold */
    public ImageQualityAssessor blurThreshold(double blurThreshold) {
        this.blurThreshold = blurThreshold;
        return this;
    }

    @Override
    /** ModelPath */
    public ImageQualityAssessor modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public ImageQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Assess */
    public ImageQualityInfo assess(byte[] imageData) {
        return ImageQualityAssessor.create(resolveModel()).blurThreshold(blurThreshold).modelPath(modelPath).device(device).assess(imageData);
    }

}


