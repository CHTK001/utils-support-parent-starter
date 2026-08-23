package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.speech.SpeechEnhancer;
import lombok.extern.slf4j.Slf4j;

/**
 * ONNX 语音增强（降噪）实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxSpeechEnhancer implements SpeechEnhancer {

    private String modelName;
    private String modelPath;
    private String device = "cpu";

    /**
     * 创建 OnnxSpeechEnhancer 实例。
     *
     * @param apiKey API 密钥（本地引擎可空）
     */
    public OnnxSpeechEnhancer(String apiKey) {
    }

    @Override
    public SpeechEnhancer model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 解析模型名。
     *
     * @return 模型名
     */
    private String resolveModel() {
        return modelName != null ? modelName : "dfsmn-ans";
    }

    @Override
    public SpeechEnhancer modelPath(String modelPath) {
        this.modelPath = modelPath;
        return this;
    }

    @Override
    public SpeechEnhancer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public byte[] enhance(byte[] audioData) {
        return SpeechEnhancer.create(resolveModel()).modelPath(modelPath).device(device).enhance(audioData);
    }
}