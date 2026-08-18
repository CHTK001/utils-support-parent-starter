package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.PedestrianDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxPedestrianDetector implements PedestrianDetector {

    /** 模型名称 */
    /** 模型名称 */
    private String modelName;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    public OnnxPedestrianDetector(String apiKey) {
    }

    @Override
    public PedestrianDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov8n-ppe";
    }

    @Override
    public PedestrianDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(resolveModel()).device(device).detect(imageData);
    }

}
