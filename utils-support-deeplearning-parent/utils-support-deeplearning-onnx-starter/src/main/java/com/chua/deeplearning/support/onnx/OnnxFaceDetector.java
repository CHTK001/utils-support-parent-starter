package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ONNX 人脸检测引擎（SPI provider="onnx"）。
 *
 * <p>用法：
 * <pre>{@code
 *   FaceDetector detector = FaceDetector.create("onnx", "")
 *       .model("scrfd-face-detector")
 *       .threshold(0.5f);
 *   List<PredictRectangle> faces = detector.detect(imageData);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxFaceDetector implements FaceDetector {

    /** API 密钥 */
    private final String apiKey;
    /** 模型名称 */
    private String modelName;
    /** 阈值 */
    private float threshold = DEFAULT_THRESHOLD;
    /** NMS 阈值 */
    private float nms = DEFAULT_NMS;
    /** 最小人脸尺寸 */
    private int minFaceSize = DEFAULT_MIN_FACE_SIZE;
    /** 设备类型 */
    private String device = DEFAULT_DEVICE;

    public OnnxFaceDetector(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public FaceDetector model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    public FaceDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public FaceDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    @Override
    public FaceDetector minFaceSize(int size) {
        this.minFaceSize = size;
        return this;
    }

    @Override
    public FaceDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
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
    public List<DetectionInfo> detectInfo(byte[] imageData) {
        return detect(imageData).stream()
                .map(r -> new DetectionInfo(
                        "face", r.confidence(),
                        r.x(), r.y(), r.width(), r.height()))
                .toList();
    }

    @Override
    public int faceCount(byte[] imageData) {
        return detect(imageData).size();
    }
}