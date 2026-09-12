package com.chua.common.support.datasearch.email.model;

import lombok.Builder;
import lombok.Data;

/**
 * 临时邮箱收到的邮件信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class EmailInfo {

    /**
      * 邮件 标识
     */
    private String id;

    /**
     * 发件人
     */
    private String from;

    /**
     * 主题
     */
    private String subject;

    /**
     * 正文（纯文本）
     */
    private String body;

    /**
     * HTML 正文
     */
    private String html;

    /**
     * 收到时间（ISO 8601 字符串）
     */
    private String createdAt;
}
