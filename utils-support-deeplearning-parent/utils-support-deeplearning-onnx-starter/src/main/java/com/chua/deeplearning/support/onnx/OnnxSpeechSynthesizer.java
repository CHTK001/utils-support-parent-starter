package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechSynthesizer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxSpeechSynthesizer implements SpeechSynthesizer {

    private String modelName;
    private String lang = "zh";
    private String modelPath;
    private float speed = 1.0f;
    private float pitch = 1.0f;
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
