package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageSegmenter;
import lombok.extern.slf4j.Slf4j;
/** @作者 CH */

@Slf4j
public class OnnxImageSegmenter implements ImageSegmenter {

    /** 模型名称 */
    private String modelName;
    /** 模型路径 */
    private String modelPath;
    /** 设备类型 */
    private String device = "cpu";

    /**
     * 创建 onnx镜像segmenter 实例
     * @param apiKey API密钥
     */
    public OnnxImageSegmenter(String apiKey) {
    }

    @Override
    /** 模型 */
    public ImageSegmenter model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型
     *
     * @return resolve模型的结果
     */
    private String resolveModel() {
        return modelName != null ? modelName : "fastsam";
    }

    @Override
    /** 模型路径 */
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


