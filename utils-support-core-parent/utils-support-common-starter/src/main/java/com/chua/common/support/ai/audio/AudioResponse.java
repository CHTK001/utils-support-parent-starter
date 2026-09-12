package com.chua.common.support.ai.audio;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
* AI 语音识别任务响应
*
* <p>封装异步 ASR 任务的当前状态和结果数据。
* 调用方通过 {@link VirtualClient#queryTask(String)} 获取此对象以判断任务进度和获取转写文本。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class AudioResponse {

    /**
    * @author CH
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
    * 完整转写文本
    *
    * <p>仅当状态为 {@link Status#SUCCESS} 时有效。
     */
    private String transcript;

    /**
    * 分段转写结果
    *
    * <p>包含每个时间段的起止时间与文本，可用于字幕生成。
     */
    private List<Segment> segments;

    /**
    * 实际识别到的语言
    *
    * <p>可能与请求时指定的语言不同（如未指定则由模型自动检测）。
     */
    private String detectedLanguage;

    /**
    * 情感标签
    *
    * <p>由支持情感识别的模型（如 SenseVoice）返回；其他模型保持 null。
    * 常见值：NEUTRAL / HAPPY / SAD / ANGRY / FEARFUL / DISGUSTED / SURPRISED / EMO_UNKNOWN。
     */
    private String emotion;

    /**
    * 音频事件标签列表
    *
    * <p>由支持事件检测的模型（如 SenseVoice）返回；可同时多个（多标签）。
    * 常见值：Speech / BGM / Laughter / Applause / Cry / Sneeze / Breath / Cough。
     */
    private List<String> events;

    /**
    * 错误信息
    *
    * <p>仅当状态为 {@link Status#FAILED} 时有效。
     */
    private String errorMessage;

    /**
    * 任务进度百分比
    *
    * <p>取值范围 0-100，部分服务商支持进度反馈。
     */
    private Integer progress;

    /**
    * 用量信息
     */
    private AiUsage usage;

    /**
    * 转写分段
     */
    @Data
    @Builder
    public static class Segment {

        /**
        * 段 ID（服务商的内部索引）
         */
        private Integer id;

        /**
        * 起始时间（秒）
         */
        private Double start;

        /**
        * 结束时间（秒）
         */
        private Double end;

        /**
        * 该段文字
         */
        private String text;

        /**
        * 该段识别到的说话人标签
         */
        private String speaker;
    }
}

