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
    /** NMS */
    private float nms = 0.4f;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
     * 创建 OnnxImageDetector 实例
     * @param apiKey apiKey
     */
    public OnnxImageDetector(String apiKey) {
    }

    @Override
    /** Model */
    public ImageDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "yolov8s";
    }

    @Override
    /** Threshold */
    public ImageDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** Nms */
    public ImageDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    @Override
    /** ModelPath */
    public ImageDetector modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public ImageDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Detect */
    public List<DetectionInfo> detect(byte[] imageData) {
        return ImageDetector.create(resolveModel()).threshold(threshold).nms(nms).modelPath(modelPath).device(device).detect(imageData);
    }

}


