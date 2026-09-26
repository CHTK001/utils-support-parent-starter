package com.chua.common.support.ai.chat;

import lombok.Builder;

/**
 * AI 附件。
 * <p>
 * 表示对话中的附件，支持二进制数据或 URL 引用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
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

    /**
     * 规范构造器：数组做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。
     * 附件支持「二进制数据」与「URL 引用」二选一，仅传 url 时 data 就是 null，
     * 故保留 null 语义。</p>
     *
     * @param name     附件名称
     * @param data     附件二进制数据，允许为 null
     * @param mimeType MIME 类型
     * @param url      附件 URL，允许为 null
     */
    public Attachment {
        data = data == null ? null : data.clone();
    }

    /**
     * 访问器覆写：返回内部数组的副本。
     *
     * @return 数组副本，无二进制数据时返回 null
     */
    @Override
    public byte[] data() {
        return data == null ? null : data.clone();
    }
}
