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
    private int topK = 5;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    private String device = "cpu";

    public OnnxImageClassifier(String apiKey) {
    }

    @Override
    public ImageClassifier model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "efficient-net-lite4-classification";
    }

    @Override
    public ImageClassifier topK(int topK) {
        this.topK = topK;
        return this;
    }

    @Override
    public ImageClassifier modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ImageClassifier device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public String classify(byte[] imageData) {
        return ImageClassifier.create(resolveModel()).topK(topK).modelPath(modelPath).device(device).classify(imageData);
    }

    @Override
    public List<DetectionInfo> classifyTopK(byte[] imageData, int k) {
        return ImageClassifier.create(resolveModel()).topK(topK).modelPath(modelPath).device(device).classifyTopK(imageData, k);
    }

}


