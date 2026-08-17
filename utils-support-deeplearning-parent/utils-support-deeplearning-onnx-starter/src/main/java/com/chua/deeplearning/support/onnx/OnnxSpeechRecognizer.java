package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechRecognizer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnnxSpeechRecognizer implements SpeechRecognizer {

    private String modelName;

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
        return SpeechRecognizer.create(resolveModel()).lang(lang);
    }

    @Override
    public SpeechRecognizer modelPath(String path) {
        return SpeechRecognizer.create(resolveModel()).modelPath(path);
    }

    @Override
    public SpeechRecognizer device(String device) {
        return SpeechRecognizer.create(resolveModel()).device(device);
    }

    @Override
    public SpeechRecognizer sampleRate(int rate) {
        return SpeechRecognizer.create(resolveModel()).sampleRate(rate);
    }

    @Override
    public String recognize(byte[] audioData) {
        return SpeechRecognizer.create(resolveModel()).recognize(audioData);
    }

    @Override
    public String recognize(byte[] audioData, String language) {
        return SpeechRecognizer.create(resolveModel()).recognize(audioData, language);
    }

}
