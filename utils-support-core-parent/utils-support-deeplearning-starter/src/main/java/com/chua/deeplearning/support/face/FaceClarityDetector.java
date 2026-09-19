package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.FaceQualityInfo;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 人脸清晰度检测门面。
 *
 * <p>专注人脸清晰度评估单一职责：检测人脸存在性、尺寸、模糊度、亮度，判断人脸是否可用于后续识别。
 * 若人脸不清晰需修复，请使用独立的 {@link com.chua.deeplearning.support.image.ImageEnhancer} 门面。</p>
 *
 * <p>嵌入式场景推荐 OpenCV 后端（Haar 级联 + Laplacian 方差，haarcascade 已内嵌 jar，无需下载模型）。</p>
 *
 * <h2>用法示例</h2>
 * <pre>{@code
 * FaceClarityDetector detector = FaceClarityDetector.create("opencv", "");
 * FaceQualityInfo info = detector.blurThreshold(80.0).minFaceRatio(0.05f).assess(imageBytes);
 * if (info.sharpnessOk()) {
 *     // 人脸清晰
 * }
 * }</pre>*     // 人脸清晰
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FaceClarityDetector {

    /**
     * 通过 SPI 创建实例（提供者="opencv"/"onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static FaceClarityDetector create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(FaceClarityDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default FaceClarityDetector provider(String provider) {
        return this;
    }

    /**
     * 设置清晰度检测模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default FaceClarityDetector model(String model) {
        return this;
    }

    /**
     * 通过引擎创建实例（引擎自动发现已注册的模型）。
     *
     * @param name 模型名称（如 "opencv-face-quality"）
     * @return 实例
     */
    static FaceClarityDetector create(String name) {
        return new DefaultFaceClarityDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * @return 模型 标识 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry
                .getModelIdsByCapability(com.chua.deeplearning.support.face.FaceQualityAssessor.class);
    }

    /**
     * 通过引擎创建实例（指定模型配置）。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 实例
     */
    static FaceClarityDetector create(String name, ModelSetting setting) {
        return new DefaultFaceClarityDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模糊度阈值。
     *
     * @param threshold 阈值（Laplacian 方差低于该值视为模糊）
     * @return this
     */
    FaceClarityDetector blurThreshold(double threshold);

    /**
     * 设置最小人脸占比。
     *
     * @param ratio 最小面积比 0~1
     * @return this
     */
    FaceClarityDetector minFaceRatio(float ratio);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    FaceClarityDetector modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备（"cpu"/"cuda"）
     * @return this
     */
    FaceClarityDetector device(String device);

    /**
     * 评估人脸清晰度。
     *
     * @param imageData 图片字节数组
     * @return 人脸质量信息（含人脸数、尺寸、模糊度、亮度及是否合格）
     */
    FaceQualityInfo assess(byte[] imageData);

    /**
     * 人脸是否清晰。
     *
     * @param imageData 图片字节数组
     * @return true 表示人脸清晰且合格
     */
    default boolean isAcceptable(byte[] imageData) {
        FaceQualityInfo info = assess(imageData);
        return info.faceOk() && info.sizeOk() && info.sharpnessOk() && info.brightnessOk();
    }
}

/**
 * 默认人脸清晰度检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultFaceClarityDetector implements FaceClarityDetector {

    /**
     * 默认模糊阈值
     */
    private static final double DEFAULT_BLUR_THRESHOLD = 80.0;

    /**
     * 默认最小人脸面积比
     */
    private static final float DEFAULT_MIN_FACE_RATIO = 0.05f;

    /**
     * 默认设备
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 识别引擎
     */
    private final IdentificationEngine engine;

    /**
     * 清晰度检测模型名称
     */
    private final String modelName;

    /**
     * 模型配置
     */
    @SuppressWarnings("unused")
    private final ModelSetting setting; // setting

    /**
     * 模糊度阈值
     */
    private double blurThreshold = DEFAULT_BLUR_THRESHOLD;

    /**
     * 最小人脸面积比
     */
    private float minFaceRatio = DEFAULT_MIN_FACE_RATIO;

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 运行设备
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 构造方法，创建 DefaultFaceClarityDetector 实例。
     *
     * @param engine 引擎，不允许为 null
     * @param modelName 模型名称，不允许为 null
     * @param setting 方法入参 setting
     */
    DefaultFaceClarityDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    /**
     * blur阈值
    */
    public FaceClarityDetector blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    /**
     * 最小值faceratio
    */
    public FaceClarityDetector minFaceRatio(float ratio) {
        this.minFaceRatio = ratio;
        return this;
    }

    @Override
    /**
     * 模型路径
    */
    public FaceClarityDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /**
     * Device
    */
    public FaceClarityDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 评定
     *
     * @param imageData 镜像数据
     * @return 评定的结果
     */
    public FaceQualityInfo assess(byte[] imageData) {
        ITranslator<byte[], FaceQualityInfo> t =
                (ITranslator<byte[], FaceQualityInfo>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("清晰度检测模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
