package com.chua.deeplearning.support.onnx.audio;

import com.chua.deeplearning.support.audio.AudioFingerprinter;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ONNX 引擎的音频指纹提取器 SPI 实现。
 *
 * <h2>职责</h2>
 * <p>本类是实现 {@link AudioFingerprinter} 接口的 ONNX 引擎专用实现，
 * 通过 {@link IdentificationEngine} 查找已注册的 wav2vec2 翻译器来执行推理。
 * 与 {@link com.chua.deeplearning.support.audio.DefaultAudioFingerprinter} 的区别在于：
 * 本类通过 SPI 体系接入，可直接通过 {@code AudioFingerprinter.create("onnx", "")} 创建。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // 方式一：通过 SPI 创建（推荐）
 *   AudioFingerprinter fp = AudioFingerprinter.create("onnx", "")
 *       .model("wav2vec2-zh");
 *   float[] vec = fp.extract(Path.of("audio.wav"));
 *
 *   // 方式二：直接使用 DefaultAudioFingerprinter
 *   AudioFingerprinter fp = AudioFingerprinter.create("wav2vec2-zh");
 * }</pre>
 *
 * <h2>配套模型要求</h2>
 * <p>本实现要求模型在 {@code OnnxModelRegistrar} 中以以下参数注册：
 * <ul>
 *   <li>{@code capabilityInterface} = {@code AudioFingerprinter.class}</li>
 *   <li>{@code inputType} = {@code byte[].class}</li>
 *   <li>{@code outputType} = {@code float[].class}</li>
 *   <li>{@code translatorClassName} = {@code Wav2Vec2FingerprintTranslator}</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class OnnxAudioFingerprinter implements AudioFingerprinter {

    /** 默认设备 */
    private static final String DEFAULT_DEVICE = "cpu";
    /** 默认采样率 */
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    /** 推理引擎 */
    private final IdentificationEngine engine;
    /** 模型 ID */
    private String modelName;
    /** 自定义模型路径 */
    private String modelPath;
    /** 推理设备 */
    private String device = DEFAULT_DEVICE;
    /** 目标采样率 */
    private int sampleRate = DEFAULT_SAMPLE_RATE;
    /** 是否 L2 归一化 */
    private boolean normalize = true;

    /**
     * 构造 ONNX 音频指纹提取器。
     *
     * @param apiKey API 密钥（本地引擎可传入空字符串）
     */
    public OnnxAudioFingerprinter(String apiKey) {
        this.engine = AbstractIdentificationEngine.getInstance();
    }

    @Override
    public AudioFingerprinter model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    public AudioFingerprinter modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public AudioFingerprinter device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public AudioFingerprinter sampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    @Override
    public AudioFingerprinter normalize(boolean normalize) {
        this.normalize = normalize;
        return this;
    }

    /**
     * 解析实际使用的模型名称。
     * 优先使用显式设置的 modelName，其次查询引擎中注册的模型列表。
     */
    private String resolveModel() {
        if (modelName != null && !modelName.isBlank()) {
            return modelName;
        }
        // 查询引擎中注册的 AudioFingerprinter 类型模型，取第一个
        var models = AudioFingerprinter.listModels();
        if (models.isEmpty()) {
            throw new IllegalStateException(
                    "未指定模型，且引擎中无已注册的 AudioFingerprinter 模型。" +
                            "请通过 .model(\"模型ID\") 显式指定，可用模型: " + models);
        }
        return models.get(0);
    }

    /**
     * 从音频字节数据中提取指纹特征向量。
     *
     * @param audioData 音频原始字节（WAV/PCM）
     * @return 归一化后的特征向量
     */
    @Override
    @SuppressWarnings("unchecked")
    public float[] extract(byte[] audioData) {
        String model = resolveModel();
        ITranslator<byte[], float[]> t =
                (ITranslator<byte[], float[]>) engine.get(model, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + model);
        }
        float[] features = t.translate(audioData);
        if (normalize && features != null) {
            return l2Normalize(features);
        }
        return features;
    }

    /**
     * 从文件路径提取指纹特征向量。
     *
     * @param path 音频文件路径
     * @return 归一化后的特征向量
     */
    @Override
    public float[] extract(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            return extract(data);
        } catch (Exception e) {
            throw new RuntimeException("读取音频文件失败: " + path, e);
        }
    }

    /**
     * L2 归一化：将向量缩放到单位模长。
     */
    private static float[] l2Normalize(float[] vec) {
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-8f) {
            return vec;
        }
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            result[i] = vec[i] / norm;
        }
        return result;
    }
}
