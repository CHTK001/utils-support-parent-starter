package com.chua.common.support.codec.image;

import com.chua.common.support.codec.Encoder;

import java.awt.image.BufferedImage;

/**
 * 图像编码器 SPI —— 把 {@link BufferedImage} 编码为某种图像格式的字节流。
 *
 * <p>实现示例：{@code TurboJpegImageEncoder}（libjpeg-turbo 原生实现）、
 * 基于 ImageIO 的纯 Java 实现。</p>
 *
 * <p>编码器实例为无状态或仅持有默认参数，可在多线程下共享；
 * 具体质量档位通过 {@link #encode(BufferedImage, float)} 逐次传入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageEncoder extends Encoder<BufferedImage, byte[]> {

    /**
     * 该实现的默认编码质量。
     *
     * @return 质量取值，范围 {@code (0, 1]}
     */
    float quality();

    /**
     * 以指定质量编码图像。
     *
     * @param image   待编码图像，不允许为 {@code null}
     * @param quality 编码质量，取值 {@code (0, 1]}
     * @return 编码后的完整图像文件字节
     */
    byte[] encode(BufferedImage image, float quality);

    /**
     * 以 {@link #quality()} 默认质量编码图像。
     *
     * @param input 待编码图像
     * @return 编码后的完整图像文件字节
     */
    @Override
    default byte[] encode(BufferedImage input) {
        return encode(input, quality());
    }
}
