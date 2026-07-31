package com.chua.common.support.ai.video;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;

/**
 * AI 视频生成任务响应
 *
 * <p>封装异步视频生成任务的当前状态和结果数据。
 * 调用方通过 {@code VideoClient#queryTask(String)} 获取此对象以判断任务进度和获取视频。
 *
 * @author CH
 */
@Data
@Builder
public class VideoResponse {

    /**
     * 任务状态枚举
     *
     * <p>定义视频生成任务的完整生命周期状态。
     */
    public enum Status {

        /**
         * 任务已创建
         *
         * <p>任务已提交到队列，等待服务端调度执行。
         */
        PENDING,

        /**
         * 任务执行中
         *
         * <p>服务端正在处理生成任务，可轮询进度。
         */
        RUNNING,

        /**
         * 任务成功
         *
         * <p>视频生成完成，视频可通过 {@link #videoUrl} 访问。
         */
        SUCCESS,

        /**
         * 任务失败
         *
         * <p>生成过程中发生错误，详情可通过 {@link #errorMessage} 查看。
         */
        FAILED
    }

    /**
     * 任务 ID
     *
     * <p>由 {@code VideoClient#createTask(String)} 返回的唯一任务标识。
     */
    private String taskId;

    /**
     * 任务当前状态
     */
    private Status status;

    /**
     * 生成视频的访问 URL
     *
     * <p>仅当状态为 {@link Status#SUCCESS} 时有效。
     * 指向服务端生成的视频文件地址。
     */
    private String videoUrl;

    /**
     * 错误信息
     *
     * <p>仅当状态为 {@link Status#FAILED} 时有效，包含失败原因。
     */
    private String errorMessage;

    /**
     * 任务进度百分比
     *
     * <p>取值范围 0-100，部分服务商支持进度反馈。
     * 不支持的实现返回 null。
     */
    private Integer progress;

    /**
     * 视频时长（秒）
     *
     * <p>生成视频的实际时长，单位为秒。
     */
    private Integer duration;

    /**
     * 实际使用的随机种子
     *
     * <p>生成视频时实际使用的种子值，可用于复现相同结果。
     */
    private Long seed;

    /**
     * 用量信息
     *
     * <p>包含本次视频生成调用的费用信息。
     * 视频生成服务通常按次或按时长计费，
     * 因此 {@link AiUsage#getTotalCost()} 字段为本次生成的费用，
     * Token 相关字段通常为 null。
     * 部分服务商可能返回 Token 用量，此时相应字段会被填充。
     */
    private AiUsage usage;
}
