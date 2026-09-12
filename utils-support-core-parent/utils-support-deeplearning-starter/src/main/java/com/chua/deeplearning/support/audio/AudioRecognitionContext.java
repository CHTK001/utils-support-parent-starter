package com.chua.deeplearning.support.audio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
* 音频识别上下文，管线中间状态的数据载体。
*
* <p>在 {@link AudioRecognitionPipeline} 的每一步处理中，将当前状态暂存于此对象，
* 供后续节点读取和写入。</p>
*
* @author CH
* @since 4.0.0.43
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioRecognitionContext {

    /** 原始音频字节（WAV/PCM） */
    private byte[] rawAudio;
    /** VAD 切分结果（语音/静音分段） */
    private List<SpeakerSegment> vadSegments;
    /** 各片段的说话人嵌入向量（与 vadsegments 一一对应） */
    private float[][] speakerEmbeddings;
    /** 各片段归属的说话人 标识（聚类后，与 vadsegments 一一对应） */
    private String[] speakerAssignments;
    /** 各片段的 ASR 转录文本 */
    private String[] transcripts;
    /** 最终合并后的说话人片段（经聚类合并连续同说话人片段） */
    private List<SpeakerSegment> finalSegments;
    /** 处理耗时（毫秒） */
    private long elapsedMs;
}
