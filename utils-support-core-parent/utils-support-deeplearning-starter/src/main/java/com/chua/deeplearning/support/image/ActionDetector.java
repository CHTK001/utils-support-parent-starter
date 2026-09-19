package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.ActionDetectionResult;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 视频动作检测器。
 * <p>输入视频数据，输出视频中检测到的动作序列（含时间戳、类别、置信度和空间位置）。</p>
 * <p>支持 9 种常见动作：举手、吃喝、吸烟、打电话、玩手机、趴桌睡觉、跌倒、洗手、拍照。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ActionDetector {

    /**
     * 通过 SPI 创建实例。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static ActionDetector create(String provider, String apiKey) {
        return ServiceProvider.of(ActionDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 创建默认动作检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */
    static ActionDetector create(String name) {
        return new DefaultActionDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建动作检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static ActionDetector create(String name, ModelSetting setting) {
        return new DefaultActionDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * @return 模型 标识 列表
     */
    static List<String> listModels() {
        return ModelRegistry.getModelIdsByCapability(ActionDetector.class);
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default ActionDetector model(String model) {
        return this;
    }

    /**
     * 设置检测阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    ActionDetector threshold(float threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    ActionDetector modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    ActionDetector device(String device);

    /**
     * 检测视频中的动作。
     *
     * @param videoData 视频文件数据
     * @return 动作检测结果列表
     */
    List<ActionDetectionResult> detect(byte[] videoData);
}

/**
 * 默认动作检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 * @param videoData 视频数据
 * @return detect的结果
 * @param device device
 * @param model 模型
 */
class DefaultActionDetector implements ActionDetector {

    private static final float DEFAULT_THRESHOLD = 0.45f; // 默认阈值
    private static final String DEFAULT_DEVICE = "cpu"; // 默认device

    private final IdentificationEngine engine; // engine
    private final String modelName; // 模型名称
    private final ModelSetting setting; // setting
    private float threshold = DEFAULT_THRESHOLD; // 阈值
    private String modelPath; // 模型路径
    private String device = DEFAULT_DEVICE; // device

    /**
     * 构造方法，创建 DefaultActionDetector 实例。
     *
     * @param engine 引擎，不允许为 null
     * @param modelName 模型名称，不允许为 null
     * @param setting 方法入参 setting
     */
    DefaultActionDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public ActionDetector model(String model) {
        return this;
    /**
     * 阈值。
     * @param threshold 阈值
     * @return 阈值的结果
     */
    }

    @Override
    public ActionDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    /**
     * 模型路径。
     * @param path 路径
     * @return 模型路径的结果
     * @param videoData 视频数据
     * @param device device
     */
    }

    @Override
    public ActionDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public ActionDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ActionDetectionResult> detect(byte[] videoData) {
        ITranslator<byte[], Object> t =
                (ITranslator<byte[], Object>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = t.translate(videoData);
        if (result == null) {
            return List.of();
        }
        if (result instanceof List<?> list) {
            List<ActionDetectionResult> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof ActionDetectionResult adr) {
                    out.add(adr);
                }
            }
            return out;
        }
        throw new IllegalStateException("模型输出不是动作检测结果: " + modelName + " -> " + result.getClass());
    }
}
