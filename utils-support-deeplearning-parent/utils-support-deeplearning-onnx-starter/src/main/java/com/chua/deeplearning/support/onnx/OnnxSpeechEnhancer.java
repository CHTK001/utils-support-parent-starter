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

    private String modelName; // 模型名称
    private String modelPath; // 模型路径
    private String device = "cpu"; // device

    /**
     * 创建 onnx语音enhancer 实例。
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
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 指定，可用模型: "
                    + SpeechEnhancer.listModels());
        }
        return modelName;
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
