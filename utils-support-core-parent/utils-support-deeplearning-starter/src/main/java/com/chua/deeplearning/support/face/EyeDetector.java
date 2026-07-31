package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 眼睛检测器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EyeDetector {

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
    private final ModelSetting setting;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = "cpu";

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
    public EyeDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public EyeDetector device(String device) {
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
}
