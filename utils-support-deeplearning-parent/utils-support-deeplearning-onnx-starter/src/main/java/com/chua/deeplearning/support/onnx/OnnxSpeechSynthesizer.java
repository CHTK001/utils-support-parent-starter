package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechSynthesizer;
import lombok.extern.slf4j.Slf4j;
/** @author CH */

@Slf4j
public class OnnxSpeechSynthesizer implements SpeechSynthesizer {

    /** 模型名称 */
    private String modelName;
    /** 语言 */
    /** Lang */
    private String lang = "zh";
    /** 模型路径 */
    private String modelPath;
    /** 速度 */
    /** Speed */
    private float speed = 1.0f;
    /** 音高 */
    /** Pitch */
    private float pitch = 1.0f;
    /** 设备类型 */
    /** Device */
    private String device = "cpu";

    /**
     * 创建 OnnxSpeechSynthesizer 实例
     * @param apiKey apiKey
     */
    public OnnxSpeechSynthesizer(String apiKey) {
    }

    @Override
    /** Model */
    public SpeechSynthesizer model(String model) {
        this.modelName = model;
        return this;
    }

    /** 解析Model */
    private String resolveModel() {
        return modelName != null ? modelName : "mms-tts-eng";
    }

    @Override
    /** Lang */
    public SpeechSynthesizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    /** ModelPath */
    public SpeechSynthesizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    /** Speed */
    public SpeechSynthesizer speed(float speed) {
        this.speed = speed;
        return this;
    }

    @Override
    /** Pitch */
    public SpeechSynthesizer pitch(float pitch) {
        this.pitch = pitch;
        return this;
    }

    @Override
    /** Device */
    public SpeechSynthesizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** Synthesize */
    public byte[] synthesize(String text) {
        return SpeechSynthesizer.create(resolveModel()).lang(lang).modelPath(modelPath).speed(speed).pitch(pitch).device(device).synthesize(text);
    }

}


