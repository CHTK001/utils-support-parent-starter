package com.chua.deeplearning.support.pose;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 姿态估计器，检测人体关键点或物体姿态信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PoseEstimator {

    /**
     * 创建姿态估计器。
     *
     * @param name 模型名称
     * @return 估计器
     */

    /**
     * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static PoseEstimator create(String provider, String apiKey) {
        return ServiceProvider.of(PoseEstimator.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 提供者。
     *
     * @param provider 提供者 名称
     * @return this
     */
    default PoseEstimator provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default PoseEstimator model(String model) {
        return this;
    }

    /**
     * 创建
     *
     * @param name 名称
     * @return 创建的结果
     */
    static PoseEstimator create(String name) {
        return new DefaultPoseEstimator(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.pose.PoseEstimator.class);
    }


    /**
     * 创建姿态估计器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 估计器
     */
    static PoseEstimator create(String name, ModelSetting setting) {
        return new DefaultPoseEstimator(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置检测阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    PoseEstimator threshold(float threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    PoseEstimator modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    PoseEstimator device(String device);

    /**
     * 估计单人姿态。
     *
     * @param imageData 图像数据
     * @return 关键点列表
     */
    List<PoseKeypoint> estimate(byte[] imageData);

    /**
     * 估计多人姿态。
     *
     * @param imageData 图像数据
     * @return 多人关键点列表
     */
    List<List<PoseKeypoint>> estimateMulti(byte[] imageData);
}

/**
 * 默认姿态估计器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultPoseEstimator implements PoseEstimator {

    /**
     * 默认检测阈值。
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
    /** 设置 */
    private final ModelSetting setting;

    /**
     * 检测阈值。
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
     * 构造默认姿态估计器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultPoseEstimator(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public PoseEstimator threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public PoseEstimator modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public PoseEstimator device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * Estimate
     *
     * @param imageData 镜像数据
     * @return estimate的结果
     */
    public List<PoseKeypoint> estimate(byte[] imageData) {
        ITranslator<byte[], List<PoseKeypoint>> t =
                (ITranslator<byte[], List<PoseKeypoint>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * estimatemulti
     *
     * @param imageData 镜像数据
     * @return estimateMulti的结果
     */
    public List<List<PoseKeypoint>> estimateMulti(byte[] imageData) {
        ITranslator<byte[], List<List<PoseKeypoint>>> t =
                (ITranslator<byte[], List<List<PoseKeypoint>>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
