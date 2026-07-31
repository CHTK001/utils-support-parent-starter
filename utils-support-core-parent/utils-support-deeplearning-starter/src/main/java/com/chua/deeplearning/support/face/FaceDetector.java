package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 人脸检测器 —— 检测图片中的人脸位置与数量。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FaceDetector {

    /**
     * 默认置信度阈值
     */
    float DEFAULT_THRESHOLD = 0.5f;

    /**
     * 默认 NMS IOU 阈值
     */
    float DEFAULT_NMS = 0.4f;

    /**
     * 默认最小人脸尺寸
     */
    int DEFAULT_MIN_FACE_SIZE = 20;

    /**
     * 默认设备
     */
    String DEFAULT_DEVICE = "cpu";

    /**
     * 创建人脸检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */
    static FaceDetector create(String name) {
        return new DefaultFaceDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建人脸检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static FaceDetector create(String name, ModelSetting setting) {
        return new DefaultFaceDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置置信度阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    default FaceDetector threshold(float threshold) {
        return this;
    }

    /**
     * 设置 NMS IOU 阈值。
     *
     * @param nms 阈值
     * @return this
     */
    default FaceDetector nms(float nms) {
        return this;
    }

    /**
     * 设置最小人脸尺寸（像素）。
     *
     * @param size 最小尺寸
     * @return this
     */
    default FaceDetector minFaceSize(int size) {
        return this;
    }

    /**
     * 设置模型路径。
     *
     * @param path 模型路径
     * @return this
     */
    default FaceDetector modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备名称
     * @return this
     */
    default FaceDetector device(String device) {
        return this;
    }

    /**
     * 检测图像中的人脸位置。
     *
     * @param imageData 图像字节数组
     * @return 人脸位置列表
     */
    List<PredictRectangle> detect(byte[] imageData);

    /**
     * 检测图像中的人脸详细信息。
     *
     * @param imageData 图像字节数组
     * @return 检测信息列表
     */
    List<DetectionInfo> detectInfo(byte[] imageData);

    /**
     * 统计图像中的人脸数量。
     *
     * @param imageData 图像字节数组
     * @return 人脸数量
     */
    int faceCount(byte[] imageData);
}

/**
 * 默认人脸检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultFaceDetector implements FaceDetector {

    /**
     * 识别引擎
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称
     */
    private final String modelName;

    /**
     * 模型配置
     */
    @SuppressWarnings("unused")
    private final ModelSetting setting;

    /**
     * 置信度阈值
     */
    private float threshold = FaceDetector.DEFAULT_THRESHOLD;

    /**
     * NMS IOU 阈值
     */
    private float nms = FaceDetector.DEFAULT_NMS;

    /**
     * 最小人脸尺寸
     */
    private int minFaceSize = FaceDetector.DEFAULT_MIN_FACE_SIZE;

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 运行设备
     */
    private String device = FaceDetector.DEFAULT_DEVICE;

    DefaultFaceDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
        if (setting.getModelPath() != null) {
            this.modelPath = setting.getModelPath();
        }
        if (setting.getDevice() != null) {
            this.device = setting.getDevice();
        }
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
    public FaceDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public FaceDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PredictRectangle> detect(byte[] imageData) {
        ITranslator<byte[], List<PredictRectangle>> t =
                (ITranslator<byte[], List<PredictRectangle>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    public List<DetectionInfo> detectInfo(byte[] imageData) {
        return detect(imageData).stream()
                .map(r -> new DetectionInfo(
                        "face",
                        r.confidence(),
                        r.x(), r.y(),
                        r.width(), r.height()))
                .collect(Collectors.toList());
    }

    @Override
    public int faceCount(byte[] imageData) {
        return detect(imageData).size();
    }
}