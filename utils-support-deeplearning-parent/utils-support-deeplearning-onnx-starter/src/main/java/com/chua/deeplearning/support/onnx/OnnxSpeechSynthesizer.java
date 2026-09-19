package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.deeplearning.support.speech.SpeechSynthesizer;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 本地语音合成器实现。
 * <p>调度 {@link TextToAudioClient} 完成实际合成，
 * 支持 mms-tts-eng / pocket-tts / vits-icefall-zh 三个模型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxSpeechSynthesizer implements SpeechSynthesizer {

    /** 默认模型名称 */
    private static final String DEFAULT_MODEL = "mms-tts-eng";

    /** 模型名称 */
    private String modelName;

    /** 合成语言 */
    private String lang = "zh";

    /** 模型路径 */
    private String modelPath;

    /** 语速倍率 */
    private float speed = 1.0f;

    /** 音调倍率 */
    private float pitch = 1.0f;

    /** 运行设备 */
    private String device = "cpu";

    /**
    * 创建 onnx语音synthesizer 实例。
    *
    * @param apiKey API 密钥（本地引擎可留空）
    * @param model 模型
    */
    public OnnxSpeechSynthesizer(String apiKey) {
    }

    @Override
    public SpeechSynthesizer model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型。
     *
     * @return 结果字符串
     */
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
        TextToAudioClient client = TextToAudioClient.create("onnx", "");
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
            return client.synthesize(text);
        } finally {
            client.close();
        }
    }
}


