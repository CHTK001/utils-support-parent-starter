package com.chua.deeplearning.support.speech;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

/**
 * 语音识别器，将音频数据转换为文字。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SpeechRecognizer {

    /**
     * 创建语音识别器。
     *
     * @param name 模型名称
     * @return 识别器
     */
    static SpeechRecognizer create(String name) {
        return new DefaultSpeechRecognizer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 创建语音识别器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 识别器
     */
    static SpeechRecognizer create(String name, ModelSetting setting) {
        return new DefaultSpeechRecognizer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置识别语言。
     *
     * @param lang 语言代码
     * @return this
     */
    SpeechRecognizer lang(String lang);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    SpeechRecognizer modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    SpeechRecognizer device(String device);

    /**
     * 设置采样率。
     *
     * @param rate 采样率（Hz）
     * @return this
     */
    SpeechRecognizer sampleRate(int rate);

    /**
     * 识别语音内容。
     *
     * @param audioData 音频数据
     * @return 识别文字
     */
    String recognize(byte[] audioData);

    /**
     * 识别语音内容，指定语言。
     *
     * @param audioData 音频数据
     * @param language  语言代码
     * @return 识别文字
     */
    String recognize(byte[] audioData, String language);
}

/**
 * 默认语音识别器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultSpeechRecognizer implements SpeechRecognizer {

    /**
     * 默认识别语言（中文）。
     */
    private static final String DEFAULT_LANG = "zh";

    /**
     * 默认运行设备（CPU）。
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 默认采样率（16kHz）。
     */
    private static final int DEFAULT_SAMPLE_RATE = 16000;

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
     * 识别语言。
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
     * 采样率。
     */
    private int sampleRate = DEFAULT_SAMPLE_RATE;

    /**
     * 构造默认语音识别器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultSpeechRecognizer(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public SpeechRecognizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    public SpeechRecognizer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public SpeechRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public SpeechRecognizer sampleRate(int rate) {
        this.sampleRate = rate;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String recognize(byte[] audioData) {
        ITranslator<byte[], String> t =
                (ITranslator<byte[], String>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(audioData);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String recognize(byte[] audioData, String language) {
        this.lang = language;
        return recognize(audioData);
    }
}