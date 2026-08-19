package com.chua.deeplearning.support.opencv.clarity;

import com.chua.deeplearning.support.image.ImageClarityDetector;
import com.chua.deeplearning.support.model.ImageQualityInfo;

/**
 * OpenCV 图片清晰度检测器（SPI provider="opencv"）。
 *
 * <p>默认使用 {@code opencv-image-quality} 引擎模型（Laplacian 方差），
 * 纯 OpenCV 算法，无需下载模型文件，嵌入式友好。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpencvImageClarityDetector implements ImageClarityDetector {

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
    public OpencvImageClarityDetector(String apiKey) {
    }

    @Override
    /** Model */
    public ImageClarityDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "opencv-image-quality";
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
