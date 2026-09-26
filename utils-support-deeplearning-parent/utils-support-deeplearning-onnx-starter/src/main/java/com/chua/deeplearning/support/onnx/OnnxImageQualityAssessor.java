package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.model.ImageQualityInfo;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*/

@Slf4j
public class OnnxImageQualityAssessor implements ImageQualityAssessor {

    /**
     * 模型名称
    */
    private String modelName;
    /**
     * 模糊度阈值
    */
    private double blurThreshold = 100.0;
    /**
     * 模型路径
    */
    private String modelPath;
    /**
     * 设备类型
    */
    private String device = "cpu";

    /**
     * 创建 onnx镜像qualityassessor 实例
     * @param apiKey API密钥
     */
    public OnnxImageQualityAssessor(String apiKey) {
    }

    @Override
    /**
     * 模型
    */
    public ImageQualityAssessor model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     * <p>ONNX 侧当前没有任何模型注册为 {@link ImageQualityAssessor} 能力
     * （NIMA 注册的是 FeatureExtractor，输出 float[]，与 ImageQualityInfo 契约不符），
     * 因此此处不再提供默认值，必须由调用方显式指定可用模型，避免运行时
     * ClassCastException。质量评估可改用 LaplacianImageQualityAssessor。</p>
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 显式指定，可用模型: "
                    + ImageQualityAssessor.listModels());
        }
        return modelName;
    }

    @Override
    /**
     * blur阈值
    */
    public ImageQualityAssessor blurThreshold(double blurThreshold) {
        this.blurThreshold = blurThreshold;
        return this;
    }

    @Override
    /**
     * 模型路径
    */
    public ImageQualityAssessor modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /**
     * Device
    */
    public ImageQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /**
     * 评定
    */
    public ImageQualityInfo assess(byte[] imageData) {
        return ImageQualityAssessor.create(resolveModel()).blurThreshold(blurThreshold).modelPath(modelPath).device(device).assess(imageData);
    }

}


