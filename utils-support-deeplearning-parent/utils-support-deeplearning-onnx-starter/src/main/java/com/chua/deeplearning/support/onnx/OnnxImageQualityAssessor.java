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
    private double blurThreshold = 100.0;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    private String device = "cpu";

    public OnnxImageQualityAssessor(String apiKey) {
    }

    @Override
    public ImageQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "nima";
    }

    @Override
    public ImageQualityAssessor blurThreshold(double blurThreshold) {
        this.blurThreshold = blurThreshold;
        return this;
    }

    @Override
    public ImageQualityAssessor modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ImageQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public ImageQualityInfo assess(byte[] imageData) {
        return ImageQualityAssessor.create(resolveModel()).blurThreshold(blurThreshold).modelPath(modelPath).device(device).assess(imageData);
    }

}


