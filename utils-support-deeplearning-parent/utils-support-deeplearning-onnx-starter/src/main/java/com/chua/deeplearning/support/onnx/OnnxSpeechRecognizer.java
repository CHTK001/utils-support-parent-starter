package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechRecognizer;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 语音识别引擎（SPI provider="onnx"）。
 *
 * <p>注册表中无可用语音识别模型，必须通过 {@code .model("模型ID")} 显式指定
 * 已注册模型，否则抛出异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxSpeechRecognizer implements SpeechRecognizer {

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 语言
     */
    private String lang = "zh";

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 采样率
     */
    private int sampleRate = 16000;

    /**
     * 运行设备
     */
    private String device = "cpu";

    /**
     * 创建 OnnxSpeechRecognizer 实例
     * @param apiKey apiKey
     */
    public OnnxSpeechRecognizer(String apiKey) {
    }

    @Override
    /** Model */
    public SpeechRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 显式指定，可用模型: " + SpeechRecognizer.listModels());
        }
        return modelName;
    }

    @Override
    /** Lang */
    public SpeechRecognizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    /** ModelPath */
    public SpeechRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** SampleRate */
    public SpeechRecognizer sampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    @Override
    /** Device */
    public SpeechRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Recognize */
    public String recognize(byte[] audioData) {
        return SpeechRecognizer.create(resolveModel()).lang(lang).modelPath(modelPath).sampleRate(sampleRate).device(device).recognize(audioData);
    }

    @Override
    /** Recognize */
    public String recognize(byte[] audioData, String language) {
        return SpeechRecognizer.create(resolveModel()).lang(lang).modelPath(modelPath).sampleRate(sampleRate).device(device).recognize(audioData, language);
    }

}
