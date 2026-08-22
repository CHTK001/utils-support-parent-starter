package com.chua.deeplearning.support.onnx.audio;

import com.chua.deeplearning.support.audio.SpeakerDiarizer;
import com.chua.deeplearning.support.audio.SpeakerSegment;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ONNX 引擎的说话人分离 SPI 实现。
 *
 * <h2>职责</h2>
 * <p>本类是实现 {@link SpeakerDiarizer} 接口的 ONNX 引擎专用实现。
 * 当前版本委托给 {@link com.chua.deeplearning.support.audio.DefaultSpeakerDiarizer}
 * 执行基于能量 VAD 的时间切分（无需深度学习模型，零额外依赖）。</p>
 *
 * <h2>升级路径</h2>
 * <p>未来当接入 pyannote 等深度说话人分离模型后，可在此类中注入对应的翻译器，
 * 替换或增强当前的 VAD 切分逻辑。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // 通过 SPI 创建
 *   SpeakerDiarizer diarizer = SpeakerDiarizer.create("onnx", "")
 *       .model("energy-vad");  // 当前 VAD 方案不依赖具体模型 ID
 *
 *   List<SpeakerSegment> segments = diarizer.diarize(Path.of("meeting.wav"));
 *
 *   for (SpeakerSegment seg : segments) {
 *       System.out.printf("%s  %.2fs - %.2fs%n",
 *               seg.speakerId(),
 *               seg.startTimeMs() / 1000.0,
 *               seg.endTimeMs() / 1000.0);
 *   }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class OnnxSpeakerDiarizer implements SpeakerDiarizer {

    /** 默认设备 */
    private static final String DEFAULT_DEVICE = "cpu";
    /** 默认采样率 */
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    /** 推理引擎（预留，当前 VAD 不直接使用） */
    private final IdentificationEngine engine;
    /** 模型 ID（当前版本仅作标记，VAD 不依赖具体模型） */
    private String modelName;
    /** 自定义模型路径 */
    private String modelPath;
    /** 推理设备 */
    private String device = DEFAULT_DEVICE;
    /** 目标采样率 */
    private int sampleRate = DEFAULT_SAMPLE_RATE;
    /** 最大说话人数 */
    private Integer maxSpeakers;

    /**
     * 构造 ONNX 说话人分离器。
     *
     * @param apiKey API 密钥（本地引擎可传入空字符串）
     */
    public OnnxSpeakerDiarizer(String apiKey) {
        this.engine = AbstractIdentificationEngine.getInstance();
    }

    @Override
    public SpeakerDiarizer model(String model) {
        this.modelName = model;
        return this;
    }

    @Override
    public SpeakerDiarizer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public SpeakerDiarizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public SpeakerDiarizer sampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    @Override
    public SpeakerDiarizer maxSpeakers(int maxSpeakers) {
        this.maxSpeakers = maxSpeakers;
        return this;
    }

    /**
     * 对音频字节数据进行说话人分离（委托给 DefaultSpeakerDiarizer 执行）。
     *
     * @param audioData 音频原始字节（WAV/PCM）
     * @return 按时间排序的说话人片段列表
     */
    @Override
    public List<SpeakerSegment> diarize(byte[] audioData) {
        // 创建 VAD 分派器并传入当前配置
        var diarizer = new com.chua.deeplearning.support.audio.DefaultSpeakerDiarizer(
                engine, modelName != null ? modelName : "energy-vad",
                com.chua.deeplearning.support.config.ModelSetting.builder().build());
        diarizer.sampleRate(sampleRate);
        if (maxSpeakers != null) {
            diarizer.maxSpeakers(maxSpeakers);
        }
        return diarizer.diarize(audioData);
    }

    /**
     * 对音频文件进行说话人分离。
     *
     * @param path 音频文件路径
     * @return 说话人片段列表
     */
    @Override
    public List<SpeakerSegment> diarize(java.nio.file.Path path) {
        var diarizer = new com.chua.deeplearning.support.audio.DefaultSpeakerDiarizer(
                engine, modelName != null ? modelName : "energy-vad",
                com.chua.deeplearning.support.config.ModelSetting.builder().build());
        diarizer.sampleRate(sampleRate);
        if (maxSpeakers != null) {
            diarizer.maxSpeakers(maxSpeakers);
        }
        return diarizer.diarize(path);
    }
}
