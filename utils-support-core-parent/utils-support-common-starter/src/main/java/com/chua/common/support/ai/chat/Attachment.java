package com.chua.common.support.ai.chat;

import lombok.Builder;

/**
 * AI 附件。
 * 表示对话中的附件，支持二进制数据或 URL 引用。
 *
 * <p>由 {@code ChatClient.addAttachment(name, data, mimeType)}（传二进制）
 * 与 {@code addAttachmentUrl(name, url, mimeType)}（传远端地址）两条入口构造，
 * 二者各填一组字段：走二进制时 {@link #data} 有值、{@link #url} 为 {@code null}，
 * 走 URL 时反之。消费端据此分流：{@link #data} 非空则被 Base64 编码成
 * {@code data:} URI 放进多模态请求，{@link #url} 非空则直接作为
 * {@code image_url} / {@code video_url} 的地址。</p>
 *
 * @param name     附件名称（调用方给的文件名或展示名），用于在会话中标识与区分同一轮的多个附件；
 *                 不参与请求体组装，可为 {@code null}
 * @param data     附件的原始二进制字节（不是 Base64 文本），单位「字节」；
 *                 允许为 {@code null} 或长度 0——此时下游退化为「只有 URL」的附件。
 *                 规范构造器会克隆入参，访问器 {@link #data()} 亦返回副本，故外部改动不回流
 * @param mimeType MIME 类型（如 {@code image/png}、{@code video/mp4}），决定下游把这段内容
 *                 当作图片还是视频分片；允许为 {@code null}，此时按 {@code image/png} 处理。
 *                 仅在有 {@link #data} 或 {@link #url} 时才被读取
 * @param url      附件的远端可访问地址，与 {@link #data} 二选一；
 *                 允许为 {@code null}，此时附件内容完全由 {@link #data} 承载
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
