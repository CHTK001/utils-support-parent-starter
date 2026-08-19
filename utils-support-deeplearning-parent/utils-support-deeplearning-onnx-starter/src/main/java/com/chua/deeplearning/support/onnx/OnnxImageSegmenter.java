package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageSegmenter;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxImageSegmenter implements ImageSegmenter {

    /** 模型名称 */
    private String modelName;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
     * 创建 OnnxImageSegmenter 实例
     * @param apiKey apiKey
     */
    public OnnxImageSegmenter(String apiKey) {
    }

    @Override
    /** Model */
    public ImageSegmenter model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "fastsam";
    }

    @Override
    /** ModelPath */
    public ImageSegmenter modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Device */
    public ImageSegmenter device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Segment */
    public byte[] segment(byte[] imageData) {
        return ImageSegmenter.create(resolveModel()).modelPath(modelPath).device(device).segment(imageData);
    }

    @Override
    /** Segment */
    public byte[] segment(byte[] imageData, int targetClass) {
        return ImageSegmenter.create(resolveModel()).modelPath(modelPath).device(device).segment(imageData, targetClass);
    }

}


