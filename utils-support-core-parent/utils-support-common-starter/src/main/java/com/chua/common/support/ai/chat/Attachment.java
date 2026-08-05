package com.chua.common.support.ai.chat;

import lombok.Builder;

/**
 * AI 附件。
 * <p>
 * 表示对话中的附件，支持二进制数据或 URL 引用。
 * </p>
 *
 * @author CH
 */
@Builder
public record Attachment(
        /**
         * 附件名称
         */
        String name,
        /**
         * 附件二进制数据
         */
        byte[] data,
        /**
         * MIME 类型
         */
        String mimeType,
        /**
         * 附件 URL
         */
        String url
) {
}
