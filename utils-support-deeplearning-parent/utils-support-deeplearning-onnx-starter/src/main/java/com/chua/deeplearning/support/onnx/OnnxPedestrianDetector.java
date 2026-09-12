package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.PedestrianDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

@Slf4j
public class OnnxPedestrianDetector implements PedestrianDetector {

    /** 模型名称 */
    private String modelName;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
      * 创建 onnxpedestriandetector 实例
     * @param apiKey API密钥
     */
    public OnnxPedestrianDetector(String apiKey) {
    }

    @Override
    /** 模型 */
    public PedestrianDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "yolov8n-ppe";
    }

    @Override
    /** Device */
    public PedestrianDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Detect */
    public List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(resolveModel()).device(device).detect(imageData);
    }

}


