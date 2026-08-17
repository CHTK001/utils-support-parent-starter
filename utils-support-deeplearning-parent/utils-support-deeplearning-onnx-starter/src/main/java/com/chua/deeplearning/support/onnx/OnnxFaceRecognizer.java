package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceRecognizer;
import com.chua.deeplearning.support.face.FaceFeature;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxFaceRecognizer implements FaceRecognizer {

    private String modelName;

    public OnnxFaceRecognizer(String apiKey) {
    }

    @Override
    public FaceRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "arcface";
    }

    @Override
    public FaceRecognizer threshold(float threshold) {
        return FaceRecognizer.create(resolveModel()).threshold(threshold);
    }

    @Override
    public FaceRecognizer modelPath(String path) {
        return FaceRecognizer.create(resolveModel()).modelPath(path);
    }

    @Override
    public FaceRecognizer device(String device) {
        return FaceRecognizer.create(resolveModel()).device(device);
    }

    @Override
    public float[] extractFeature(byte[] imageData) {
        return FaceRecognizer.create(resolveModel()).extractFeature(imageData);
    }

    @Override
    public float compare(float[] feature1, float[] feature2) {
        return FaceRecognizer.create(resolveModel()).compare(feature1, feature2);
    }

    @Override
    public List<FaceFeature> recognize(byte[] imageData, List<float[]> referenceFeatures) {
        return FaceRecognizer.create(resolveModel()).recognize(imageData, referenceFeatures);
    }

}
