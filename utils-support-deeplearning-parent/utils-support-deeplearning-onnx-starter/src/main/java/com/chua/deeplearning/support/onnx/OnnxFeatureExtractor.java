package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.feature.FeatureExtractor;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*/

@Slf4j
public class OnnxFeatureExtractor implements FeatureExtractor {

    /**
     * 模型名称
    */
    private String modelName;
    /**
     * 模型路径
    */
    private String modelPath;
    /**
     * 是否归一化
    */
    private boolean normalize = true;
    /**
     * 设备类型
    */
    private String device = "cpu";

    /**
     * 创建 onnx特征extractor 实例
     * @param apiKey API密钥
     */
    public OnnxFeatureExtractor(String apiKey) {
    }

    @Override
    /**
     * 模型
    */
    public FeatureExtractor model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     * <p>不设默认模型：特征模型由用户在系统配置中显式选择，未配置时抛错。
     * 早期版本默认 {@code dino-v2}，但其 Translator 已停用；随后改为
     * {@code clip-image-feature}，同样属于替用户做决定，故一并移除。</p>
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定特征模型，请通过 .model(\"模型ID\") 指定，可用模型: "
                    + FeatureExtractor.listModels());
        }
        return modelName;
    }

    @Override
    /**
     * 模型路径
    */
    public FeatureExtractor modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /**
     * Normalize
    */
    public FeatureExtractor normalize(boolean normalize) {
        this.normalize = normalize;
        return this;
    }

    @Override
    /**
     * Device
    */
    public FeatureExtractor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /**
     * Extract
    */
    public float[] extract(byte[] imageData) {
        return FeatureExtractor.create(resolveModel()).modelPath(modelPath).normalize(normalize).device(device).extract(imageData);
    }

    @Override
    /**
     * Extract
    */
    public float[] extract(String text) {
        return FeatureExtractor.create(resolveModel()).modelPath(modelPath).normalize(normalize).device(device).extract(text);
    }

}


