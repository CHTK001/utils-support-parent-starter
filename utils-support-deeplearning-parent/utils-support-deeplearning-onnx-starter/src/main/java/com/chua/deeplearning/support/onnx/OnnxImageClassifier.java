package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxImageClassifier implements ImageClassifier {

    private String modelName;

    public OnnxImageClassifier(String apiKey) {
    }

    @Override
    public ImageClassifier model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "efficient-net-lite4";
    }

    @Override
    public ImageClassifier topK(int k) {
        return ImageClassifier.create(resolveModel()).topK(k);
    }

    @Override
    public ImageClassifier modelPath(String path) {
        return ImageClassifier.create(resolveModel()).modelPath(path);
    }

    @Override
    public ImageClassifier device(String device) {
        return ImageClassifier.create(resolveModel()).device(device);
    }

    @Override
    public String classify(byte[] imageData) {
        return ImageClassifier.create(resolveModel()).classify(imageData);
    }

    @Override
    public List<DetectionInfo> classifyTopK(byte[] imageData, int k) {
        return ImageClassifier.create(resolveModel()).classifyTopK(imageData, k);
    }

}
