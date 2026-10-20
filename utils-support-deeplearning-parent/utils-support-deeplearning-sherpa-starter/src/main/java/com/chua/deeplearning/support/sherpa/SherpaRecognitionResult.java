package com.chua.deeplearning.support.sherpa;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * sherpa-onnx 离线识别结果。
 *
 * <p>在 sherpa-onnx 原生结果（文本 / token / 时间戳 / 语种 / 情感 / 事件）之上，
 * 补充了本模块聚合出的<b>分段列表</b>：当启用 VAD 分段识别时，每段音频对应一条
 * {@link Segment}，其起止时间已换算为「相对整段音频」的绝对秒数，可直接用于字幕生成。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class SherpaRecognitionResult {

    /**
     * 完整识别文本（多段结果按顺序拼接，段间以换行分隔）。
     */
    private String text;

    /**
     * 识别到的语种（SenseVoice 等模型输出，其它模型可能为空）。
     */
    private String language;

    /**
     * 情感标签（SenseVoice 输出，如 NEUTRAL / HAPPY / SAD / ANGRY）。
     */
    private String emotion;

    /**
     * 音频事件标签（SenseVoice 输出，如 Speech / BGM / Laughter / Applause）。
     */
    private List<String> events;

    /**
     * 分段识别结果。
     */
    private List<Segment> segments;

    /**
     * 使用的模型族。
     */
    private SherpaModelType modelType;

    /**
     * 音频总时长（秒）。
     */
    private double duration;

    /**
     * 推理耗时（毫秒）。
     */
    private long elapsedMillis;

    /**
     * 识别分段。
     */
    @Data
    @Builder
    public static class Segment {

        /**
         * 段序号（从 0 开始）。
         */
        private int index;

        /**
         * 段起始时间（秒，相对整段音频）。
         */
        private double start;

        /**
         * 段结束时间（秒，相对整段音频）。
         */
        private double end;

        /**
         * 该段文本。
         */
        private String text;

        /**
         * 该段的 token 序列（模型词表索引对应的字符串）。
         */
        private String[] tokens;

        /**
         * 该段 token 时间戳（秒，相对该段起点）。
         */
        private float[] timestamps;

        /**
         * 该段识别语种。
         */
        private String language;

        /**
         * 该段情感标签。
         */
        private String emotion;

        /**
         * 该段音频事件标签。
         */
        private List<String> events;
    }
}
