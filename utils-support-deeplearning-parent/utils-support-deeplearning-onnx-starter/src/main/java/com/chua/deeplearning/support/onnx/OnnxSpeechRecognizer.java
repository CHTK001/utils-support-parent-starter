package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechRecognizer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxSpeechRecognizer implements SpeechRecognizer {

    private String modelName;
    private String lang = "zh";
    private String modelPath;
    private int sampleRate = 16000;
    private String device = "cpu";

    public OnnxSpeechRecognizer(String apiKey) {
    }

    @Override
    public SpeechRecognizer model(String model) {
        this.modelName = model;
        return this;
    }

    private String resolveModel() {
        return modelName != null ? modelName : "whisper";
    }

    @Override
    public SpeechRecognizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    public SpeechRecognizer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public SpeechRecognizer sampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    @Override
    public SpeechRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public String recognize(byte[] audioData) {
        return SpeechRecognizer.create(resolveModel()).lang(lang).modelPath(modelPath).sampleRate(sampleRate).device(device).recognize(audioData);
    }

    @Override
    public String recognize(byte[] audioData, String language) {
        return SpeechRecognizer.create(resolveModel()).lang(lang).modelPath(modelPath).sampleRate(sampleRate).device(device).recognize(audioData, language);
    }

}
