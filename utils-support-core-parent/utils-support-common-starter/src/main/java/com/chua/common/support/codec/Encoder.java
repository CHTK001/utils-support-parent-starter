package com.chua.common.support.codec;

import com.chua.common.support.exception.CodecException;

/**
 * 编码器 SPI 根接口 —— 定义"输入 → 输出"的单向编码契约。
 *
 * <p>本接口只约定最通用的编码形态，媒体类编码请继承
 * {@code com.chua.common.support.codec.image.ImageEncoder} 或
 * {@code com.chua.common.support.codec.video.VideoEncoder}，
 * 以便调用方按能力域获取实现。</p>
 *
 * <p>实现类通过 {@code @Spi("name")} 注册，由
 * {@code com.chua.common.support.spi.ServiceProvider} 发现；
 * 有状态的编码器（如硬件视频编码器）应以
 * {@code ServiceProvider.of(VideoEncoder.class).getNewExtension(name)} 取得独立实例。</p>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 * @author CH
 * @since 4.0.0.42
 */
public interface Encoder<I, O> {

    /**
     * 执行编码。
     *
     * @param input 编码输入，不允许为 {@code null}
     * @return 编码结果
     * @throws CodecException 输入不合法、底层库失败或输出格式不受支持时抛出
     */
    O encode(I input);

    /**
     * 是否支持指定的目标格式。
     *
     * @param format 格式标识，图像侧为扩展名（如 {@code jpeg}），视频侧为编解码器名（如 {@code h264}）
     * @return 支持返回 {@code true}
     */
    boolean supports(String format);
}
