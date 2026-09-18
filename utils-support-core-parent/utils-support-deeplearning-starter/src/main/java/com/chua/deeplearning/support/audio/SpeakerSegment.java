package com.chua.deeplearning.support.audio;

/**
* 音频片段数据模型，说话人分离（Diarization）结果的原子单元。
*
* <p>每个 {@link SpeakerSegment} 表示一段音频中归属于同一说话人的连续时间段，
* 包含说话人标识、起止时间戳、可选转录文本以及置信度。</p>
*
* <h3>字段说明</h3>
* <ul>
*   <li>{@code speakerId} — 说话人唯一标识，格式通常为 "speaker_0"、"speaker_1" 等；
* 同一说话人的不同时间段可能持有相同的 标识，便于后续聚类合并。</li>
*   <li>{@code startTimeMs} — 片段起始时间，单位毫秒，相对于音频首帧。</li>
*   <li>{@code endTimeMs} — 片段结束时间，单位毫秒（闭区间，含该时刻采样）。</li>
*   <li>{@code transcript} — 该片段经 ASR 转写后的文本，可为 {@code null}（未做转写时）。</li>
*   <li>{@code confidence} — 该片段归属该说话人的置信度，范围 [0, 1]，默认 1.0。</li>
* </ul>
*
* <h3>典型使用场景</h3>
* <pre>{@code
*   List<SpeakerSegment> segments = speakerDiarizer.diarize(audioBytes);
*
*   for (SpeakerSegment seg : segments) {
*       System.out.printf("%s  [%,.0f ms - %,.0f ms] (%,.2f s)%n",
*               seg.speakerId(),
*               seg.startTimeMs(), seg.endTimeMs(),
*               seg.durationSec());
*       if (seg.transcript() != null) {
*           System.out.println("  文本: " + seg.transcript());
*       }
*   }
* }</pre>       System.out.println("  文本: " + seg.transcript());
*       }
*   }
* }</pre>
*
* <h3>注意事项</h3>
* <ul>
*   <li>返回的列表已按 {@code startTimeMs} 升序排列。</li>
*   <li>不同说话人的片段可能在时间上存在微小重叠（模型边界误差），调用方可根据业务需求决定合并策略。</li>
*   <li>{@code confidence} 仅为占位字段，当前嵌入式 VAD 方案固定返回 1.0；
* 未来接入基于 嵌入 的说话人验证后可填充真实置信度。</li>
* </ul>
*
* @param speakerId    说话人标识
* @param startTimeMs  片段起始时间（毫秒）
* @param endTimeMs    片段结束时间（毫秒）
* @param transcript   该片段转录文本，可为 空
* @param confidence   置信度，范围 [0, 1]，默认 1.0
* @author CH
* @since 4.0.0.43
 */
public record SpeakerSegment(
        /** 说话人标识（如 "speaker_0"） */
        String speakerId,
        /** 片段起始时间（毫秒，相对音频首帧） */
        long startTimeMs,
        /** 片段结束时间（毫秒，闭区间） */
        long endTimeMs,
        /** 该片段经 ASR 转写后的文本，可为 空 */
        String transcript,
        /** 该片段归属该说话人的置信度，范围 [0, 1] */
        float confidence
) {
    /**
        * 便捷构造器，省略置信度参数，默认取 1.0。
        *
        * @param speakerId    说话人标识
        * @param startTimeMs  起始时间（毫秒）
        * @param endTimeMs    结束时间（毫秒）
        * @param transcript   转录文本，可为 空
        * @return SpeakerSegment的结果
        */
    public SpeakerSegment(String speakerId, long startTimeMs, long endTimeMs, String transcript) {
        this(speakerId, startTimeMs, endTimeMs, transcript, 1.0f);
    }

    /**
    * 全参紧凑构造器：置信度为 nan 时回退为 1.0。
    */
    public SpeakerSegment {
        confidence = Float.isNaN(confidence) ? 1.0f : confidence;
    }

    /**
    * 返回该片段的持续时间（毫秒）。
    *
    * @return 时长（endTimeMs - 启动时间ms），若结束时间早于起始时间则返回 0
    */
    public long durationMs() {
        return Math.max(0L, endTimeMs - startTimeMs);
    }

    /**
    * 返回该片段的持续时间（秒，保留两位小数）。
    *
    * @return 时长（秒），精度到 0.01s
    */
    public double durationSec() {
        return Math.round(durationMs() / 10.0) / 100.0;
    }

    /**
    * 判断两个片段在时间轴上是否重叠。
    *
    * @param other 待比较的另一个片段
    * @return {@code true} 表示两个片段的时间区间存在交集
    */
    public boolean overlapsWith(SpeakerSegment other) {
        return this.startTimeMs < other.endTimeMs && this.endTimeMs > other.startTimeMs;
    }

    /**
    * 判断该片段是否在给定时间范围内。
    *
    * @param startMs 查询区间起始（毫秒）
    * @param endMs   查询区间结束（毫秒）
    * @return {@code true} 表示该片段与 [启动ms, 结束ms] 有交集
    */
    public boolean intersects(long startMs, long endMs) {
        return this.startTimeMs < endMs && this.endTimeMs > startMs;
    }
}
