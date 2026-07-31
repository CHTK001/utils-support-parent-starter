package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 图像检测器，检测图像中的目标物体并返回边界框和类别信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageDetector {

    /**
     * 创建图像检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */
    static ImageDetector create(String name) {
        return new DefaultImageDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建图像检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static ImageDetector create(String name, ModelSetting setting) {
        return new DefaultImageDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置检测阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    ImageDetector threshold(float threshold);

    /**
     * 设置 NMS 阈值。
     *
     * @param nms NMS 阈值
     * @return this
     */
    ImageDetector nms(float nms);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    ImageDetector modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    ImageDetector device(String device);

    /**
     * 检测目标。
     *
     * @param imageData 图像数据
     * @return 检测信息列表
     */
    List<DetectionInfo> detect(byte[] imageData);
}

/**
 * 默认图像检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultImageDetector implements ImageDetector {

    /**
     * 默认检测阈值。
     */
    private static final float DEFAULT_THRESHOLD = 0.5f;

    /**
     * 默认 NMS 阈值。
     */
    private static final float DEFAULT_NMS = 0.4f;

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
    private final ModelSetting setting;

    /**
     * 检测阈值。
     */
    private float threshold = DEFAULT_THRESHOLD;

    /**
     * NMS 阈值。
     */
    private float nms = DEFAULT_NMS;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 构造默认图像检测器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultImageDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public ImageDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public ImageDetector nms(float nms) {
        this.nms = nms;
        return this;
    }

    @Override
    public ImageDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public ImageDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DetectionInfo> detect(byte[] imageData) {
        ITranslator<byte[], List<DetectionInfo>> t =
                (ITranslator<byte[], List<DetectionInfo>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}