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
     * 人脸检测权重在模型根下的目录段。
     *
     * <p>刻意用 {@code face/detection/} 而不是更宽松的 {@code face/}：后者会把
     * {@code face/attribute/genderage}、{@code face/landmark/2d106det} 一并算进来
     * —— 它们确实是「人脸权重」，但不是检测器（写这条规则时被测试抓到过）。
     * 本方法只服务于「人脸检测器清单」，所以按检测器实际所在的目录段判定；
     * 本仓 6 个人脸检测器权重（SCRFD / RetinaFace / TinaFace / YOLO-Face /
     * FacePlugin / UltraFace）全部落在 {@code face/detection/} 下。</p>
     *
     * <p>同时用路径段而不是子串匹配：{@code vision/face-mask-detector/} 与
     * {@code vision/detection/anime-face/} 都不含独立的 {@code face/detection/} 段，
     * 不会被误纳。</p>
     */
    String FACE_WEIGHT_SEGMENT = "face/detection/";

    /**
     * 权重路径是否是人脸检测模型。
     *
     * @param relativePath 注册表里声明的模型权重相对路径，可为 空
     * @return true 表示是人脸检测权重
     */
    static boolean isFaceWeightPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/').toLowerCase();
        // 头部与中间两种形态都要覆盖：face/detection/... 与 models/onnx/face/detection/...
        return normalized.startsWith(FACE_WEIGHT_SEGMENT) || normalized.contains("/" + FACE_WEIGHT_SEGMENT);
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * <p><b>为什么要补一路 {@code ImageDetector}</b>：注册表里「人脸检测」是用两个
     * 能力位标的 —— 只有 {@code opencv-face} 声明了 {@link FaceDetector}，而真正可用的
     * 人脸检测器（SCRFD / RetinaFace / TinaFace / YOLO-Face / FacePlugin）声明的都是
     * {@code ImageDetector}。只按单一能力位枚举会让页面/提示词里只剩一个
     * {@code opencv-face}：它的权重在模型仓里取不到，选中即报「模型文件不存在」，
     * 用户看着一份「不可用清单」却无从下手。</p>
     *
     * <p>能力位是注册时的**标签**，与 translator 的类型继承无关（translator 实现的是
     * {@code Translator}），所以这里只是把清单补全，不改变任何模型的创建路径。</p>
     *
     * @return 模型 标识 列表
     */
    static List<String> listModels() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(
                com.chua.deeplearning.support.engine.ModelRegistry
                        .getModelIdsByCapability(com.chua.deeplearning.support.face.FaceDetector.class));
        com.chua.deeplearning.support.engine.ModelRegistry
                .getAllByCapability(com.chua.deeplearning.support.image.ImageDetector.class)
                .stream()
                .filter(entry -> isFaceWeightPath(entry.relativePath()))
                .map(com.chua.deeplearning.support.engine.ModelRegistry.Entry::modelId)
                .forEach(ids::add);
        return List.copyOf(ids);
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

    /**
     * 阈值
    */
    @Override
    public FaceDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    /**
     * Nms
    */
    @Override
    public FaceDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    /**
     * 最小值Face获取大小
    */
    @Override
    public FaceDetector minFaceSize(int size) {
        this.minFaceSize = size;
        return this;
    }

    /**
     * 模型路径
    */
    @Override
    public FaceDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    /**
     * Device
    */
    @Override
    public FaceDetector device(String device) {
        this.device = device;
        return this;
    }

    /**
     * Detect
     *
     * @param imageData 镜像数据
     * @return detect的结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<PredictRectangle> detect(byte[] imageData) {
        ITranslator<byte[], List<PredictRectangle>> t =
                (ITranslator<byte[], List<PredictRectangle>>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    /**
     * detect信息
    */
    @Override
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

    /**
     * Face计算数量
    */
    @Override
    public int faceCount(byte[] imageData) {
        return detect(imageData).size();
    }
}
