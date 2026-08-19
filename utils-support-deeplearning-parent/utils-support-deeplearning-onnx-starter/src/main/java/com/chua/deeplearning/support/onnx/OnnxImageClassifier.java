package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageClassifier implements ImageClassifier {

    /** 模型名称 */
    private String modelName;
    /** Top-K 采样数量 */
    /** 顶部K */
    private int topK = 5;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
     * 创建 OnnxImageClassifier 实例
     * @param apiKey apiKey
     */
    public OnnxImageClassifier(String apiKey) {
    }

    @Override
    /** Model */
    public ImageClassifier model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "efficient-net-lite4-classification";
    }

    @Override
    /** TopK */
    public ImageClassifier topK(int topK) {
        this.topK = topK;
        return this;
    }

    @Override
    /** ModelPath */
    public ImageClassifier modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public ImageClassifier device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Classify */
    public String classify(byte[] imageData) {
        return ImageClassifier.create(resolveModel()).topK(topK).modelPath(modelPath).device(device).classify(imageData);
    }

    @Override
    /** ClassifyTopK */
    public List<DetectionInfo> classifyTopK(byte[] imageData, int k) {
        return ImageClassifier.create(resolveModel()).topK(topK).modelPath(modelPath).device(device).classifyTopK(imageData, k);
    }

}


