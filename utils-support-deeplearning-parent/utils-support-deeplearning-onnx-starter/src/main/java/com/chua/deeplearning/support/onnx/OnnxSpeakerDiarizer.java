package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.audio.SpeakerDiarizer;
import com.chua.deeplearning.support.audio.SpeakerSegment;
import java.nio.file.Path;
import java.util.List;

/**
 * ONNX 说话人分离器（SPI 提供者="onnx"）。
 *
 * <p>以 {@code SpeakerDiarizer} 为能力的已注册模型不止一个，无法约定单一默认值，
 * 因此必须通过 {@code .model("模型ID")} 显式指定；未指定时抛出异常，
 * 不返回空分段冒充分离成功。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxSpeakerDiarizer implements SpeakerDiarizer {

    /**
     * 默认运行设备
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 分离模型名称
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
     * 最大说话人数，为空表示沿用被委托实现的默认值
     */
    private Integer maxSpeakers;

    /**
     * 构造 ONNX 说话人分离器。
     *
     * @param apiKey 访问密钥，本地引擎忽略该参数
     */
    public OnnxSpeakerDiarizer(String apiKey) {
    }

    /**
     * 指定分离模型标识。
     *
     * @param model 模型标识，须已在模型注册表中登记
     * @return 当前实例，支持链式调用
     */
    @Override
    public SpeakerDiarizer model(String model) {
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
    public SpeakerDiarizer modelPath(String path) {
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
    public SpeakerDiarizer device(String device) {
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
    public SpeakerDiarizer sampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    /**
     * 指定可区分的最大说话人数。
     *
     * @param maxSpeakers 最大说话人数
     * @return 当前实例，支持链式调用
     */
    @Override
    public SpeakerDiarizer maxSpeakers(int maxSpeakers) {
        this.maxSpeakers = maxSpeakers;
        return this;
    }

    /**
     * 对音频字节做说话人分离。
     *
     * @param audioData 音频原始字节，通常为 WAV 或 PCM
     * @return 按时间排列的说话人分段列表
     */
    @Override
    public List<SpeakerSegment> diarize(byte[] audioData) {
        return delegate().diarize(audioData);
    }

    /**
     * 对音频文件做说话人分离。
     *
     * @param path 音频文件路径
     * @return 按时间排列的说话人分段列表
     */
    @Override
    public List<SpeakerSegment> diarize(Path path) {
        return delegate().diarize(path);
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
                    + SpeakerDiarizer.listModels());
        }
        return modelName;
    }

    /**
     * 按当前配置构建被委托的分离器实例。
     *
     * @return 已完成配置的分离器实例
     * @throws IllegalStateException 未指定模型标识时抛出
     */
    private SpeakerDiarizer delegate() {
        SpeakerDiarizer target = SpeakerDiarizer.create(resolveModel())
                .modelPath(modelPath)
                .device(device);
        if (sampleRate != null) {
            target = target.sampleRate(sampleRate);
        }
        if (maxSpeakers != null) {
            target = target.maxSpeakers(maxSpeakers);
        }
        return target;
    }
}
