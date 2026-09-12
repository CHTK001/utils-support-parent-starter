package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
   * ONNX 人脸检测引擎（SPI 提供者="onnx"）。
 *
 * <p>用法：
 * <pre>{@code
 *   FaceDetector detector = FaceDetector.create("onnx", "")
 *       .model("scrfd-face-detector")
 *       .threshold(0.5f);
 *   List<PredictRectangle> faces = detector.detect(imageData);
 * }</pre>detect(imageData);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxFaceDetector implements FaceDetector {

    /** API 密钥 */
    /** API密钥 */
    private final String apiKey;
    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = DEFAULT_THRESHOLD;
    /** NMS 阈值 */
    /** NMS */
    private float nms = DEFAULT_NMS;
    /** 最小人脸尺寸 */
    /** 最小值face尺寸 */
    private int minFaceSize = DEFAULT_MIN_FACE_SIZE;
    /** 设备类型 */
    /** Device */
    private String device = DEFAULT_DEVICE;

    /**
      * 创建 onnxfacedetector 实例
     * @param apiKey API密钥
     */
    public OnnxFaceDetector(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    /** 模型 */
    public FaceDetector model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    /** 阈值 */
    public FaceDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** Nms */
    public FaceDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    @Override
    /** 最小值Face获取大小 */
    public FaceDetector minFaceSize(int size) {
        this.minFaceSize = size;
        return this;
    }

    @Override
    /** Device */
    public FaceDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Detect */
    public List<PredictRectangle> detect(byte[] imageData) {
        String name = modelName != null ? modelName : "scrfd-face-detector";
        return FaceDetector.create(name)
                .threshold(threshold)
                .nms(nms)
                .minFaceSize(minFaceSize)
                .device(device)
                .detect(imageData);
    }

    @Override
    /** detect信息 */
    public List<DetectionInfo> detectInfo(byte[] imageData) {
        return detect(imageData).stream()
                .map(r -> new DetectionInfo(
                        "face", r.confidence(),
                        r.x(), r.y(), r.width(), r.height()))
                .toList();
    }

    @Override
    /** Face计算数量 */
    public int faceCount(byte[] imageData) {
        return detect(imageData).size();
    }
}