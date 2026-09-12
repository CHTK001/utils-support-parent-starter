package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 行人检测器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PedestrianDetector {

    /**
      * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static PedestrianDetector create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(PedestrianDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
      * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default PedestrianDetector provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default PedestrianDetector model(String model) {
        return this;
    }


    /**
     * 创建行人检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */
    static PedestrianDetector create(String name) {
        return new DefaultPedestrianDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.PedestrianDetector.class);
    }


    /**
     * 创建行人检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static PedestrianDetector create(String name, ModelSetting setting) {
        return new DefaultPedestrianDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置置信度阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    default PedestrianDetector threshold(float threshold) {
        return this;
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default PedestrianDetector modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default PedestrianDetector device(String device) {
        return this;
    }

    /**
     * 检测行人。
     *
     * @param imageData 图像字节数组
     * @return 检测结果
     */
    List<DetectionInfo> detect(byte[] imageData);

    /**
     * 行人数量。
     *
     * @param imageData 图像字节数组
     * @return 数量
     */
    default int count(byte[] imageData) {
        return detect(imageData).size();
    }
}

/**
 * 默认行人检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultPedestrianDetector implements PedestrianDetector {

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
     * 置信度阈值。
     */
    private float threshold = 0.5f;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = "cpu";

    DefaultPedestrianDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public PedestrianDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public PedestrianDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public PedestrianDetector device(String device) {
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
    public List<DetectionInfo> detect(byte[] imageData) {
        ITranslator<byte[], List<DetectionInfo>> t =
                (ITranslator<byte[], List<DetectionInfo>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
