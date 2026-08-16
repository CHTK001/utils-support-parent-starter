package com.chua.deeplearning.support.speech;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;

/**
 * 语音合成器，将文字转换为音频数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SpeechSynthesizer {

    /**
     * 创建语音合成器。
     *
     * @param name 模型名称
     * @return 合成器
     */
    static SpeechSynthesizer create(String name) {
        return new DefaultSpeechSynthesizer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.speech.SpeechSynthesizer.class);
    }


    /**
     * 创建语音合成器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 合成器
     */
    static SpeechSynthesizer create(String name, ModelSetting setting) {
        return new DefaultSpeechSynthesizer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置合成语言。
     *
     * @param lang 语言代码
     * @return this
     */
    SpeechSynthesizer lang(String lang);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    SpeechSynthesizer modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    SpeechSynthesizer device(String device);

    /**
     * 设置语速。
     *
     * @param speed 语速倍率
     * @return this
     */
    SpeechSynthesizer speed(float speed);

    /**
     * 设置音调。
     *
     * @param pitch 音调倍率
     * @return this
     */
    SpeechSynthesizer pitch(float pitch);

    /**
     * 合成语音。
     *
     * @param text 文字内容
     * @return 音频数据
     */
    byte[] synthesize(String text);
}

/**
 * 默认语音合成器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultSpeechSynthesizer implements SpeechSynthesizer {

    /**
     * 默认合成语言（中文）。
     */
    private static final String DEFAULT_LANG = "zh";

    /**
     * 默认运行设备（CPU）。
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 默认语速。
     */
    private static final float DEFAULT_SPEED = 1.0f;

    /**
     * 默认音调。
     */
    private static final float DEFAULT_PITCH = 1.0f;

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
     * 合成语言。
     */
    private String lang = DEFAULT_LANG;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 语速。
     */
    private float speed = DEFAULT_SPEED;

    /**
     * 音调。
     */
    private float pitch = DEFAULT_PITCH;

    /**
     * 构造默认语音合成器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultSpeechSynthesizer(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public SpeechSynthesizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    public SpeechSynthesizer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public SpeechSynthesizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public SpeechSynthesizer speed(float speed) {
        this.speed = speed;
        return this;
    }

    @Override
    public SpeechSynthesizer pitch(float pitch) {
        this.pitch = pitch;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] synthesize(String text) {
        ITranslator<String, byte[]> t =
                (ITranslator<String, byte[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(text);
    }
}
