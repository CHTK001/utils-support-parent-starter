package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageDetector implements ImageDetector {

    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = 0.5f;
    /** NMS 阈值 */
    private float nms = 0.4f;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    private String device = "cpu";

    public OnnxImageDetector(String apiKey) {
    }

    @Override
    public ImageDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "yolov8s";
    }

    @Override
    public ImageDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public ImageDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    @Override
    public ImageDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ImageDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(resolveModel()).threshold(threshold).nms(nms).modelPath(modelPath).device(device).detect(imageData);
    }

}


