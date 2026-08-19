package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 深度估计器。
 * <p>输入图像，输出深度图（图像字节数组）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DepthEstimator {

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static DepthEstimator create(String provider, String apiKey) {
        return com.chua.common.support.spi.ServiceProvider.of(DepthEstimator.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default DepthEstimator provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default DepthEstimator model(String model) {
        return this;
    }


    /**
     * 创建深度估计器。
     *
     * @param name 模型名称
     * @return 估计器
     */
    static DepthEstimator create(String name) {
        return new DefaultDepthEstimator(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.DepthEstimator.class);
    }


    /**
     * 创建深度估计器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 估计器
     */
    static DepthEstimator create(String name, ModelSetting setting) {
        return new DefaultDepthEstimator(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default DepthEstimator modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default DepthEstimator device(String device) {
        return this;
    }

    /**
     * 估计深度图。
     *
     * @param imageData 输入图像字节数组
     * @return 深度图字节数组
     */
    byte[] estimate(byte[] imageData);
}

/**
 * 默认深度估计器。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultDepthEstimator implements DepthEstimator {

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

    DefaultDepthEstimator(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    /** ModelPath */
    public DepthEstimator modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public DepthEstimator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** Estimate */
    public byte[] estimate(byte[] imageData) {
        ITranslator<byte[], byte[]> t =
                (ITranslator<byte[], byte[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
