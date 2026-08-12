package com.chua.common.support.ai.audio;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;

/**
 * AI 文字转语音（TTS）任务响应。
 *
 * <p>封装异步 TTS 任务的当前状态和结果数据。
 * 调用方通过 {@link TextToAudioClient#queryTask(String)} 获取此对象。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class TextToAudioResponse {

    /**
     * 任务状态枚举
     */
    public enum Status {

        /**
         * 任务已创建
         */
        PENDING,

        /**
         * 任务执行中
         */
        RUNNING,

        /**
         * 任务成功
         */
        SUCCESS,

        /**
         * 任务失败
         */
        FAILED
    }

    /**
     * 任务 ID
     */
    private String taskId;

    /**
     * 任务当前状态
     */
    private Status status;

    /**
     * 合成的音频字节
     *
     * <p>仅当状态为 {@link Status#SUCCESS} 时有效。
     */
    private byte[] audioBytes;

    /**
     * 音频格式
     *
     * <p>如 "wav"、"mp3"、"opus"。
     */
    private String format;

    /**
     * 音频时长（秒）
     */
    private Double duration;

    /**
     * 采样率（Hz）
     */
    private Integer sampleRate;

    /**
     * 实际使用的发音人
     */
    private String voice;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 任务进度百分比
     */
    private Integer progress;

    /**
     * 用量信息
     */
    private AiUsage usage;
}
