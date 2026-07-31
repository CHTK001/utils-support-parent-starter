package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

/**
 * 深度估计器。
 * <p>输入图像，输出深度图（图像字节数组）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DepthEstimator {

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
    public DepthEstimator modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public DepthEstimator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] estimate(byte[] imageData) {
        ITranslator<byte[], byte[]> t =
                (ITranslator<byte[], byte[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
