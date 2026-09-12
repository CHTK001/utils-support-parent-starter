package com.chua.deeplearning.support.liveness;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.DetectOptions;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

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

    /**
    * 通过 SPI 创建实例（提供者="onnx" 等）。
    *
    * @param provider 提供者 名称
    * @param apiKey   API 密钥（本地引擎可空）
    * @return 实例
     */
    static LivenessDetector create(String provider, String apiKey) {
        return ServiceProvider.of(LivenessDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
    * 设置 提供者。
    *
    * @param provider 提供者 名称
    * @return this
     */
    default LivenessDetector provider(String provider) {
        return this;
    }

    /**
    * 设置模型名称。
    *
    * @param model 模型名称
    * @return this
     */
    default LivenessDetector model(String model) {
        return this;
    }

    /**
    * 创建
    *
    * @param name 名称
    * @return 创建的结果
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
    * @return 模型 标识 列表
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
    * 活体阈值。
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
    /** 阈值 */
    public LivenessDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** 模型路径 */
    public LivenessDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public LivenessDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * 是否Live
    *
    * @param imageData 镜像数据
    * @return 是否live的结果
     */
    public boolean isLive(byte[] imageData) {
        ITranslator<byte[], Object> t =
                (ITranslator<byte[], Object>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = t.translate(imageData);
 // 兼容 布尔值 / Float / 数字 输出（FLRGB 等返回活体分数）
        if (result instanceof Boolean bool) {
            return bool;
        }
        if (result instanceof Number number) {
            return number.floatValue() >= threshold;
        }
        if (result instanceof CharSequence cs) {
            String lower = cs.toString().toLowerCase();
            return lower.contains("live") || lower.contains("true") || lower.contains("真实")
                    || lower.contains("活体");
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * livescore
    *
    * @param imageData 镜像数据
    * @return liveScore的结果
     */
    public float liveScore(byte[] imageData) {
        ITranslator<byte[], Object> t =
                (ITranslator<byte[], Object>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = t.translate(imageData);
        if (result instanceof Number number) {
            return number.floatValue();
        }
        if (result instanceof Boolean bool) {
            return bool ? 1f : 0f;
        }
        return 0f;
    }
}
