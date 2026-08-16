package com.chua.deeplearning.support.liveness;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;

/**
 * 活体检测器，判断人脸图像是否为真实活体而非照片/视频攻击。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface LivenessDetector {

    /**
     * 创建活体检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */
    static LivenessDetector create(String name) {
        return new DefaultLivenessDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.liveness.LivenessDetector.class);
    }


    /**
     * 创建活体检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static LivenessDetector create(String name, ModelSetting setting) {
        return new DefaultLivenessDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置活体阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    LivenessDetector threshold(float threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    LivenessDetector modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    LivenessDetector device(String device);

    /**
     * 判断是否为活体。
     *
     * @param imageData 图像数据
     * @return true 表示活体
     */
    boolean isLive(byte[] imageData);

    /**
     * 获取活体分数。
     *
     * @param imageData 图像数据
     * @return 活体分数（0~1）
     */
    float liveScore(byte[] imageData);
}

/**
 * 默认活体检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultLivenessDetector implements LivenessDetector {

    /**
     * 默认活体阈值。
     */
    private static final float DEFAULT_THRESHOLD = 0.5f;

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
     * 活体阈值。
     */
    private float threshold = DEFAULT_THRESHOLD;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 构造默认活体检测器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultLivenessDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public LivenessDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    public LivenessDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public LivenessDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isLive(byte[] imageData) {
        ITranslator<byte[], Boolean> t =
                (ITranslator<byte[], Boolean>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    public float liveScore(byte[] imageData) {
        ITranslator<byte[], Float> t =
                (ITranslator<byte[], Float>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
