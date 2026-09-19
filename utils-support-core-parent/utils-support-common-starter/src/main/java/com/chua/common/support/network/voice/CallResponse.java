package com.chua.common.support.network.voice;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

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
     * 错误消息（与 {@link #message} 同义，部分调用方使用此命名）
     */
    private String errorMessage;

    /**
     * 调用耗时（毫秒）
     */
    private long duration;

    /**
     * 附加数据（提供商返回的原始负载）
     */
    private Map<String, Object> data;

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
