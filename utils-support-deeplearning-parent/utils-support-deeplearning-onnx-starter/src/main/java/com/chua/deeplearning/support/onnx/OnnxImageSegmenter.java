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

    public OnnxImageSegmenter(String apiKey) {
    }

    @Override
    public ImageSegmenter model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "fastsam";
    }

    @Override
    public ImageSegmenter modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public ImageSegmenter device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public byte[] segment(byte[] imageData) {
        return ImageSegmenter.create(resolveModel()).modelPath(modelPath).device(device).segment(imageData);
    }

    @Override
    public byte[] segment(byte[] imageData, int targetClass) {
        return ImageSegmenter.create(resolveModel()).modelPath(modelPath).device(device).segment(imageData, targetClass);
    }

}


