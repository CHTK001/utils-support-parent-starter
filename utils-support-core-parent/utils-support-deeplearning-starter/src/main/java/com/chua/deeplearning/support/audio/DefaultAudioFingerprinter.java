package com.chua.deeplearning.support.audio;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

/**
 * 音频指纹提取器的默认实现，委托给引擎中已注册的 {@code ITranslator<byte[], float[]>} 执行推理。
 *
 * <h2>工作原理</h2>
 * <ol>
 *   <li>启动时从 {@link IdentificationEngine} 中查找指定模型 ID 对应的翻译器。</li>
 *   <li>收到音频字节后，将数据交给翻译器执行 ONNX/TensorFlow 推理，得到原始特征向量。</li>
 *   <li>若开启了 L2 归一化（默认），对输出向量做归一化处理，使结果便于余弦相似度计算。</li>
 * </ol>
 *
 * <h2>模型注册要求</h2>
 * <p>本实现依赖模型在 {@link com.chua.deeplearning.support.engine.ModelRegistry} 中以
 * {@link AudioFingerprinter} 作为 {@code capabilityInterface} 注册。
 * 典型注册方式（在 {@code OnnxModelRegistrar} 中）：</p>
 * <pre>{@code
 *   reg("wav2vec2-zh",
 *       "com.chua.deeplearning.support.onnx.audio.Wav2Vec2FingerprintTranslator",
 *       byte[].class, float[].class,
 *       AudioFingerprinter.class,
 *       "audio/fingerprint/wav2vec2-zh/model.onnx",
 *       "https://huggingface.co/onnx-community/wav2vec2-large-xlsr-53-chinese-zh-cn-ONNX/resolve/main/model.onnx",
 *       false, null);
 * }</pre>
 *
 * <h2>输入音频格式要求</h2>
 * <ul>
 *   <li>推荐使用 16kHz 单声道 PCM 或 WAV 格式。</li>
 *   <li>支持 16-bit signed integer、8-bit unsigned、32-bit float 采样深度。</li>
 *   <li>若输入为多声道，自动混缩为单声道（各声道等权平均）。</li>
 *   <li>若采样率不为 16kHz，将使用线性插值重采样至目标采样率。</li>
 * </ul>
 *
 * <h2>归一化说明</h2>
 * <p>L2 归一化公式：{@code v_norm = v / ||v||_2}，其中 {@code ||v||_2 = sqrt(sum(v_i^2))}。
 * 归一化后向量满足 {@code ||v_norm||_2 = 1}，两向量点积 {@code v1·v2} 即为余弦相似度，
 * 取值范围 [-1, 1]，越接近 1 表示音频内容越相似。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class DefaultAudioFingerprinter implements AudioFingerprinter {

    /** 默认推理设备：CPU */
    private static final String DEFAULT_DEVICE = "cpu";
    /** 默认目标采样率：16kHz（大多数语音模型的标准输入采样率） */
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    /** 推理引擎实例（全局单例） */
    private final IdentificationEngine engine;
    /** 要使用的模型 ID */
    private final String modelName;
    /** 模型配置（来源：构造时传入） */
    @SuppressWarnings("unused")
    private final ModelSetting setting;
    /** 自定义模型路径（覆盖注册表中的默认路径） */
    private String modelPath;
    /** 推理设备："cpu" 或 "cuda" */
    private String device = DEFAULT_DEVICE;
    /** 目标采样率（Hz） */
    private int sampleRate = DEFAULT_SAMPLE_RATE;
    /** 是否对特征向量做 L2 归一化 */
    private boolean normalize = true;

    /**
    * 构造默认音频指纹提取器。
    *
    * @param engine    推理引擎实例
    * @param modelName 模型 ID（须在 ModelRegistry 中注册）
    * @param setting   模型配置（可包含 modelPath、device 等覆盖默认值）
    */
    public DefaultAudioFingerprinter(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
        // 若配置中提供了自定义路径，优先使用
        if (setting.getModelPath() != null) {
            this.modelPath = setting.getModelPath();
        }
        // 若配置中指定了设备，优先使用
        if (setting.getDevice() != null) {
            this.device = setting.getDevice();
        }
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
    * 从音频字节数据中提取指纹特征向量。
    *
    * <p>执行流程：
    * <ol>
    *   <li>从引擎中获取 {@code ITranslator<byte[], float[]>} 实例</li>
    *   <li>调用翻译器执行推理，得到原始特征向量</li>
    *   <li>若开启归一化，对向量做 L2 归一化处理</li>
    * </ol>
    * </p>
    *
    * @param audioData 音频原始字节（WAV/PCM）
    * @return 特征向量（归一化后模长为 1）
    * @throws IllegalStateException 若模型未在引擎中注册
    */
    @Override
    @SuppressWarnings("unchecked")
    public float[] extract(byte[] audioData) {
        // 从引擎中获取已注册的翻译器；若未注册则抛出明确异常并提示可用模型列表
        ITranslator<byte[], float[]> t =
                (ITranslator<byte[], float[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException(
                    "模型未注册: " + modelName +
                            "，可用模型: " + AudioFingerprinter.listModels() +
                            "，请确认已通过 OnnxModelRegistrar 注册且 capabilityInterface 为 AudioFingerprinter.class");
        }
        // 执行推理
        float[] features = t.translate(audioData);
        // 归一化处理
        if (normalize && features != null) {
            return l2Normalize(features);
        }
        return features;
    }

    /**
    * 从文件路径提取指纹特征向量。
    *
    * <p>内部先读取文件全部字节，再委托 {@link #extract(byte[])} 执行提取。</p>
    *
    * @param path 音频文件路径
    * @return 特征向量
    * @throws RuntimeException 若文件读取失败
    */
    @Override
    public float[] extract(java.nio.file.Path path) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(path);
            return extract(data);
        } catch (Exception e) {
            throw new RuntimeException("读取音频文件失败: " + path, e);
        }
    }

    /**
    * 对浮点向量执行 L2 归一化：将每个元素除以向量的欧氏范数。
    *
    * <p>公式：{@code result[i] = vec[i] / sqrt(sum(vec[j]^2))}</p>
    *
    * <p>若向量范数接近零（{@code < 1e-8}），说明输入为全零向量，直接原样返回，
    * 避免除以零产生 NaN。</p>
    *
    * @param vec 原始特征向量
    * @return L2 归一化后的向量，模长为 1
    */
    private static float[] l2Normalize(float[] vec) {
        // 第一步：计算向量的 L2 范数（各元素平方和的平方根）
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        // 防御：范数为零时直接返回原向量，避免除以零
        if (norm < 1e-8f) {
            return vec;
        }
        // 第二步：每个元素除以范数
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            result[i] = vec[i] / norm;
        }
        return result;
    }
}
