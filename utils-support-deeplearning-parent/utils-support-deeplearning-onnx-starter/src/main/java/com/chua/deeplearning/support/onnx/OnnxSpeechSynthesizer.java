package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechSynthesizer;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 本地语音合成器实现。
 * <p>调度 {@link OnnxTextToAudioClient} 完成实际合成，
 * 支持 mms-tts-eng / pocket-tts / vits-icefall-zh 三个模型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxSpeechSynthesizer implements SpeechSynthesizer {

    /** 默认模型 */
    private static final String DEFAULT_MODEL = "mms-tts-eng";

    /** 模型名称 */
    private String modelName;
    /** 语言 */
    private String lang = "zh";
    /** 模型路径 */
    private String modelPath;
    /** 语速 */
    private float speed = 1.0f;
    /** 音调 */
    private float pitch = 1.0f;
    /** 设备类型 */
    private String device = "cpu";

    /**
     * 创建 OnnxSpeechSynthesizer 实例
     *
     * @param apiKey API 密钥（本地引擎可留空）
     */
    public OnnxSpeechSynthesizer(String apiKey) {
    }

    @Override
    public SpeechSynthesizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null && !modelName.isBlank() ? modelName : DEFAULT_MODEL;
    }

    @Override
    public SpeechSynthesizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    public SpeechSynthesizer modelPath(String modelPath) {
        this.modelPath = modelPath;
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
    public SpeechSynthesizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public byte[] synthesize(String text) {
        com.chua.common.support.ai.audio.TextToAudioClient client =
                com.chua.common.support.ai.audio.TextToAudioClient.create("onnx", "");
        try {
            client.model(resolveModel());
            if (!lang.isEmpty()) {
                client.language(lang);
            }
            if (speed != 1.0f) {
                client.speed((double) speed);
            }
            if (pitch != 1.0f) {
                client.temperature((double) pitch);
            }
            if (modelPath != null && !modelPath.isBlank()) {
                // modelPath 通过 setting 传入时由 OnnxTextToAudioClient 处理
            }
            return client.synthesize(text);
        } finally {
            client.close();
        }
    }
}


