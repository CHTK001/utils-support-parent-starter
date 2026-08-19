package com.chua.common.support.task.message;

import lombok.Getter;

import java.util.Map;

/**
 * 消息响应对象
 *
 * <p>封装消息发送的结果信息，包括成功/失败状态、消息ID、耗时等。
 *
 * @author CH
 * @since 2026/07/17
 */
@Getter
public class MessageResponse {

    /**
     * 是否发送成功
     */
    private final boolean success;

    /**
     * 消息 ID（服务商返回）
     */
    private final String messageId;

    /**
     * 错误信息
     */
    private final String errorMessage;

    /**
     * 耗时（毫秒）
     */
    private final long durationMillis;

    /**
     * 扩展数据
     */
    private final Map<String, Object> data;

    /**
     * 私有构造函数，通过构建器初始化消息响应对象。
     *
     * @param builder 包含所有字段值的构建器实例。
     */
    private MessageResponse(Builder builder) {
        this.success = builder.success;
        this.messageId = builder.messageId;
        this.errorMessage = builder.errorMessage;
        this.durationMillis = builder.durationMillis;
        this.data = builder.data;
    }

    /**
     * 创建一个新的构建器实例。
     *
     * @return 新的 Builder 实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建一个成功的消息响应对象。
     *
     * @param messageId 服务商返回的消息 ID。
     * @return 成功的 MessageResponse 实例。
     */
    public static MessageResponse success(String messageId) {
        return builder().success(true).messageId(messageId).build();
    }

    /**
     * 创建一个失败的消息响应对象。
     *
     * @param errorMessage 描述失败原因的详细信息。
     * @return 失败的 MessageResponse 实例。
     */
    public static MessageResponse failure(String errorMessage) {
        return builder().success(false).errorMessage(errorMessage).build();
    }

    /**
     * 构建器类，用于构建 MessageResponse 对象。
     *
     */
    public static class Builder {

        /**
         * 是否发送成功。
         */
        private boolean success;

        /**
         * 消息 ID（服务商返回）。
         */
        private String messageId;

        /**
         * 错误信息。
         */
        private String errorMessage;

        /**
         * 耗时（毫秒）。
         */
        private long durationMillis;

        /**
         * 扩展数据。
         */
        private Map<String, Object> data;

        /**
         * 设置发送状态。
         *
         * @param success 是否成功。
         * @return 当前构建器实例。
         */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /**
         * 设置消息 ID。
         *
         * @param messageId 消息 ID。
         * @return 当前构建器实例。
         */
        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        /**
         * 设置错误信息。
         *
         * @param errorMessage 错误信息。
         * @return 当前构建器实例。
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * 设置耗时。
         *
         * @param durationMillis 耗时（毫秒）。
         * @return 当前构建器实例。
         */
        public Builder durationMillis(long durationMillis) {
            this.durationMillis = durationMillis;
            return this;
        }

        /**
         * 设置扩展数据。
         *
         * @param data 扩展数据。
         * @return 当前构建器实例。
         */
        public Builder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }

        /**
         * 构建 MessageResponse 对象。
         *
         * @return 构建完成的 MessageResponse 对象。
         */
        public MessageResponse build() {
            return new MessageResponse(this);
        }
    }
}
