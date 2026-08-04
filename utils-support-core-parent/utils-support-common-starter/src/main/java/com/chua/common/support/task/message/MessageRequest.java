package com.chua.common.support.task.message;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 消息请求对象
 * <p>
 * 封装发送消息所需的全部参数，包括接收人、内容、标题、附件等。
 * 通过 Builder 模式构建，支持不同类型的消息需求。
 *
 * @author CH
 * @since 2026/07/17
 */
@NullUnmarked
@Getter
public class MessageRequest {

    /**
     * 接收人（邮箱/手机号/用户ID/群组ID 等）
     */
    private final String to;

    /**
     * 抄送人列表
     */
    private final List<String> cc;

    /**
     * 消息标题（邮件/通知场景）
     */
    private final String subject;

    /**
     * 消息内容（文本）
     */
    @Setter
    /**
     * 内容
     */
    private String content;

    /**
     * 消息类型（text/html/markdown/json 等）
     */
    private final String contentType;

    /**
     * 附件文件路径列表
     */
    private final List<String> attachments;

    /**
     * 模板 ID（使用模板发送时）
     */
    private final String templateId;

    /**
     * 模板参数
     */
    private final Map<String, String> templateParams;

    /**
     * 扩展参数
     */
    private final Map<String, Object> extra;

    private MessageRequest(Builder builder) {
        this.to = builder.to;
        this.cc = builder.cc;
        this.subject = builder.subject;
        this.content = builder.content;
        this.contentType = builder.contentType;
        this.attachments = builder.attachments;
        this.templateId = builder.templateId;
        this.templateParams = builder.templateParams;
        this.extra = builder.extra;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String to;
        private List<String> cc;
        /**
         * 主题
         */
        private String subject;
        /**
         * 内容
         */
        private String content;
        private String contentType = "text";
        private List<String> attachments;
        /**
         * 模板 ID
         */
        private String templateId;
        private Map<String, String> templateParams;
        private Map<String, Object> extra;

        /**
         * 设置接收人
         *
         * @param to 接收人标识
         * @return 当前 Builder 实例
         */
        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /**
         * 设置抄送人列表
         *
         * @param cc 抄送人列表
         * @return 当前 Builder 实例
         */
        public Builder cc(List<String> cc) {
            this.cc = cc;
            return this;
        }

        /**
         * 设置消息标题
         *
         * @param subject 消息标题
         * @return 当前 Builder 实例
         */
        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        /**
         * 设置消息内容
         *
         * @param content 消息内容
         * @return 当前 Builder 实例
         */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * 设置消息类型
         *
         * @param contentType 消息类型（如 text, html, markdown 等）
         * @return 当前 Builder 实例
         */
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        /**
         * 设置附件文件路径列表
         *
         * @param attachments 附件路径列表
         * @return 当前 Builder 实例
         */
        public Builder attachments(List<String> attachments) {
            this.attachments = attachments;
            return this;
        }

        /**
         * 设置模板 ID
         *
         * @param templateId 模板唯一标识
         * @return 当前 Builder 实例
         */
        public Builder templateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        /**
         * 设置模板参数
         *
         * @param params 模板参数字典
         * @return 当前 Builder 实例
         */
        public Builder templateParams(Map<String, String> params) {
            this.templateParams = params;
            return this;
        }

        /**
         * 设置扩展参数
         *
         * @param extra 额外的扩展参数
         * @return 当前 Builder 实例
         */
        public Builder extra(Map<String, Object> extra) {
            this.extra = extra;
            return this;
        }

        /**
         * 构建 MessageRequest 对象
         *
         * @return 构建完成的 MessageRequest 实例
         * @throws IllegalArgumentException 当接收人为空或空白时抛出异常
         */
        public MessageRequest build() {
            if (to == null || to.isBlank()) {
                throw new IllegalArgumentException("接收人不能为空");
            }
            return new MessageRequest(this);
        }
    }
}
