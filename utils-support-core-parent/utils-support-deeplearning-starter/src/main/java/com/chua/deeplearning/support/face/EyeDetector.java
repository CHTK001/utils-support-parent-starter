package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 眼睛检测器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EyeDetector {

    /**
     * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static EyeDetector create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(EyeDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default EyeDetector provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default EyeDetector model(String model) {
        return this;
    }


    /**
     * 创建眼睛检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */
    static EyeDetector create(String name) {
        return new DefaultEyeDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.face.EyeDetector.class);
    }


    /**
     * 创建眼睛检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static EyeDetector create(String name, ModelSetting setting) {
        return new DefaultEyeDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default EyeDetector modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default EyeDetector device(String device) {
        return this;
    }

    /**
     * 检测眼睛区域。
     *
     * @param imageData 图像字节数组
     * @return 眼睛框列表
     */
    List<PredictRectangle> detect(byte[] imageData);

    /**
     * 眼睛数量。
     *
     * @param imageData 图像字节数组
     * @return 数量
     */
    default int eyeCount(byte[] imageData) {
        return detect(imageData).size();
    }
}

/**
 * 默认眼睛检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultEyeDetector implements EyeDetector {

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
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = "cpu";

    /**
     * 构造方法，创建 DefaultEyeDetector 实例。
     *
     * @param engine 引擎，不允许为 null
     * @param modelName 模型名称，不允许为 null
     * @param setting 方法入参 setting
     */
    DefaultEyeDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    /** 模型路径 */
    public EyeDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public EyeDetector device(String device) {
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
                (ITranslator<byte[], List<PredictRectangle>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
