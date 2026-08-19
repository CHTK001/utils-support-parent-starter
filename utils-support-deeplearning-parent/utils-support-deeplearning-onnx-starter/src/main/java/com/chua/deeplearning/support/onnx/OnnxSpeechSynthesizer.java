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

    public OnnxSpeechSynthesizer(String apiKey) {
    }

    @Override
    public SpeechSynthesizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "mms-tts-eng";
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
        return SpeechSynthesizer.create(resolveModel()).lang(lang).modelPath(modelPath).speed(speed).pitch(pitch).device(device).synthesize(text);
    }

}


