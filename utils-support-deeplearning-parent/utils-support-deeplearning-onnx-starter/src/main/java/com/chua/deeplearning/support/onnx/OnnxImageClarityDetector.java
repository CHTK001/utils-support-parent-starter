package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.image.ImageClarityDetector;
import com.chua.deeplearning.support.model.ImageQualityInfo;

/**
 * ONNX 图片清晰度检测器（SPI provider="onnx"）。
 *
 * <p>默认使用 {@code nima} 模型（NIMA 图像质量评分）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxImageClarityDetector implements ImageClarityDetector {

    /**
     * 清晰度检测模型名称
     */
    private String modelName;

    /**
     * 模糊度阈值
     */
    private double blurThreshold = 100.0;

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 运行设备
     */
    private String device = "cpu";

    /**
     * SPI 构造函数。
     *
     * @param apiKey API 密钥（本地引擎忽略）
     */
    public OnnxImageClarityDetector(String apiKey) {
    }

    @Override
    /** Model */
    public ImageClarityDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "nima";
    }

    @Override
    /** BlurThreshold */
    public ImageClarityDetector blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    /** ModelPath */
    public ImageClarityDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public ImageClarityDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Assess */
    public ImageQualityInfo assess(byte[] imageData) {
        return ImageClarityDetector.create(resolveModel())
                .blurThreshold(blurThreshold)
                .modelPath(modelPath)
                .device(device)
                .assess(imageData);
    }
}
