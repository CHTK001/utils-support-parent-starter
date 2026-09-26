package com.chua.common.support.codec;

import com.chua.common.support.exception.CodecException;

/**
 * 解码器 SPI 根接口 —— 定义"输入 → 输出"的单向解码契约。
 *
 * <p>与 {@link Encoder} 对称，媒体类解码请继承
 * {@code com.chua.common.support.codec.image.ImageDecoder} 或
 * {@code com.chua.common.support.codec.video.VideoDecoder}。</p>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author CH
 * @since 4.0.0.42
 */
public interface Decoder<I, O> {

    /**
     * 执行解码。
     *
     * @param input 解码输入，不允许为 {@code null}
     * @return 解码结果
     * @throws CodecException 输入不合法、数据损坏或底层库失败时抛出
     */
    O decode(I input);

    /**
     * 是否支持指定的源格式。
     *
     * @param format 格式标识，图像侧为扩展名（如 {@code jpeg}），视频侧为编解码器名（如 {@code h264}）
     * @return 支持返回 {@code true}
     */
    boolean supports(String format);
}
