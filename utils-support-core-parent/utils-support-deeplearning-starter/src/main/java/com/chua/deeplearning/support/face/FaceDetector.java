package com.chua.deeplearning.support.face;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.DetectOptions;
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
     * 通过 SPI 创建人脸检测器（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 检测器
     */
    static FaceDetector create(String provider, String apiKey) {
        return ServiceProvider.of(FaceDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 通过 SPI 创建人脸检测器，带 baseurl。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义地址
     * @return 检测器
     */
    static FaceDetector create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(FaceDetector.class)
                .getNewExtension(provider, apiKey, baseUrl);
    }

    /**
     * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default FaceDetector provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default FaceDetector model(String model) {
        return this;
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * <p>按能力接口从 {@link com.chua.deeplearning.support.engine.ModelRegistry} 枚举
     * 全部已注册模型，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 标识 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.face.FaceDetector.class);
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
    /** 设置 */
    private final ModelSetting setting;

    /**
     * 置信度阈值
     */
    private Float threshold;

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

    /**
     * 构造方法，创建 DefaultFaceDetector 实例。
     *
     * @param engine 引擎，不允许为 null
     * @param modelName 模型名称，不允许为 null
     * @param setting 方法入参 setting
     */
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
    /** 模型路径 */
    public FaceDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public FaceDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * Detect
     *
     * @param imageData 镜像数据
     * @return detect的结果
     */
    public List<PredictRectangle> detect(byte[] imageData) {
        ITranslator<byte[], List<PredictRectangle>> t =
                (ITranslator<byte[], List<PredictRectangle>>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    /** detect信息 */
    public List<DetectionInfo> detectInfo(byte[] imageData) {
        List<PredictRectangle> raw = detect(imageData);
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                // 检测置信度阈值过滤
                .filter(r -> r.confidence() >= threshold)
                // 最小人脸尺寸过滤（短边像素）
                .filter(r -> Math.min(r.width(), r.height()) >= minFaceSize)
                .map(r -> new DetectionInfo(
                        "face",
                        r.confidence(),
                        r.x(), r.y(),
                        r.width(), r.height()))
                .collect(Collectors.toList());
    }

    @Override
    /** Face计算数量 */
    public int faceCount(byte[] imageData) {
        return detect(imageData).size();
    }
}
