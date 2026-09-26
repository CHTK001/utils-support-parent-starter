package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.audio.AudioFingerprinter;
import java.nio.file.Path;

/**
 * ONNX 音频指纹提取器（SPI 提供者="onnx"）。
 *
 * <p>以 {@code AudioFingerprinter} 为能力的已注册模型不止一个，无法约定单一默认值，
 * 因此必须通过 {@code .model("模型ID")} 显式指定；未指定时抛出异常，
 * 不返回空特征冒充提取成功。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxAudioFingerprinter implements AudioFingerprinter {

    /**
     * 默认运行设备
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 指纹模型名称
     */
    private String modelName;

    /**
     * 模型路径
     */
    private String modelPath;

    /**
     * 运行设备
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 目标采样率，为空表示沿用被委托实现的默认值
     */
    private Integer sampleRate;

    /**
     * 是否执行 L2 归一化，为空表示沿用被委托实现的默认值
     */
    private Boolean normalize;

    /**
     * 构造 ONNX 音频指纹提取器。
     *
     * @param apiKey 访问密钥，本地引擎忽略该参数
     */
    public OnnxAudioFingerprinter(String apiKey) {
    }

    /**
     * 指定指纹模型标识。
     *
     * @param model 模型标识，须已在模型注册表中登记
     * @return 当前实例，支持链式调用
     */
    @Override
    public AudioFingerprinter model(String model) {
        this.modelName = model;
        return this;
    }

    /**
     * 指定模型权重路径。
     *
     * @param path 模型权重路径，为空表示按模型标识解析
     * @return 当前实例，支持链式调用
     */
    @Override
    public AudioFingerprinter modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    /**
     * 指定运行设备。
     *
     * @param device 设备标识，如 cpu、cuda
     * @return 当前实例，支持链式调用
     */
    @Override
    public AudioFingerprinter device(String device) {
        this.device = device;
        return this;
    }

    /**
     * 指定音频目标采样率。
     *
     * @param sampleRate 目标采样率，单位赫兹
     * @return 当前实例，支持链式调用
     */
    @Override
    public AudioFingerprinter sampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    /**
     * 指定是否对特征向量做 L2 归一化。
     *
     * @param normalize true 表示归一化
     * @return 当前实例，支持链式调用
     */
    @Override
    public AudioFingerprinter normalize(boolean normalize) {
        this.normalize = normalize;
        return this;
    }

    /**
     * 从音频字节中提取特征向量。
     *
     * @param audioData 音频原始字节，通常为 WAV 或 PCM
     * @return 特征向量
     */
    @Override
    public float[] extract(byte[] audioData) {
        return delegate().extract(audioData);
    }

    /**
     * 从音频文件中提取特征向量。
     *
     * @param path 音频文件路径
     * @return 特征向量
     */
    @Override
    public float[] extract(Path path) {
        return delegate().extract(path);
    }

    /**
     * 解析模型标识。
     *
     * @return 已显式指定的模型标识
     * @throws IllegalStateException 未指定模型标识时抛出
     */
    private String resolveModel() {
        if (modelName == null) {
            throw new IllegalStateException("未指定模型，请通过 .model(\"模型ID\") 显式指定，可用模型: "
                    + AudioFingerprinter.listModels());
        }
        return modelName;
    }

    /**
     * 按当前配置构建被委托的提取器实例。
     *
     * @return 已完成配置的提取器实例
     * @throws IllegalStateException 未指定模型标识时抛出
     */
    private AudioFingerprinter delegate() {
        AudioFingerprinter target = AudioFingerprinter.create(resolveModel())
                .modelPath(modelPath)
                .device(device);
        if (sampleRate != null) {
            target = target.sampleRate(sampleRate);
        }
        if (normalize != null) {
            target = target.normalize(normalize);
        }
        return target;
    }
}
