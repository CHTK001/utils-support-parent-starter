package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceRecognizer;
import com.chua.deeplearning.support.face.FaceFeature;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

@Slf4j
public class OnnxFaceRecognizer implements FaceRecognizer {

    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = 0.5f;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
     * 创建 onnxfacerecognizer 实例
     * @param apiKey API密钥
     */
    public OnnxFaceRecognizer(String apiKey) {
    }

    @Override
    /** 模型 */
    public FaceRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "arc-face";
    }

    @Override
    /** 阈值 */
    public FaceRecognizer threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public FaceRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public FaceRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** extract特征 */
    public float[] extractFeature(byte[] imageData) {
        return FaceRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).extractFeature(imageData);
    }

    @Override
    /** 比较 */
    public float compare(float[] feature1, float[] feature2) {
        return FaceRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).compare(feature1, feature2);
    }

    @Override
    /** Recognize */
    public List<FaceFeature> recognize(byte[] imageData, List<float[]> referenceFeatures) {
        return FaceRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).recognize(imageData, referenceFeatures);
    }

}


