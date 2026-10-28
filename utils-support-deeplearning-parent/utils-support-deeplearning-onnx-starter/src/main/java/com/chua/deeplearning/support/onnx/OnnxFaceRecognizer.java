package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceRecognizer;
import com.chua.deeplearning.support.face.FaceFeature;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/**
 * @作者 CH
*
* @author CH
* @since 4.0.0.42
*/

@Slf4j
public class OnnxFaceRecognizer implements FaceRecognizer {

    /**
     * 模型名称
    */
    private String modelName;
    /**
     * 阈值
    */
    private float threshold = 0.5f;
    /**
     * 模型路径
    */
    private String modelPath;
    /**
     * 设备类型
    */
    private String device = "cpu";

    /**
     * 创建 onnxfacerecognizer 实例
     * @param apiKey API密钥
     */
    public OnnxFaceRecognizer(String apiKey) {
    }

    /**
     * 模型
    */
    @Override
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
        if (modelName == null) {
            throw new IllegalStateException("未指定人脸特征模型，请通过 .model(\"模型ID\") 指定，"
                    + "或在系统配置 face 分组的 feature_model 中选择，可用模型: "
                    + FaceRecognizer.listModels());
        }
        return modelName;
    }

    /**
     * 阈值
    */
    @Override
    public FaceRecognizer threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    /**
     * 模型路径
    */
    @Override
    public FaceRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    /**
     * Device
    */
    @Override
    public FaceRecognizer device(String device) {
        this.device = device;
        return this;
    }

    /**
     * extract特征
    */
    @Override
    public float[] extractFeature(byte[] imageData) {
        return FaceRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).extractFeature(imageData);
    }

    /**
     * 比较
    */
    @Override
    public float compare(float[] feature1, float[] feature2) {
        return FaceRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).compare(feature1, feature2);
    }

    /**
     * Recognize
    */
    @Override
    public List<FaceFeature> recognize(byte[] imageData, List<float[]> referenceFeatures) {
        return FaceRecognizer.create(resolveModel()).threshold(threshold).modelPath(modelPath).device(device).recognize(imageData, referenceFeatures);
    }

}


