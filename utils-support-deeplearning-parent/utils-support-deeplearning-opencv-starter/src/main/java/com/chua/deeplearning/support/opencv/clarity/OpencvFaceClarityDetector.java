package com.chua.deeplearning.support.opencv.clarity;

import com.chua.deeplearning.support.face.FaceClarityDetector;
import com.chua.deeplearning.support.model.FaceQualityInfo;

/**
* 打开cv 人脸清晰度检测器（SPI 提供者="opencv"）。
*
* <p>默认使用 {@code opencv-face-quality} 引擎模型（Haar 级联 + Laplacian 方差），
* 纯 打开cv 算法，无需下载模型文件，嵌入式友好。</p>
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
    /** 模型 */
    public FaceClarityDetector model(String model) {
        this.modelName = model;
        return this;
    }

    /**
    * 解析模型
    *
    * @return resolve模型的结果
    */
    private String resolveModel() {
        return modelName != null ? modelName : "opencv-face-quality";
    }

    @Override
    /** blur阈值 */
    public FaceClarityDetector blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    /** 最小值faceratio */
    public FaceClarityDetector minFaceRatio(float ratio) {
        this.minFaceRatio = ratio;
        return this;
    }

    @Override
    /** 模型路径 */
    public FaceClarityDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public FaceClarityDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** 评定 */
    public FaceQualityInfo assess(byte[] imageData) {
        return FaceClarityDetector.create(resolveModel())
                .blurThreshold(blurThreshold)
                .minFaceRatio(minFaceRatio)
                .modelPath(modelPath)
                .device(device)
                .assess(imageData);
    }

    @Override
    /** 是否Acceptable */
    public boolean isAcceptable(byte[] imageData) {
        return assess(imageData).faceOk()
                && assess(imageData).sizeOk()
                && assess(imageData).sharpnessOk()
                && assess(imageData).brightnessOk();
    }
}
