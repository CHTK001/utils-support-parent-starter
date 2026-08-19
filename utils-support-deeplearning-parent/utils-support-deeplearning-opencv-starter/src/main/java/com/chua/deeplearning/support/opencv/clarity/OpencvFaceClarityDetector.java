package com.chua.deeplearning.support.opencv.clarity;

import com.chua.deeplearning.support.face.FaceClarityDetector;
import com.chua.deeplearning.support.model.FaceQualityInfo;

/**
 * OpenCV 人脸清晰度检测器（SPI provider="opencv"）。
 *
 * <p>默认使用 {@code opencv-face-quality} 引擎模型（Haar 级联 + Laplacian 方差），
 * 纯 OpenCV 算法，无需下载模型文件，嵌入式友好。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpencvFaceClarityDetector implements FaceClarityDetector {

    /**
     * 默认人脸检测模型路径
     */
    private static final String FACE_MODEL_PATH = "models/opencv/haarcascade_frontalface_default.xml";

    /**
     * 清晰度检测模型名称
     */
    private String modelName;

    /**
     * 模糊度阈值
     */
    private double blurThreshold = 80.0;

    /**
     * 最小人脸面积比
     */
    private float minFaceRatio = 0.05f;

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
    public OpencvFaceClarityDetector(String apiKey) {
    }

    @Override
    public FaceClarityDetector model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "opencv-face-quality";
    }

    @Override
    public FaceClarityDetector blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    public FaceClarityDetector minFaceRatio(float ratio) {
        this.minFaceRatio = ratio;
        return this;
    }

    @Override
    public FaceClarityDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public FaceClarityDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public FaceQualityInfo assess(byte[] imageData) {
        return FaceClarityDetector.create(resolveModel())
                .blurThreshold(blurThreshold)
                .minFaceRatio(minFaceRatio)
                .modelPath(modelPath)
                .device(device)
                .assess(imageData);
    }

    @Override
    public boolean isAcceptable(byte[] imageData) {
        return assess(imageData).faceOk()
                && assess(imageData).sizeOk()
                && assess(imageData).sharpnessOk()
                && assess(imageData).brightnessOk();
    }
}
