package com.chua.common.support.ai.image;

import com.chua.common.support.ai.AiUsage;
import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

/**
 * AI 图片生成任务响应
 *
 * <p>封装异步图片生成任务的当前状态和结果数据。
 * 调用方通过 {@link ImageClient#queryTask(String)} 获取此对象以判断任务进度和获取图片。
 *
 * @author CH
 */
@NullUnmarked
@Data
@Builder
public class ImageResponse {

    /**
     * 任务状态枚举
     *
     * <p>定义图片生成任务的完整生命周期状态。
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
         * <p>图片生成完成，图片数据可通过 {@link #imageBytes} 或 {@link #imageUrl} 获取。
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
     * <p>由 {@link ImageClient#createTask(String)} 返回的唯一任务标识。
     */
    private String taskId;

    /**
     * 任务当前状态
     */
    private Status status;

    /**
     * 生成图片的字节数组
     *
     * <p>仅当状态为 {@link Status#SUCCESS} 时有效。
     * 部分服务商直接返回字节数据，部分仅返回 URL。
     */
    private byte[] imageBytes;

    /**
     * 生成图片的访问 URL
     *
     * <p>仅当状态为 {@link Status#SUCCESS} 时有效。
     * 部分服务商返回图片的远程访问地址而非字节数据。
     */
    private String imageUrl;

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
     * 实际使用的随机种子
     *
     * <p>生成图片时实际使用的种子值，可用于复现相同结果。
     */
    private Long seed;

    /**
     * 用量信息
     *
     * <p>包含本次图片生成调用的费用信息。
     * 图片生成服务通常按次计费而非按 Token 计费，
     * 因此 {@link AiUsage#getTotalCost()} 字段为本次生成的费用，
     * 而 Token 相关字段（inputTokens、outputTokens）通常为 null 或 0。
     * 部分服务商可能返回 Token 用量，此时相应字段会被填充。
     */
    private AiUsage usage;
}
