package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

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
      * 创建 onnx镜像qualityassessor 实例
     * @param apiKey API密钥
     */
    public OnnxImageQualityAssessor(String apiKey) {
    }

    @Override
    /** 模型 */
    public ImageQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "nima";
    }

    @Override
    /** blur阈值 */
    public ImageQualityAssessor blurThreshold(double blurThreshold) {
        this.blurThreshold = blurThreshold;
        return this;
    }

    @Override
    /** 模型路径 */
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
    /** 评定 */
    public ImageQualityInfo assess(byte[] imageData) {
        return ImageQualityAssessor.create(resolveModel()).blurThreshold(blurThreshold).modelPath(modelPath).device(device).assess(imageData);
    }

}


