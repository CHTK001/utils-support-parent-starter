package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechSynthesizer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxSpeechSynthesizer implements SpeechSynthesizer {

    private String modelName;

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
        return SpeechSynthesizer.create(resolveModel()).lang(lang);
    }

    @Override
    public SpeechSynthesizer modelPath(String path) {
        return SpeechSynthesizer.create(resolveModel()).modelPath(path);
    }

    @Override
    public SpeechSynthesizer device(String device) {
        return SpeechSynthesizer.create(resolveModel()).device(device);
    }

    @Override
    public SpeechSynthesizer speed(float speed) {
        return SpeechSynthesizer.create(resolveModel()).speed(speed);
    }

    @Override
    public SpeechSynthesizer pitch(float pitch) {
        return SpeechSynthesizer.create(resolveModel()).pitch(pitch);
    }

    @Override
    public byte[] synthesize(String text) {
        return SpeechSynthesizer.create(resolveModel()).synthesize(text);
    }

}
