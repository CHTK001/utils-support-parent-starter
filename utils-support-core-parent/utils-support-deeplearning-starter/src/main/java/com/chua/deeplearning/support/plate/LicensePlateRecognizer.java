package com.chua.deeplearning.support.plate;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.DetectOptions;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 车牌识别器，从车牌图像中识别车牌号码和颜色信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface LicensePlateRecognizer {

    /**
     * 创建车牌识别器。
     *
     * @param name 模型名称
     * @return 识别器
     */

    /**
     * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static LicensePlateRecognizer create(String provider, String apiKey) {
        return ServiceProvider.of(LicensePlateRecognizer.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default LicensePlateRecognizer provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default LicensePlateRecognizer model(String model) {
        return this;
    }

    /**
     * 创建
     *
     * @param name 名称
     * @return 创建的结果
     */
    static LicensePlateRecognizer create(String name) {
        return new DefaultLicensePlateRecognizer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.plate.LicensePlateRecognizer.class);
    }


    /**
     * 创建车牌识别器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 识别器
     */
    static LicensePlateRecognizer create(String name, ModelSetting setting) {
        return new DefaultLicensePlateRecognizer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置识别阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    LicensePlateRecognizer threshold(float threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    LicensePlateRecognizer modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    LicensePlateRecognizer device(String device);

    /**
     * 识别车牌号码。
     *
     * @param imageData 车牌图像数据
     * @return 车牌号码
     */
    String recognize(byte[] imageData);

    /**
     * 识别车牌详细信息。
     *
     * @param imageData 车牌图像数据
     * @return 检测信息列表
     */
    List<DetectionInfo> recognizeDetail(byte[] imageData);

    /**
     * 识别车牌完整结果。
     *
     * @param imageData 车牌图像数据
     * @return 车牌结果（号码、颜色）
     */
    PlateResult recognizePlate(byte[] imageData);
}

/**
 * 默认车牌识别器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultLicensePlateRecognizer implements LicensePlateRecognizer {

    
    /**
     * 默认运行设备（CPU）。
     */
    private static final String DEFAULT_DEVICE = "cpu";

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
    * 识别阈值。
    */
    private Float threshold;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 构造默认车牌识别器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultLicensePlateRecognizer(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public LicensePlateRecognizer threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public LicensePlateRecognizer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public LicensePlateRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * Recognize
    *
    * @param imageData 镜像数据
    * @return recognize的结果
    */
    public String recognize(byte[] imageData) {
        return recognizePlate(imageData).plateNo();
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * recognizedetail
     *
     * @param imageData 镜像数据
     * @return recognizeDetail的结果
     */
    public List<DetectionInfo> recognizeDetail(byte[] imageData) {
        ITranslator<byte[], List<DetectionInfo>> t =
                (ITranslator<byte[], List<DetectionInfo>>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * recognize铭牌
     *
     * @param imageData 镜像数据
     * @return recognize铭牌的结果
     */
    public PlateResult recognizePlate(byte[] imageData) {
        ITranslator<byte[], PlateResult> t =
                (ITranslator<byte[], PlateResult>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
