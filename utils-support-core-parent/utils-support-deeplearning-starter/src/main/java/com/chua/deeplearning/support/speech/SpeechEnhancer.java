package com.chua.deeplearning.support.speech;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 语音增强器（降噪）。
 * <p>
 * 输入带噪语音音频字节数组，输出降噪后的语音音频字节数组。
   * 支持 wav / pcm 等音频格式，具体格式由模型实现定义（如 DFSMN 模型处理 48khz 单声道）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SpeechEnhancer {

    /**
      * 通过 SPI 创建实例（提供者="onnx" 等）。
     *
     * @param provider 提供者 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static SpeechEnhancer create(String provider, String apiKey) {
        return ServiceProvider.of(SpeechEnhancer.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 创建默认语音增强器。
     *
     * @param name 模型名称
     * @return 增强器
     */
    static SpeechEnhancer create(String name) {
        return new DefaultSpeechEnhancer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建语音增强器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 增强器
     */
    static SpeechEnhancer create(String name, ModelSetting setting) {
        return new DefaultSpeechEnhancer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * @return 模型 标识 列表
     */
    static List<String> listModels() {
        return ModelRegistry.getModelIdsByCapability(SpeechEnhancer.class);
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default SpeechEnhancer model(String model) {
        return this;
    }

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    default SpeechEnhancer modelPath(String path) {
        return this;
    }

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    default SpeechEnhancer device(String device) {
        return this;
    }

    /**
     * 对音频执行语音增强（降噪）。
     *
     * @param audioData 输入音频字节数组（wav/pcm）
     * @return 输出音频字节数组（降噪后）
     */
    byte[] enhance(byte[] audioData);
}

/**
 * 默认语音增强器实现。
 *
 * @author CH
 * @since 4.0.0.42
 * @param audioData 音频数据
 * @return 增强的结果
 * @param device device
 * @param model 模型
 */
class DefaultSpeechEnhancer implements SpeechEnhancer {

    private static final String DEFAULT_DEVICE = "cpu"; // 默认device

    private final IdentificationEngine engine; // engine
    private final String modelName; // 模型名称
    private final ModelSetting setting; // setting
    private String modelPath; // 模型路径
    private String device = DEFAULT_DEVICE; // device

    DefaultSpeechEnhancer(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public SpeechEnhancer model(String model) {
        return this;
    /**
     * 模型路径。
     * @param path 路径
     * @return 模型路径的结果
     * @param audioData 音频数据
     * @param device device
     */
    }

    @Override
    public SpeechEnhancer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public SpeechEnhancer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] enhance(byte[] audioData) {
        ITranslator<byte[], Object> t =
                (ITranslator<byte[], Object>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object out = t.translate(audioData);
        if (out instanceof byte[] bytes) {
            return bytes;
        }
        throw new IllegalStateException("语音增强模型输出类型不支持: " + (out == null ? "null" : out.getClass().getName()));
    }
}