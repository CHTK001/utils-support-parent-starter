package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.FaceQualityInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 人脸质量评估器。
 * <p>综合人脸存在性、尺寸、模糊度、亮度等指标评估人脸图片是否可用于识别。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FaceQualityAssessor {

    /**
     * 创建人脸质量评估器。
     *
     * @param name 模型名称
     * @return 评估器
     */

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static FaceQualityAssessor create(String provider, String apiKey) {
        return ServiceProvider.of(FaceQualityAssessor.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default FaceQualityAssessor provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default FaceQualityAssessor model(String model) {
        return this;
    }

    static FaceQualityAssessor create(String name) {
        return new DefaultFaceQualityAssessor(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * <p>按能力接口从 {@link com.chua.deeplearning.support.engine.ModelRegistry} 枚举
     * 全部已注册模型，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 ID 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.face.FaceQualityAssessor.class);
    }


    /**
     * 创建人脸质量评估器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 评估器
     */
    static FaceQualityAssessor create(String name, ModelSetting setting) {
        return new DefaultFaceQualityAssessor(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模糊阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    default FaceQualityAssessor blurThreshold(double threshold) {
        return this;
    }

    /**
     * 设置最小人脸占比。
     *
     * @param ratio 最小面积比 0~1
     * @return this
     */
    default FaceQualityAssessor minFaceRatio(float ratio) {
        return this;
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default FaceQualityAssessor modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default FaceQualityAssessor device(String device) {
        return this;
    }

    /**
     * 评估人脸质量。
     *
     * @param imageData 图像字节数组
     * @return 质量信息
     */
    FaceQualityInfo assess(byte[] imageData);

    /**
     * 是否通过质量检测。
     *
     * @param imageData 图像字节数组
     * @return true 表示质量合格
     */
    default boolean isAcceptable(byte[] imageData) {
        FaceQualityInfo info = assess(imageData);
        return info.faceOk() && info.sizeOk() && info.sharpnessOk() && info.brightnessOk();
    }
}

/**
 * 默认人脸质量评估器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultFaceQualityAssessor implements FaceQualityAssessor {

    /**
     * 识别引擎。
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称。
     */
    private final String modelName;

    /**
     * 模型配置。
     */
    @SuppressWarnings("unused")
    /** 设置 */
    private final ModelSetting setting;

    /**
     * 模糊阈值。
     */
    private double blurThreshold = 80.0;

    /**
     * 最小人脸面积比。
     */
    private float minFaceRatio = 0.05f;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = "cpu";

    DefaultFaceQualityAssessor(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public FaceQualityAssessor blurThreshold(double threshold) {
        this.blurThreshold = threshold;
        return this;
    }

    @Override
    public FaceQualityAssessor minFaceRatio(float ratio) {
        this.minFaceRatio = ratio;
        return this;
    }

    @Override
    public FaceQualityAssessor modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public FaceQualityAssessor device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public FaceQualityInfo assess(byte[] imageData) {
        ITranslator<byte[], FaceQualityInfo> t =
                (ITranslator<byte[], FaceQualityInfo>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
