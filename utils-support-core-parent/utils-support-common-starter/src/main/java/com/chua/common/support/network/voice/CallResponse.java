package com.chua.common.support.network.voice;

import lombok.Builder;
import lombok.Data;

/**
 * 语音呼叫响应结果。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class CallResponse {

    /**
     * 呼叫 ID
     */
    private String callId;

    /**
     * 呼叫状态（如 "queued"、"ringing"、"in-progress"、"completed"、"failed"）
     */
    private String status;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 附加消息（错误描述等）
     */
    private String message;

    /**
     * 快速构建成功响应。
     *
     * @param callId 呼叫 ID
     * @return 成功响应
     */
    public static CallResponse success(String callId) {
        return CallResponse.builder()
                .callId(callId)
                .status("queued")
                .success(true)
                .build();
    }

    /**
     * 快速构建失败响应。
     *
     * @param message 错误消息
     * @return 失败响应
     */
    public static CallResponse failure(String message) {
        return CallResponse.builder()
                .status("failed")
                .success(false)
                .message(message)
                .build();
    }
}
